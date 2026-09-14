package com.siberanka.twiopsec.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {
    private static final URI GITHUB_LATEST = URI.create("https://github.com/siberanka/TwiOpSec/releases/latest");
    private static final URI GITLAB_LATEST =
            URI.create("https://gitlab.com/siberanka/TwiOpSec/-/releases/permalink/latest");
    private static final Pattern VERSION = Pattern.compile("v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final int MAX_REDIRECT_LENGTH = 512;

    private final String currentVersion;
    private final Duration requestTimeout;
    private final RedirectProbe probe;

    public UpdateChecker(String currentVersion, int connectTimeoutSeconds, int requestTimeoutSeconds) {
        this(currentVersion, requestTimeoutSeconds,
                new HttpRedirectProbe(Duration.ofSeconds(connectTimeoutSeconds), currentVersion));
    }

    UpdateChecker(String currentVersion, int requestTimeoutSeconds, RedirectProbe probe) {
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    public Result check() {
        Optional<SemanticVersion> current = SemanticVersion.parse(currentVersion);
        if (current.isEmpty()) {
            return Result.unavailable(currentVersion, "invalid-current-version");
        }

        SourceAttempt primary = attempt(Source.GITHUB, GITHUB_LATEST, current.get());
        if (primary.result() != null) {
            return primary.result();
        }
        if (Thread.currentThread().isInterrupted()) {
            return Result.unavailable(currentVersion, "interrupted");
        }

        SourceAttempt backup = attempt(Source.GITLAB, GITLAB_LATEST, current.get());
        if (backup.result() != null) {
            return backup.result();
        }
        return Result.unavailable(currentVersion,
                "github=" + primary.failure() + ",gitlab=" + backup.failure());
    }

    private SourceAttempt attempt(Source source, URI endpoint, SemanticVersion current) {
        try {
            URI release = probe.redirect(endpoint, requestTimeout);
            Optional<String> tag = validatedTag(source, release);
            if (tag.isEmpty()) {
                return SourceAttempt.failed("invalid-response");
            }
            Optional<SemanticVersion> latest = SemanticVersion.parse(tag.get());
            if (latest.isEmpty()) {
                return SourceAttempt.failed("invalid-version");
            }
            Status status = latest.get().compareTo(current) > 0 ? Status.AVAILABLE : Status.UP_TO_DATE;
            return SourceAttempt.success(new Result(status, currentVersion, latest.get().text(), release, source, "ok"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SourceAttempt.failed("interrupted");
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return SourceAttempt.failed(safeFailure(exception));
        }
    }

    static Optional<String> validatedTag(Source source, URI uri) {
        if (uri == null || uri.toASCIIString().length() > MAX_REDIRECT_LENGTH
                || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return Optional.empty();
        }
        String expectedHost = source == Source.GITHUB ? "github.com" : "gitlab.com";
        if (uri.getHost() == null || !expectedHost.equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String prefix = source == Source.GITHUB
                ? "/siberanka/TwiOpSec/releases/tag/"
                : "/siberanka/TwiOpSec/-/releases/";
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith(prefix)) {
            return Optional.empty();
        }
        String tag = rawPath.substring(prefix.length());
        return VERSION.matcher(tag).matches() ? Optional.of(tag) : Optional.empty();
    }

    private static String safeFailure(Exception exception) {
        if (exception instanceof java.net.http.HttpTimeoutException) {
            return "timeout";
        }
        if (exception instanceof IOException) {
            return "io-error";
        }
        return "invalid-response";
    }

    public enum Status {
        DISABLED,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        UNAVAILABLE
    }

    public enum Source {
        GITHUB,
        GITLAB
    }

    public record Result(Status status, String currentVersion, String latestVersion,
                         URI releaseUri, Source source, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            currentVersion = currentVersion == null ? "unknown" : currentVersion;
            detail = detail == null ? "" : detail;
        }

        public static Result disabled(String currentVersion) {
            return new Result(Status.DISABLED, currentVersion, null, null, null, "disabled");
        }

        public static Result checking(String currentVersion) {
            return new Result(Status.CHECKING, currentVersion, null, null, null, "checking");
        }

        public static Result unavailable(String currentVersion, String detail) {
            return new Result(Status.UNAVAILABLE, currentVersion, null, null, null, detail);
        }

        public String health() {
            return switch (status) {
                case DISABLED -> "disabled";
                case CHECKING -> "checking";
                case UP_TO_DATE -> "up-to-date,source=" + source.name().toLowerCase(Locale.ROOT);
                case AVAILABLE -> "available=" + latestVersion + ",source="
                        + source.name().toLowerCase(Locale.ROOT);
                case UNAVAILABLE -> "unavailable,error=" + detail;
            };
        }
    }

    @FunctionalInterface
    interface RedirectProbe {
        URI redirect(URI endpoint, Duration requestTimeout) throws IOException, InterruptedException;
    }

    private record SourceAttempt(Result result, String failure) {
        static SourceAttempt success(Result result) {
            return new SourceAttempt(result, null);
        }

        static SourceAttempt failed(String failure) {
            return new SourceAttempt(null, failure);
        }
    }

    private record SemanticVersion(int major, int minor, int patch, String text)
            implements Comparable<SemanticVersion> {
        static Optional<SemanticVersion> parse(String source) {
            if (source == null) {
                return Optional.empty();
            }
            Matcher matcher = VERSION.matcher(source.trim());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            return Optional.of(new SemanticVersion(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                    matcher.group(1) + '.' + matcher.group(2) + '.' + matcher.group(3)));
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) {
                result = Integer.compare(minor, other.minor);
            }
            return result == 0 ? Integer.compare(patch, other.patch) : result;
        }
    }

    private static final class HttpRedirectProbe implements RedirectProbe {
        private final HttpClient client;
        private final String userAgent;

        private HttpRedirectProbe(Duration connectTimeout, String version) {
            client = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            userAgent = "TwiOpSec/" + version;
        }

        @Override
        public URI redirect(URI endpoint, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", "text/html")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body();
            try {
                if (!isRedirect(response.statusCode())) {
                    throw new IOException("Unexpected update endpoint status");
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Missing update endpoint redirect"));
                if (location.length() > MAX_REDIRECT_LENGTH) {
                    throw new IOException("Oversized update endpoint redirect");
                }
                return endpoint.resolve(location);
            } finally {
                body.close();
            }
        }

        private static boolean isRedirect(int status) {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }
    }
}
