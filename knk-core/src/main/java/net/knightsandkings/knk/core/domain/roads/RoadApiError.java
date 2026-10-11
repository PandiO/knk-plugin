package net.knightsandkings.knk.core.domain.roads;

import java.util.Objects;

/**
 * The body the road controllers send on a refusal (Phase 1 status): {@code error} is
 * {@code ValidationFailed} (400), {@code NotFound} (404) or {@code Conflict} (409) and
 * {@code message} the human-readable reason. The api-client's {@code RoadMapper.error} reads it
 * from an {@code ApiException} so admin commands can show the message. Bukkit-free.
 */
public record RoadApiError(String error, String message) {
    public static final String VALIDATION_FAILED = "ValidationFailed";
    public static final String NOT_FOUND = "NotFound";
    public static final String CONFLICT = "Conflict";

    public RoadApiError {
        Objects.requireNonNull(error, "error");
        if (message == null) {
            message = "";
        }
    }

    public boolean isValidationFailed() {
        return VALIDATION_FAILED.equals(error);
    }

    public boolean isNotFound() {
        return NOT_FOUND.equals(error);
    }

    public boolean isConflict() {
        return CONFLICT.equals(error);
    }
}
