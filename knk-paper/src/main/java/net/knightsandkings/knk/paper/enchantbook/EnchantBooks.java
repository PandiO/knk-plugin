package net.knightsandkings.knk.paper.enchantbook;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.enchantbook.EnchantBookPayload;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.knightsandkings.knk.core.enchantbook.EnchantBookText;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/**
 * Applying permanent enchantment books (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.2;
 * Linear KNG-5): the siege-agnostic counterpart of {@code SiegeEnchantBooks} on {@code claude/siege-minigame}.
 * Reads the facts off the items, lets {@link EnchantBookRules} decide, and writes a normal permanent
 * enchantment - vanilla via {@code addEnchant} (unsafe levels allowed, as {@code /knk itemblueprints give}),
 * custom via the {@link EnchantmentRepository} lore lines. Never writes the siege PDC markers, so the siege
 * stripping sweep leaves the result alone.
 */
public final class EnchantBooks {

    private final EnchantmentRepository enchantmentRepository;

    public EnchantBooks(EnchantmentRepository enchantmentRepository) {
        this.enchantmentRepository = enchantmentRepository;
    }

    /** The result of {@link #apply}: {@code updated} is the enchanted copy of the target when applied. */
    public record Outcome(ApplyResult result, ItemStack updated) {
    }

    /** A valid book whose enchantment exists on this server, or null. */
    public EnchantBookPayload book(ItemStack item) {
        return EnchantBookItems.payload(item).filter(EnchantBooks::known).orElse(null);
    }

    /** Would {@code book} go on {@code target}? Changes nothing. */
    public ApplyResult evaluate(ItemStack book, ItemStack target) {
        EnchantBookPayload payload = book(book);
        return EnchantBookRules.evaluate(payload, payload == null ? null : target(payload, target));
    }

    /** Applies one {@code book} to a copy of {@code target}. The caller consumes the book and writes the item back. */
    public Outcome apply(ItemStack book, ItemStack target) {
        EnchantBookPayload payload = book(book);
        EnchantBookRules.Target facts = payload == null ? null : target(payload, target);
        ApplyResult result = EnchantBookRules.evaluate(payload, facts);
        if (result != ApplyResult.APPLIED) {
            return new Outcome(result, null);
        }

        int level = EnchantBookRules.appliedLevel(facts.existingLevel(), payload.level());
        ItemStack updated = target.clone();
        ItemMeta meta = updated.getItemMeta();
        if (payload.kind() == EnchantBookPayload.Kind.VANILLA) {
            meta.addEnchant(vanilla(payload), level, true);
        } else {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of();
            meta.setLore(enchantmentRepository.applyEnchantment(lore, payload.enchantmentKey(), level).join());
        }
        updated.setItemMeta(meta);
        return new Outcome(ApplyResult.APPLIED, updated);
    }

    /** "Sharpness III" for the chooser title and messages. */
    public String describeBook(ItemStack book) {
        EnchantBookPayload payload = book(book);
        if (payload == null) return "?";
        String name = payload.kind() == EnchantBookPayload.Kind.CUSTOM
                ? EnchantmentRegistry.getInstance().getById(payload.enchantmentKey())
                        .map(net.knightsandkings.knk.core.domain.enchantment.Enchantment::displayName)
                        .orElse(EnchantBookText.displayNameFromKey(payload.enchantmentKey()))
                : EnchantBookText.displayNameFromKey(payload.enchantmentKey());
        return name + " " + EnchantBookText.roman(payload.level());
    }

    public static String describe(ApplyResult result) {
        return switch (result) {
            case APPLIED -> "Enchantment applied.";
            case INVALID_BOOK -> "This enchantment book doesn't work on this server.";
            case NOT_ENCHANTABLE -> "That enchantment can't go on this item.";
            case CONFLICT -> "That enchantment conflicts with one the item already has.";
            case NO_IMPROVEMENT -> "The item already has that enchantment at this level or higher.";
        };
    }

    private EnchantBookRules.Target target(EnchantBookPayload payload, ItemStack target) {
        boolean usable = target != null && !target.getType().isAir()
                && target.getType() != Material.BOOK && target.getType() != Material.ENCHANTED_BOOK;
        if (!usable) {
            return new EnchantBookRules.Target(false, false, false, 0);
        }
        if (payload.kind() == EnchantBookPayload.Kind.VANILLA) {
            Enchantment enchantment = vanilla(payload);
            boolean conflicts = false;
            for (Enchantment existing : target.getEnchantments().keySet()) {
                if (!existing.equals(enchantment) && enchantment.conflictsWith(existing)) {
                    conflicts = true;
                    break;
                }
            }
            return new EnchantBookRules.Target(true, enchantment.canEnchantItem(target), conflicts, target.getEnchantmentLevel(enchantment));
        }
        boolean compatible = EnchantBookRules.customCompatible(target.getType().getMaxDurability());
        return new EnchantBookRules.Target(true, compatible, false, customLevel(target, payload.enchantmentKey()));
    }

    private int customLevel(ItemStack item, String enchantmentId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return 0;
        Map<String, Integer> levels = enchantmentRepository.getEnchantments(meta.getLore()).join();
        Integer level = levels.get(enchantmentId);
        return level != null ? level : 0;
    }

    private static boolean known(EnchantBookPayload payload) {
        return payload.kind() == EnchantBookPayload.Kind.VANILLA
                ? vanilla(payload) != null
                : EnchantmentRegistry.getInstance().existsAndValidLevel(payload.enchantmentKey(), payload.level());
    }

    private static Enchantment vanilla(EnchantBookPayload payload) {
        NamespacedKey key = NamespacedKey.fromString(payload.enchantmentKey());
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }
}
