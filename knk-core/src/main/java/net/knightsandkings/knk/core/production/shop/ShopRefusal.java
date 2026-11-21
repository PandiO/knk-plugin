package net.knightsandkings.knk.core.production.shop;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The API's refusal codes for buying and selling (API contract shops § 4.4; 409 {@code { "code", "error" }}) and what the
 * shop menus tell the player for each. {@link #PRICE_CHANGED} is the one code the menus answer by asking again.
 */
public enum ShopRefusal {
    PRODUCTION_DISABLED("ProductionDisabled", "Selling to shops and buying into the reward inbox are switched off right now."),
    NOT_CONFIRMED("NotConfirmed", "The shop needs your confirmation first."),
    PRICE_CHANGED("PriceChanged", "The price changed. Check the new price and confirm again."),
    NOT_FOR_SALE("NotForSale", "This item is no longer for sale here."),
    SOLD_OUT("SoldOut", "This item is sold out."),
    NOT_ENOUGH_STOCK("NotEnoughStock", "The shop doesn't have that many in stock any more."),
    STORAGE_LOCKED("StorageLocked", "The shop's stock is busy right now. Try again in a moment."),
    INSUFFICIENT_FUNDS("InsufficientFunds", "You don't have enough coins."),
    INBOX_FULL("InboxFull", "Your reward inbox is full."),
    NO_TOWN("NoTown", "This shop belongs to no town, so it can't trade right now."),
    TOWN_FUNDS_INSUFFICIENT("TownFundsInsufficient", "The town can't pay that much right now."),
    CATEGORY_NOT_ACCEPTED("CategoryNotAccepted", "This shop doesn't buy items of that kind."),
    NO_PRICE("NoPrice", "This item has no price, so the shop can't buy it."),
    NOT_RESOLVED("NotResolved", "This item isn't in the item catalogue."),
    IN_STORAGE("InStorage", "That unique item is in a storage, not in your hands."),
    SOULBOUND("Soulbound", "Soulbound items can't be sold."),
    NO_ROOM("NoRoom", "The shop has no room for more of this."),
    IDEMPOTENCY_KEY_REUSE("IdempotencyKeyReuse", "That request was already used. Please try again."),
    /** A 409 without a known code, or another error. */
    OTHER(null, "The shop couldn't do that right now.");

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z]+)\"");

    private final String code;
    private final String message;

    ShopRefusal(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    /** What the player is told. */
    public String message() {
        return message;
    }

    /** The refusal for an API code (case-insensitive); {@link #OTHER} for null or an unknown code. */
    public static ShopRefusal of(String code) {
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        for (ShopRefusal refusal : values()) {
            if (refusal.code != null && refusal.code.toLowerCase(Locale.ROOT).equals(wanted)) {
                return refusal;
            }
        }
        return OTHER;
    }

    /** The refusal in a 409 body ({@code {"code":"SoldOut","error":"..."}}); {@link #OTHER} when there is none. */
    public static ShopRefusal fromBody(String body) {
        if (body == null) {
            return OTHER;
        }
        Matcher m = CODE.matcher(body);
        return m.find() ? of(m.group(1)) : OTHER;
    }

    /** The message for a quote or line refusal: the API's own message when it sent one, else ours. */
    public static String describe(String code, String apiMessage) {
        ShopRefusal refusal = of(code);
        if (refusal == OTHER && apiMessage != null && !apiMessage.isBlank()) {
            return apiMessage;
        }
        return refusal.message();
    }
}
