package com.siberanka.twiopsec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPluginDescriptorTest {
    @Test
    void shipsOnlyTheModernPaperDescriptor() throws IOException {
        ClassLoader loader = PaperPluginDescriptorTest.class.getClassLoader();
        URL descriptor = loader.getResource("paper-plugin.yml");

        assertNotNull(descriptor);
        assertNull(loader.getResource("plugin.yml"));
        String yaml;
        try (InputStream input = descriptor.openStream()) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yaml.contains("main: com.siberanka.twiopsec.TwiOpSecPlugin"));
        assertTrue(yaml.contains("folia-supported: true"));
        assertFalse(yaml.contains("commands:"));
    }
}
