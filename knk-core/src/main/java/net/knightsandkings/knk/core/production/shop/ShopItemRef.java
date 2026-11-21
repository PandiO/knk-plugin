package net.knightsandkings.knk.core.production.shop;

import java.util.Locale;

/**
 * Which item of a shop a menu is about, carried between the shop menus as one context value ({@code ctx.item}): a
 * catalogue entry {@code e<id>}, a unique item {@code i<id>} or Merchandise stock of a blueprint {@code b<id>}. The API
 * checks these in the same order (catalogue entry, instance, blueprint), so the reference is also what the quote and the
 * purchase send.
 */
public record ShopItemRef(Kind kind, long id) {

    public enum Kind {
        ENTRY('e'), INSTANCE('i'), BLUEPRINT('b');

        private final char prefix;

        Kind(char prefix) {
            this.prefix = prefix;
        }
    }

    public static ShopItemRef of(ShopOfferItem item) {
        if (item.catalogueEntryId() != null) {
            return new ShopItemRef(Kind.ENTRY, item.catalogueEntryId());
        }
        if (item.itemInstanceId() != null) {
            return new ShopItemRef(Kind.INSTANCE, item.itemInstanceId());
        }
        return new ShopItemRef(Kind.BLUEPRINT, item.itemBlueprintId());
    }

    /** Parses {@code e12}, {@code i7} or {@code b3}; null for anything else. */
    public static ShopItemRef parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.length() < 2) {
            return null;
        }
        Kind kind = switch (value.charAt(0)) {
            case 'e' -> Kind.ENTRY;
            case 'i' -> Kind.INSTANCE;
            case 'b' -> Kind.BLUEPRINT;
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        try {
            long id = Long.parseLong(value.substring(1));
            return id > 0 ? new ShopItemRef(kind, id) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean matches(ShopOfferItem item) {
        return item != null && equals(of(item));
    }

    public Integer catalogueEntryId() {
        return kind == Kind.ENTRY ? (int) id : null;
    }

    public Long itemInstanceId() {
        return kind == Kind.INSTANCE ? id : null;
    }

    public Integer itemBlueprintId() {
        return kind == Kind.BLUEPRINT ? (int) id : null;
    }

    @Override
    public String toString() {
        return kind.prefix + Long.toString(id);
    }
}
