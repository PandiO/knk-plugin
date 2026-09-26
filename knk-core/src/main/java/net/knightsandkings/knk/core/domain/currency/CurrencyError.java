package net.knightsandkings.knk.core.domain.currency;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Why the API refused a currency request: its {@code code} (knk-web-api CurrencyErrorCode, e.g.
 * {@code DailyCapExceeded}), message and structured details (e.g. {@code remaining},
 * {@code seconds}, {@code balance}). {@link #UNKNOWN_OUTCOME} is the plugin's own code for a write
 * whose outcome it couldn't learn (the API never answered after every retry).
 */
public record CurrencyError(String code, String message, Map<String, Object> details) {

    public static final String UNKNOWN_OUTCOME = "UnknownOutcome";
    public static final String INSUFFICIENT_FUNDS = "InsufficientFunds";
    public static final String BALANCE_CAP_EXCEEDED = "BalanceCapExceeded";
    public static final String AMOUNT_OUT_OF_RANGE = "AmountOutOfRange";
    public static final String NOT_TRANSFERABLE = "NotTransferable";
    public static final String SELF_TRANSFER = "SelfTransfer";
    public static final String RECIPIENT_NOT_FOUND = "RecipientNotFound";
    public static final String ACCOUNT_LOCKED = "AccountLocked";
    public static final String COOLDOWN_ACTIVE = "CooldownActive";
    public static final String DAILY_CAP_EXCEEDED = "DailyCapExceeded";
    public static final String RECIPIENT_DAILY_CAP_EXCEEDED = "RecipientDailyCapExceeded";
    public static final String NEW_ACCOUNT_RESTRICTED = "NewAccountRestricted";
    public static final String TRANSFERS_DISABLED = "TransfersDisabled";
    public static final String IDEMPOTENCY_KEY_REUSE = "IdempotencyKeyReuse";
    public static final String PENDING_TRANSFER_NOT_FOUND = "PendingTransferNotFound";
    public static final String PENDING_TRANSFER_EXPIRED = "PendingTransferExpired";
    public static final String PENDING_TRANSFER_CLOSED = "PendingTransferClosed";
    public static final String USER_NOT_FOUND = "UserNotFound";

    public CurrencyError {
        // Not Map.copyOf: JSON details may hold nulls (e.g. "eligibleFrom": null).
        details = details == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(details));
    }

    public boolean is(String expected) {
        return expected.equalsIgnoreCase(code);
    }

    /** A numeric detail, or null. */
    public Long detailLong(String key) {
        Object value = details.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** A text detail, or null. */
    public String detailString(String key) {
        Object value = details.get(key);
        return value == null ? null : value.toString();
    }

    /** The server's message without its "Code: " prefix. */
    public String plainMessage() {
        if (message == null) {
            return "";
        }
        String prefix = code + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
