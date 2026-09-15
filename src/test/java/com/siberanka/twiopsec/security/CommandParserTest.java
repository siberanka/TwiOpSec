package com.siberanka.twiopsec.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void parsesTurkishAliasesAndExpandsProtectedCommands() {
        var command = CommandParser.parse(" /görevler 1 ");
        assertEquals(CommandParser.ParseStatus.VALID, command.status());
        assertEquals("görevler", command.label());
        assertTrue(CommandParser.parseChain("cüzdan Intruder", Map.of("cüzdan", List.of("op $1")), "CONSOLE")
                .stream().anyMatch(parsed -> parsed.label().equals("op")
                        && parsed.arguments().equals(List.of("Intruder"))));
        assertEquals(CommandParser.ParseStatus.INVALID, CommandParser.parse("görevler/op Intruder").status());
        assertEquals(CommandParser.ParseStatus.INVALID, CommandParser.parse("оp Intruder").status());
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
        assertEquals(CommandParser.ParseStatus.INVALID, CommandParser.parse("op Alice\nstop").status());
    }

    @Test
    void exposesNestedAndNamespacedCommandPayloadsWithoutUnboundedRecursion() {
        var chain = CommandParser.parseChain(
                "minecraft:execute as @a run execute positioned 0 0 0 run minecraft:op Alice");

        assertEquals("execute", chain.getFirst().label());
        assertTrue(chain.stream().anyMatch(command -> command.label().equals("op")
                && command.arguments().getFirst().equals("Alice")));
        assertTrue(CommandParser.parseChain("execute run bukkit:reload confirm").stream()
                .anyMatch(CommandParser::attemptsRuntimeUnload));
        assertTrue(CommandParser.parseChain("sudo Alice /plugman unload TwiOpSec").stream()
                .anyMatch(CommandParser::attemptsRuntimeUnload));
    }

    @Test
    void expandsBukkitAliasesAndArgumentsWithBoundedRecursion() {
        Map<String, List<String>> aliases = Map.of(
                "fixedop", List.of("op Intruder"),
                "argop", List.of("minecraft:op $1"),
                "nested", List.of("execute run fixedop"),
                "cycle", List.of("cycle")
        );

        assertTrue(CommandParser.parseChain("fixedop", aliases, "CONSOLE").stream()
                .anyMatch(command -> command.label().equals("op")));
        assertTrue(CommandParser.parseChain("argop Intruder", aliases, "CONSOLE").stream()
                .anyMatch(command -> command.label().equals("op")
                        && command.arguments().equals(List.of("Intruder"))));
        assertTrue(CommandParser.parseChain("nested", aliases, "CONSOLE").stream()
                .anyMatch(command -> command.label().equals("op")));
        assertTrue(CommandParser.parseChain("cycle", aliases, "CONSOLE").stream()
                .anyMatch(command -> command.status() == CommandParser.ParseStatus.INVALID));
        assertTrue(CommandParser.parseChain("argop", Map.of("argop", List.of("op $$1")), "CONSOLE").stream()
                .anyMatch(command -> command.status() == CommandParser.ParseStatus.INVALID));
        assertTrue(CommandParser.parseChain("senderop", Map.of("senderop", List.of("op $sender")), "Intruder")
                .stream().anyMatch(command -> command.arguments().equals(List.of("Intruder"))));
        assertTrue(CommandParser.parseChain("allargs one two",
                        Map.of("allargs", List.of("op $2-")), "CONSOLE").stream()
                .anyMatch(command -> command.arguments().equals(List.of("two"))));
        assertTrue(CommandParser.parseChain("literal", Map.of("literal", List.of("say \\$1")), "CONSOLE")
                .stream().anyMatch(command -> command.arguments().equals(List.of("$1"))));
    }
}
