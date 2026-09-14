package com.siberanka.twiopsec.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Strict, bounded YAML reader shared by runtime configuration and migration. */
final class SecureYaml {
    static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_ALIASES = 20;
    private static final int MAX_NESTING_DEPTH = 32;

    private SecureYaml() {
    }

    static Document load(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("YAML file is missing or is not a regular file: " + normalized.getFileName());
        }
        long declaredSize = Files.size(normalized);
        if (declaredSize <= 0L || declaredSize > MAX_BYTES) {
            throw new IOException("YAML size is outside safety limits: " + normalized.getFileName());
        }

        byte[] bytes = Files.readAllBytes(normalized);
        if (bytes.length <= 0 || bytes.length > MAX_BYTES) {
            throw new IOException("YAML changed or exceeded safety limits while reading: " + normalized.getFileName());
        }
        String text = decodeUtf8(bytes, normalized);
        validateSyntax(text, normalized);

        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(text);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + normalized.getFileName() + ": " + safeMessage(exception), exception);
        }
        return new Document(configuration, text, bytes.clone(), sha256(bytes));
    }

    static Document parse(String text, String sourceName) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IOException("YAML is empty: " + sourceName);
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("YAML size is outside safety limits: " + sourceName);
        }
        validateSyntax(text, Path.of(sourceName));
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(text);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + sourceName + ": " + safeMessage(exception), exception);
        }
        return new Document(configuration, text, bytes.clone(), sha256(bytes));
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String decodeUtf8(byte[] bytes, Path path) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("YAML is not valid UTF-8: " + path.getFileName(), exception);
        }
    }

    private static void validateSyntax(String text, Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setCodePointLimit((int) MAX_BYTES);
        try {
            Object root = new Yaml(new SafeConstructor(options)).load(text);
            if (!(root instanceof Map<?, ?>)) {
                throw new IOException("YAML root must be a mapping: " + path.getFileName());
            }
        } catch (YAMLException exception) {
            throw new IOException("Unsafe or invalid YAML in " + path.getFileName() + ": " + safeMessage(exception), exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String clean = message.replace('\r', ' ').replace('\n', ' ');
        return clean.substring(0, Math.min(clean.length(), 200));
    }

    record Document(YamlConfiguration configuration, String text, byte[] bytes, String sha256) {
        Document {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
