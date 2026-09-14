package com.siberanka.twiopsec.security;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bounded, single-writer JSONL audit sink. Gameplay threads never perform file I/O. */
public final class AuditLogger implements AutoCloseable {
    private final ArrayBlockingQueue<Entry> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writerThread;
    private final Path file;
    private final Logger logger;
    private final Clock clock;

    public AuditLogger(Path dataFolder, int capacity, Logger logger) {
        this(dataFolder, capacity, logger, Clock.systemUTC());
    }

    AuditLogger(Path dataFolder, int capacity, Logger logger, Clock clock) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.file = dataFolder.resolve("audit.jsonl");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = clock;
        this.writerThread = Thread.ofPlatform().name("TwiOpSec-Audit").daemon(true).start(this::writeLoop);
    }

    public void record(String action, String player, String uuid, String detail) {
        if (!running.get()) {
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

    private void writeLoop() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (running.get() || !queue.isEmpty()) {
                    Entry entry = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (entry != null) {
                        writer.write(entry.toJson());
                        writer.newLine();
                    }
                    if (queue.isEmpty()) {
                        writer.flush();
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "TwiOpSec audit writer stopped after an I/O failure", exception);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            writerThread.join(2_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        String result = value.replace('\r', ' ').replace('\n', ' ');
        return result.substring(0, Math.min(result.length(), max));
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
