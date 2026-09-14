package com.siberanka.twiopsec.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLoggerTest {
    @TempDir
    Path temporary;

    @Test
    void drainsEscapesAndRotatesWithoutDroppingQueuedEntries() throws Exception {
        AuditLogger audit = new AuditLogger(temporary, 128, Logger.getAnonymousLogger(),
                Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC), 1024L, 5);
        for (int index = 0; index < 8; index++) {
            audit.record("action\n" + index, "Player\"", "uuid", "x".repeat(220));
        }

        audit.close();

        assertEquals(0L, audit.droppedEvents());
        assertTrue(Files.isRegularFile(temporary.resolve("audit.jsonl")));
        assertTrue(Files.isRegularFile(temporary.resolve("audit.1.jsonl")));
        List<String> lines;
        try (var stream = Files.list(temporary)) {
            lines = stream.filter(path -> path.getFileName().toString().matches("audit(?:\\.[1-5])?\\.jsonl"))
                    .flatMap(path -> {
                        try {
                            return Files.readAllLines(path).stream();
                        } catch (java.io.IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    }).toList();
        }
        assertEquals(8, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.startsWith("{\"timestamp\":")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("action 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Player\\\"")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("action\\n")));
    }

    @Test
    void permanentIoFailureDoesNotHangServerShutdown() throws Exception {
        Path blockedDataFolder = temporary.resolve("not-a-directory");
        Files.writeString(blockedDataFolder, "blocking file");
        AuditLogger audit = new AuditLogger(blockedDataFolder, 128, Logger.getAnonymousLogger());
        audit.record("shutdown-test", "server", "", "pending");

        long started = System.nanoTime();
        audit.close();
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 3_000L);
        assertFalse(audit.isHealthy());
        assertEquals(1L, audit.droppedEvents());
    }
}
