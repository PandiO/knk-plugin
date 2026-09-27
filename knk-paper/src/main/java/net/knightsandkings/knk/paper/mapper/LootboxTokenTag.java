package net.knightsandkings.knk.paper.mapper;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A lootbox token item's identity (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md Phase 5): the PDC tag
 * {@code knightsandkings:knk_lootbox_token} holding the token id knk-web-api issued (a UUID as a STRING). The item is a
 * token only because of this tag: its name, lore and material are display, so renaming any item in an anvil (v1's
 * "Rare Sword Box" exploit) makes nothing, and a forged tag names a token the API never issued.
 */
public final class LootboxTokenTag {

    /** Same namespace as {@link ItemGradeTag#GRADE_KEY}. */
    public static final NamespacedKey TOKEN_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_lootbox_token"));

    private LootboxTokenTag() {
    }

    public static void stamp(ItemMeta meta, UUID token) {
        if (meta == null || token == null) {
            return;
        }
        meta.getPersistentDataContainer().set(TOKEN_KEY, PersistentDataType.STRING, token.toString());
    }

    /** The item's token, or empty for any item without a (well-formed) tag. */
    public static Optional<UUID> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return read(item.getItemMeta());
    }

    public static Optional<UUID> read(ItemMeta meta) {
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String raw = container.get(TOKEN_KEY, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isToken(ItemStack item) {
        return read(item).isPresent();
    }

    /** Whether any of {@code items} carries {@code token} (the delivery dedupe scan). */
    public static boolean contains(ItemStack[] items, UUID token) {
        if (items == null || token == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (read(item).filter(token::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
