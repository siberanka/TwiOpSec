package com.siberanka.twiopsec.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerAliasLoaderTest {
    @TempDir
    Path temporary;

    @Test
    void loadsBoundedAliases() throws IOException {
        Files.writeString(temporary.resolve("commands.yml"), """
                aliases:
                  safe:
                  - version $1-
                  dangerous:
                  - op $1
                """);

        var aliases = ServerAliasLoader.load(temporary);

        assertEquals("op $1", aliases.get("dangerous").getFirst());
    }

    @Test
    void rejectsMalformedAndMistypedAliases() throws IOException {
        Files.writeString(temporary.resolve("commands.yml"), "aliases: {a: [version], a: [op Intruder]}\n");
        assertThrows(IOException.class, () -> ServerAliasLoader.load(temporary));

        Files.writeString(temporary.resolve("commands.yml"), "aliases:\n  unsafe: [op Intruder, 42]\n");
        assertThrows(IOException.class, () -> ServerAliasLoader.load(temporary));
    }

    @Test
    void acceptsTurkishAliasNamesAndRejectsUnsafeSeparators() throws IOException {
        Files.writeString(temporary.resolve("commands.yml"), """
                aliases:
                  görevler: [quests]
                  cüzdan: [balance]
                  İzin: [version]
                """);
        var aliases = ServerAliasLoader.load(temporary);
        assertEquals("quests", aliases.get("görevler").getFirst());
        assertEquals("balance", aliases.get("cüzdan").getFirst());
        assertEquals("version", aliases.get("i\u0307zin").getFirst());

        Files.writeString(temporary.resolve("commands.yml"), "aliases:\n  'görevler/op': [op Intruder]\n");
        assertThrows(IOException.class, () -> ServerAliasLoader.load(temporary));
        Files.writeString(temporary.resolve("commands.yml"), "aliases:\n  'görevler stop': [op Intruder]\n");
        assertThrows(IOException.class, () -> ServerAliasLoader.load(temporary));
    }
}
