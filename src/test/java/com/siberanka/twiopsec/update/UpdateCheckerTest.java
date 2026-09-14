package com.siberanka.twiopsec.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    @Test
    void usesValidGithubReleaseAsAuthoritativeSource() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = new UpdateChecker("1.2.0", 5, (endpoint, timeout) -> {
            calls.incrementAndGet();
            return URI.create("https://github.com/siberanka/TwiOpSec/releases/tag/v1.3.0");
        });

        UpdateChecker.Result result = checker.check();

        assertEquals(UpdateChecker.Status.AVAILABLE, result.status());
        assertEquals(UpdateChecker.Source.GITHUB, result.source());
        assertEquals("1.3.0", result.latestVersion());
        assertEquals(1, calls.get());
    }

    @Test
    void fallsBackToGitlabOnlyWhenGithubCannotBeValidated() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = new UpdateChecker("1.2.0", 5, (endpoint, timeout) -> {
            calls.incrementAndGet();
            if (endpoint.getHost().equals("github.com")) {
                return URI.create("https://evil.invalid/siberanka/TwiOpSec/releases/tag/v9.9.9");
            }
            return URI.create("https://gitlab.com/siberanka/TwiOpSec/-/releases/v1.2.1");
        });

        UpdateChecker.Result result = checker.check();

        assertEquals(UpdateChecker.Status.AVAILABLE, result.status());
        assertEquals(UpdateChecker.Source.GITLAB, result.source());
        assertEquals(2, calls.get());
    }

    @Test
    void validGithubOlderVersionPreventsBackupFromOverridingPrimary() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = new UpdateChecker("2.0.0", 5, (endpoint, timeout) -> {
            calls.incrementAndGet();
            return URI.create("https://github.com/siberanka/TwiOpSec/releases/tag/v1.9.9");
        });

        UpdateChecker.Result result = checker.check();

        assertEquals(UpdateChecker.Status.UP_TO_DATE, result.status());
        assertEquals(UpdateChecker.Source.GITHUB, result.source());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsUntrustedRedirectShapesAndReportsBoundedFailure() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = new UpdateChecker("1.2.0", 5, (endpoint, timeout) -> {
            calls.incrementAndGet();
            throw new IOException("secret-rich environment message that must not escape");
        });

        UpdateChecker.Result result = checker.check();

        assertEquals(UpdateChecker.Status.UNAVAILABLE, result.status());
        assertEquals("github=io-error,gitlab=io-error", result.detail());
        assertEquals(2, calls.get());
        assertFalse(UpdateChecker.validatedTag(UpdateChecker.Source.GITHUB,
                URI.create("https://github.com.evil.invalid/siberanka/TwiOpSec/releases/tag/v9.9.9")).isPresent());
        assertFalse(UpdateChecker.validatedTag(UpdateChecker.Source.GITHUB,
                URI.create("https://github.com/siberanka/TwiOpSec/releases/tag/v9.9.9?asset=evil")).isPresent());
        assertFalse(UpdateChecker.validatedTag(UpdateChecker.Source.GITLAB,
                URI.create("https://gitlab.com:444/siberanka/TwiOpSec/-/releases/v9.9.9")).isPresent());
        assertTrue(UpdateChecker.validatedTag(UpdateChecker.Source.GITLAB,
                URI.create("https://gitlab.com/siberanka/TwiOpSec/-/releases/v9.9.9")).isPresent());
    }

    @Test
    void comparesAllStableSemanticVersionComponents() {
        UpdateChecker checker = new UpdateChecker("1.10.0", 5, (endpoint, timeout) ->
                URI.create("https://github.com/siberanka/TwiOpSec/releases/tag/v2.0.0"));
        assertEquals(UpdateChecker.Status.AVAILABLE, checker.check().status());

        UpdateChecker invalidCurrent = new UpdateChecker("development", 5, (endpoint, timeout) ->
                URI.create("https://github.com/siberanka/TwiOpSec/releases/tag/v9.9.9"));
        assertEquals(UpdateChecker.Status.UNAVAILABLE, invalidCurrent.check().status());
    }
}
