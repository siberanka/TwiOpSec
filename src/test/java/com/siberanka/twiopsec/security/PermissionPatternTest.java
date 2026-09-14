package com.siberanka.twiopsec.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPatternTest {
    @Test
    void exactAndNamespaceWildcardsAreCaseInsensitive() {
        PermissionPattern exact = PermissionPattern.parse("minecraft.command.op");
        PermissionPattern wildcard = PermissionPattern.parse("Essentials.*");

        assertTrue(exact.matches("MINECRAFT.COMMAND.OP"));
        assertFalse(exact.matches("minecraft.command.op.other"));
        assertTrue(wildcard.matches("essentials.fly"));
        assertTrue(wildcard.matches("essentials.*"));
        assertFalse(wildcard.matches("essential.fly"));
    }

    @Test
    void globalStarRepresentsTheActualRootNodeNotEveryPermission() {
        PermissionPattern global = PermissionPattern.parse("*");

        assertTrue(global.isGlobalNode());
        assertTrue(global.matches("*"));
        assertFalse(global.matches("minecraft.command.help"));
    }

    @Test
    void rejectsAmbiguousOrDangerousPatterns() {
        assertThrows(IllegalArgumentException.class, () -> PermissionPattern.parse("mine*craft"));
        assertThrows(IllegalArgumentException.class, () -> PermissionPattern.parse("minecraft.**"));
        assertThrows(IllegalArgumentException.class, () -> PermissionPattern.parse("mine\ncraft"));
    }
}
