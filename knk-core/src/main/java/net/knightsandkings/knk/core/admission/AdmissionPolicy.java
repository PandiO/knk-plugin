package net.knightsandkings.knk.core.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import net.knightsandkings.knk.core.domain.admission.AdmissionResult;

/**
 * Who may join (invitations DESIGN §5.2, plan §6.2): the plugin's admission gate at pre-login. Bukkit-free; the
 * paper module's {@code AdmissionListener} asks the API and applies the {@link Decision}.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@link Mode#WHITELIST} (the default) or {@link Mode#OPEN}: allow; the vanilla whitelist decides as before.</li>
 *   <li>{@link Mode#INVITE}:
 *     <ul>
 *       <li>an operator: allow, without asking the API (the same rule as the hub's admission);</li>
 *       <li>the API admits: allow;</li>
 *       <li>not admitted with a claim code: deny {@link #KICK} (the code on the disconnect screen);</li>
 *       <li>deactivated: deny {@link #DEACTIVATED};</li>
 *       <li>the API can't be reached (or answers something unusable): allow when the offline store holds a fresh
 *           identity for the UUID (an account exists, so it was admitted), else deny {@link #UNAVAILABLE}. Fail-closed
 *           for strangers.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class AdmissionPolicy {
    /** Message key: the claim code screen. Placeholders {@code code}, {@code minutes} (plus {@code url}, added by the caller). */
    public static final String KICK = "admission.kick";
    /** Message key: the API can't be reached and the player isn't known. */
    public static final String UNAVAILABLE = "admission.unavailable";
    /** Message key: the account is deactivated or deleted. */
    public static final String DEACTIVATED = "admission.deactivated";

    /** Used for {@code minutes} when the API sent a code without {@code expiresAt} (the API's default lifetime). */
    public static final int DEFAULT_CLAIM_CODE_MINUTES = 15;

    public enum Mode {
        /** Today's behaviour: the vanilla whitelist (when on) is the gate. The default. */
        WHITELIST,
        /** No gate at all. */
        OPEN,
        /** The plugin gate: only admitted accounts and operators get in. */
        INVITE;

        /** {@code admission.mode}; null, blank or unknown → {@code null} (the caller falls back to WHITELIST and warns). */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** What the gate does with one connection. */
    public sealed interface Decision permits Allow, Deny {
    }

    public record Allow() implements Decision {
    }

    /** Refuse the connection with the message under {@code messageKey}, its placeholders filled from {@code placeholders}. */
    public record Deny(String messageKey, Map<String, String> placeholders) implements Decision {
        public Deny {
            placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
        }

        /** Never prints the claim code. */
        @Override
        public String toString() {
            return "Deny[" + messageKey + "]";
        }
    }

    private static final Allow ALLOW = new Allow();

    private AdmissionPolicy() {
    }

    /** Whether the gate has to ask the API at all: only in invite mode, and never for an operator. */
    public static boolean needsCheck(Mode mode, boolean operator) {
        return mode == Mode.INVITE && !operator;
    }

    /**
     * @param mode          {@code admission.mode}
     * @param operator      the connecting UUID is a server operator
     * @param result        the API's answer, or null when the API couldn't be asked (down, timeout, error)
     * @param knownOffline  the offline security store holds an identity for the UUID within its max age
     * @param now           for the code's remaining minutes
     */
    public static Decision decide(Mode mode, boolean operator, AdmissionResult result, boolean knownOffline, Instant now) {
        if (!needsCheck(mode, operator)) {
            return ALLOW;
        }
        if (result == null) {
            return knownOffline ? ALLOW : new Deny(UNAVAILABLE, Map.of());
        }
        if (result.admitted()) {
            return ALLOW;
        }
        if (AdmissionResult.REASON_DEACTIVATED.equals(result.reason())) {
            return new Deny(DEACTIVATED, Map.of());
        }
        if (result.hasCode()) {
            return new Deny(KICK, Map.of(
                "code", result.code(),
                "minutes", String.valueOf(minutesLeft(result.expiresAt(), now))));
        }
        // Not admitted, but no code and no known reason: an answer the gate can't act on. Treat it as the API being
        // unavailable, so a known player still gets in and a stranger is told to retry.
        return knownOffline ? ALLOW : new Deny(UNAVAILABLE, Map.of());
    }

    /** Whole minutes until {@code expiresAt}, rounded up, at least 1; {@link #DEFAULT_CLAIM_CODE_MINUTES} without a time. */
    static int minutesLeft(Instant expiresAt, Instant now) {
        if (expiresAt == null || now == null) {
            return DEFAULT_CLAIM_CODE_MINUTES;
        }
        long seconds = Duration.between(now, expiresAt).getSeconds();
        if (seconds <= 60) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, (seconds + 59) / 60);
    }
}
