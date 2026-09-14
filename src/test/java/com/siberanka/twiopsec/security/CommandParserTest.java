package com.siberanka.twiopsec.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParserTest {
    @Test
    void normalizesNamespacedLabelsAndWhitespace() {
        CommandParser.ParsedCommand command = CommandParser.parse(" /Minecraft:OP   Alice ");

        assertEquals("minecraft", command.namespace());
        assertEquals("op", command.label());
        assertEquals("Alice", command.arguments().getFirst());
    }

    @Test
    void blocksKnownRuntimeUnloadFormsButNotServerStopOrDatapackReload() {
        assertTrue(CommandParser.attemptsRuntimeUnload(CommandParser.parse("plugman disable TwiOpSec")));
        assertTrue(CommandParser.attemptsRuntimeUnload(CommandParser.parse("plugmanx:plm reload all")));
        assertTrue(CommandParser.attemptsRuntimeUnload(CommandParser.parse("serverutils plugin unload TwiOpSec.jar")));
        assertTrue(CommandParser.attemptsRuntimeUnload(CommandParser.parse("plugmanager disable --all")));
        assertTrue(CommandParser.attemptsRuntimeUnload(CommandParser.parse("plugman reload plugin:TwiOpSec")));
        assertTrue(CommandParser.attemptsRuntimeUnload(CommandParser.parse("bukkit:reload")));
        assertFalse(CommandParser.attemptsRuntimeUnload(CommandParser.parse("minecraft:reload")));
        assertFalse(CommandParser.attemptsRuntimeUnload(CommandParser.parse("stop")));
        assertFalse(CommandParser.attemptsRuntimeUnload(CommandParser.parse("plugman disable OtherPlugin")));
    }

    @Test
    void rejectsMultilineAndOversizedInput() {
        assertTrue(CommandParser.parse("op Alice\nstop").label().isEmpty());
        assertTrue(CommandParser.parse("x".repeat(1025)).label().isEmpty());
    }
}
