package net.knightsandkings.knk.core.domain.common;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a conditional GET (plan R17): either the server answered 304 Not Modified because
 * the caller's {@code etag} still matches, or it sent a fresh {@code body} with its current
 * {@code etag}.
 *
 * <p>The etag is the server's {@code ETag} header verbatim (a quoted version such as {@code "3"});
 * pass it back unchanged on the next call. Phase 3's tile cache stores it next to the cached body.
 *
 * @param notModified true when the server answered 304; {@code body} is then {@code null}
 * @param body        the parsed body on a 200, {@code null} on a 304
 * @param etag        the server's ETag (on a 304 the one the caller sent, if the server sent none);
 *                    {@code null} when the server sent no ETag at all
 */
public record Conditional<T>(boolean notModified, T body, String etag) {
    public Conditional {
        if (notModified && body != null) {
            throw new IllegalArgumentException("a 304 carries no body");
        }
        if (!notModified) {
            Objects.requireNonNull(body, "body");
        }
    }

    /** A 200 with a fresh body. */
    public static <T> Conditional<T> modified(T body, String etag) {
        return new Conditional<>(false, body, etag);
    }

    /** A 304: the caller's copy is still current. */
    public static <T> Conditional<T> notModified(String etag) {
        return new Conditional<>(true, null, etag);
    }

    /** The body when the server sent one. */
    public Optional<T> bodyOptional() {
        return Optional.ofNullable(body);
    }
}
