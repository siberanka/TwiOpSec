package com.siberanka.twiopsec.security;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bounded, rotating, retrying single-writer JSONL audit sink. Gameplay threads never perform file I/O. */
public final class AuditLogger implements AutoCloseable {
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int RETAINED_ARCHIVES = 5;
    private static final long RETRY_MILLIS = 1_000L;
    private final ArrayBlockingQueue<Entry> queue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");
    private final Thread writerThread;
    private final Path file;
    private final Logger logger;
    private final Clock clock;
    private final long maxFileBytes;
    private final int retainedArchives;

    public AuditLogger(Path dataFolder, int capacity, Logger logger) {
        this(dataFolder, capacity, logger, Clock.systemUTC());
    }

    AuditLogger(Path dataFolder, int capacity, Logger logger, Clock clock) {
        this(dataFolder, capacity, logger, clock, MAX_FILE_BYTES, RETAINED_ARCHIVES);
    }

    AuditLogger(Path dataFolder, int capacity, Logger logger, Clock clock, long maxFileBytes, int retainedArchives) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.file = dataFolder.resolve("audit.jsonl");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = clock;
        this.maxFileBytes = Math.max(1024L, maxFileBytes);
        this.retainedArchives = Math.max(1, Math.min(retainedArchives, RETAINED_ARCHIVES));
        this.writerThread = Thread.ofPlatform().name("TwiOpSec-Audit").daemon(true).start(this::writeLoop);
    }

    public void record(String action, String player, String uuid, String detail) {
        if (!accepting.get()) {
            return;
        }
        Entry entry = new Entry(Instant.now(clock).toString(), clean(action, 64), clean(player, 32),
                clean(uuid, 40), clean(detail, 256));
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
        }
    }

    public long droppedEvents() {
        return dropped.get();
    }

    public boolean isHealthy() {
        return healthy.get() && writerThread.isAlive();
    }

    public String lastFailure() {
        return lastFailure.get();
    }

    private void writeLoop() {
        BufferedWriter writer = null;
        Entry pending = null;
        long bytesWritten = 0L;
        boolean failureLogged = false;
        try {
            while (accepting.get() || !queue.isEmpty()) {
                try {
                    if (writer == null) {
                        Files.createDirectories(file.toAbsolutePath().getParent());
                        rotateIfFull();
                        bytesWritten = Files.exists(file) ? Files.size(file) : 0L;
                        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                        healthy.set(true);
                        lastFailure.set("");
                        failureLogged = false;
                    }

                    if (pending == null) {
                        pending = queue.poll(250, TimeUnit.MILLISECONDS);
                    }
                    if (pending != null) {
                        String line = pending.toJson();
                        long encodedLength = line.getBytes(StandardCharsets.UTF_8).length
                                + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
                        if (bytesWritten > 0L && bytesWritten + encodedLength > maxFileBytes) {
                            writer.flush();
                            writer.close();
                            rotate();
                            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                            bytesWritten = 0L;
                        }
                        writer.write(line);
                        writer.newLine();
                        bytesWritten += encodedLength;
                        pending = null;
                    }
                    if (queue.isEmpty()) {
                        writer.flush();
                    }
                } catch (IOException exception) {
                    closeQuietly(writer);
                    writer = null;
                    healthy.set(false);
                    lastFailure.set(clean(exception.getClass().getSimpleName() + ": " + exception.getMessage(), 200));
                    if (!failureLogged) {
                        logger.log(Level.SEVERE, "TwiOpSec audit I/O failed; the writer will retry", exception);
                        failureLogged = true;
                    }
                    if (!accepting.get()) {
                        long abandoned = queue.size() + (pending == null ? 0L : 1L);
                        queue.clear();
                        dropped.addAndGet(abandoned);
                        break;
                    }
                    Thread.sleep(RETRY_MILLIS);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            long abandoned = queue.size() + (pending == null ? 0L : 1L);
            dropped.addAndGet(abandoned);
            healthy.set(false);
            lastFailure.compareAndSet("", "audit writer interrupted before draining " + abandoned + " entries");
        } finally {
            closeQuietly(writer);
        }
    }

    private void rotateIfFull() throws IOException {
        if (Files.isRegularFile(file) && Files.size(file) >= maxFileBytes) {
            rotate();
        }
    }

    private void rotate() throws IOException {
        Path oldest = archive(retainedArchives);
        Files.deleteIfExists(oldest);
        for (int index = retainedArchives - 1; index >= 1; index--) {
            Path source = archive(index);
            if (Files.exists(source)) {
                moveReplacing(source, archive(index + 1));
            }
        }
        if (Files.exists(file)) {
            moveReplacing(file, archive(1));
        }
    }

    private Path archive(int index) {
        return file.resolveSibling("audit." + index + ".jsonl");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // The health failure was or will be reported by the writer loop.
        }
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        try {
            writerThread.join(2_000L);
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                writerThread.join(500L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        String result = value.replace('\r', ' ').replace('\n', ' ');
        int end = Math.min(result.length(), max);
        if (end > 0 && end < result.length() && Character.isHighSurrogate(result.charAt(end - 1))
                && Character.isLowSurrogate(result.charAt(end))) {
            end--;
        }
        return result.substring(0, end);
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record Entry(String timestamp, String action, String player, String uuid, String detail) {
        String toJson() {
            return "{\"timestamp\":\"" + json(timestamp) + "\",\"action\":\"" + json(action)
                    + "\",\"player\":\"" + json(player) + "\",\"uuid\":\"" + json(uuid)
                    + "\",\"detail\":\"" + json(detail) + "\"}";
        }
    }
}
