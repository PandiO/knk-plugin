package net.knightsandkings.knk.core.domain.currency;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The API's 409 {@code AlreadyReversed} answer to {@code /knk currency reverse} (currency smoke
 * test, 2026-09-27): the transaction was reversed before, by whom, when and by which reversal.
 * Read from the error's details {@code reversalTransactionPublicId}, {@code reversedAt},
 * {@code reversedByUserId}, {@code reversedByUsername}; any of them may be missing.
 */
public record AlreadyReversed(String reversalPublicId, Instant reversedAt, Integer reversedByUserId, String reversedByUsername) {

    /** The details of an {@code AlreadyReversed} error, or null for any other error or when the reversal id is missing. */
    public static AlreadyReversed from(CurrencyError error) {
        if (error == null || !error.is(CurrencyError.ALREADY_REVERSED)) {
            return null;
        }
        String reversal = error.detailString("reversalTransactionPublicId");
        if (reversal == null || reversal.isBlank()) {
            return null;
        }
        Long byId = error.detailLong("reversedByUserId");
        String byName = error.detailString("reversedByUsername");
        return new AlreadyReversed(reversal, instant(error.detailString("reversedAt")),
            byId != null ? Math.toIntExact(byId) : null, byName != null && !byName.isBlank() ? byName : null);
    }

    /** Who reversed it: their name, else "#id", else "the game server" (no staff account, e.g. the console). */
    public String reversedBy() {
        if (reversedByUsername != null) {
            return reversedByUsername;
        }
        return reversedByUserId != null && reversedByUserId > 0 ? "#" + reversedByUserId : "the game server";
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC); // the API writes UTC without an offset
            } catch (DateTimeParseException alsoIgnored) {
                return null;
            }
        }
    }
}
