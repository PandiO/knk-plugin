package net.knightsandkings.knk.paper.enchantbook;

import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.item.GradeCatalog;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import net.knightsandkings.knk.core.enchantbook.EnchantBookCapSettings;
import net.knightsandkings.knk.core.enchantbook.EnchantBookPayload;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.knightsandkings.knk.core.enchantbook.EnchantBookText;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.mapper.ItemGradeTag;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Applying permanent enchantment books (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.2;
 * Linear KNG-5): the siege-agnostic counterpart of {@code SiegeEnchantBooks} on {@code claude/siege-minigame}.
 * Reads the facts off the items, lets {@link EnchantBookRules} decide, and writes a normal permanent
 * enchantment - vanilla via {@code addEnchant} (unsafe levels allowed, as {@code /knk itemblueprints give}),
 * custom via the {@link EnchantmentRepository} lore lines ({@link CustomEnchantmentLore}, the same pipeline as
 * {@code /ce add}). Never writes the siege PDC markers, so the siege
 * stripping sweep leaves the result alone.
 * <p>
 * KNG-6 (docs/specs/items/GRADE_DROPCHANCE.md): the target's grade ({@link ItemGradeTag}, looked up live in
 * {@link GradeCatalog}) caps the resulting level at {@code definitionMaxLevel / divisor}, and a successful
 * bonus roll gives one level more within that cap - v1's {@code EnchantbookClick}.
 */
public final class EnchantBooks {

    private final EnchantmentRepository enchantmentRepository;
    private final EnchantBookCapSettings capSettings;
    private final GradeCatalog grades;
    private final DoubleSupplier random;

    public EnchantBooks(EnchantmentRepository enchantmentRepository) {
        this(enchantmentRepository, EnchantBookCapSettings.DEFAULTS, GradeCatalog.getInstance(), () -> ThreadLocalRandom.current().nextDouble());
    }

    public EnchantBooks(EnchantmentRepository enchantmentRepository, EnchantBookCapSettings capSettings,
                        GradeCatalog grades, DoubleSupplier random) {
        this.enchantmentRepository = enchantmentRepository;
        this.capSettings = capSettings;
        this.grades = grades;
        this.random = random;
    }

    /**
     * What a book would do to an item, without the bonus roll.
     *
     * @param level  the level the item would end up with (meaningful when {@code APPLIED})
     * @param capped the grade cap lowered it below what the book teaches
     */
    public record Decision(ApplyResult result, EnchantBookPayload payload, EnchantBookRules.Target facts,
                           int level, int maxLevel, boolean capped) {
    }

    /**
     * The result of {@link #apply}: {@code updated} is the enchanted copy of the target when applied, at
     * {@code level}; {@code capped} when the grade cap lowered it, {@code bonus} when the bonus roll raised it.
     */
    public record Outcome(ApplyResult result, ItemStack updated, int level, boolean capped, boolean bonus) {
        public Outcome(ApplyResult result, ItemStack updated) {
            this(result, updated, 0, false, false);
        }
    }

    /** A valid book whose enchantment exists on this server, or null. */
    public EnchantBookPayload book(ItemStack item) {
        return EnchantBookItems.payload(item).filter(EnchantBooks::known).orElse(null);
    }

    /** Would {@code book} go on {@code target}? Changes nothing. */
    public ApplyResult evaluate(ItemStack book, ItemStack target) {
        return decide(book, target).result();
    }

    /** What {@code book} would do to {@code target} (bonus roll aside). Changes nothing. */
    public Decision decide(ItemStack book, ItemStack target) {
        EnchantBookPayload payload = book(book);
        if (payload == null) {
            return new Decision(ApplyResult.INVALID_BOOK, null, null, 0, 0, false);
        }
        int maxLevel = maxLevel(book, payload);
        EnchantBookRules.Target facts = target(payload, target, maxLevel);
        ApplyResult result = EnchantBookRules.evaluate(payload, facts);
        if (result != ApplyResult.APPLIED) {
            return new Decision(result, payload, facts, 0, maxLevel, false);
        }
        return new Decision(result, payload, facts, EnchantBookRules.appliedLevel(facts, payload.level()), maxLevel,
                EnchantBookRules.cappedBelowBook(facts, payload.level()));
    }

    /** Applies one {@code book} to a copy of {@code target}. The caller consumes the book and writes the item back. */
    public Outcome apply(ItemStack book, ItemStack target) {
        Decision decision = decide(book, target);
        if (decision.result() != ApplyResult.APPLIED) {
            return new Outcome(decision.result(), null);
        }

        EnchantBookPayload payload = decision.payload();
        int level = decision.level();
        boolean bonus = false;
        if (capSettings.bonus(random.getAsDouble())) {
            int raised = EnchantBookRules.withBonus(level, decision.facts().levelCap(), decision.maxLevel());
            bonus = raised > level;
            level = raised;
        }
        ItemStack updated = target.clone();
        ItemMeta meta = updated.getItemMeta();
        if (payload.kind() == EnchantBookPayload.Kind.VANILLA) {
            meta.addEnchant(vanilla(payload), level, true);
        } else {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of();
            // The shared pipeline: custom enchantment lines on top, under the vanilla list; Grade/Origin stay last.
            meta.setLore(CustomEnchantmentLore.apply(enchantmentRepository, lore, payload.enchantmentKey(), level));
        }
        updated.setItemMeta(meta);
        return new Outcome(ApplyResult.APPLIED, updated, level, decision.capped(), bonus);
    }

    /**
     * The grade the cap uses for {@code target}: its own (tag or lore), else the configured grade for ungraded
     * items; empty when that is "uncapped" (0) or unknown. For the confirmation text only.
     */
    public Optional<KnkGrade> capGrade(ItemStack target) {
        Integer stars = ItemGradeTag.stars(target).orElse(null);
        int effective = stars != null ? stars : capSettings.ungradedStars();
        return effective > 0 ? grades.byStars(effective) : Optional.empty();
    }

    /** True when {@code target} has no grade of its own (the cap then uses {@code ungraded-stars}). */
    public static boolean ungraded(ItemStack target) {
        return ItemGradeTag.stars(target).isEmpty();
    }

    /** "Sharpness" (no level) for the confirmation text. */
    public String describeEnchantment(ItemStack book) {
        String full = describeBook(book);
        int space = full.lastIndexOf(' ');
        return space > 0 ? full.substring(0, space) : full;
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
            case LEVEL_CAPPED -> "This item's grade doesn't allow that enchantment any higher.";
            case NO_IMPROVEMENT -> "The item already has that enchantment at this level or higher.";
        };
    }

    /** The action-bar text for an apply: names the level when the grade cap or the bonus changed it. */
    public static String describe(Outcome outcome) {
        if (outcome.result() != ApplyResult.APPLIED) {
            return describe(outcome.result());
        }
        String level = EnchantBookText.roman(outcome.level());
        if (outcome.bonus()) {
            return "Enchantment applied - lucky, a bonus level: " + level + "!";
        }
        if (outcome.capped()) {
            return "Enchantment applied at " + level + ", the most this item's grade allows.";
        }
        return describe(ApplyResult.APPLIED);
    }

    private EnchantBookRules.Target target(EnchantBookPayload payload, ItemStack target, int maxLevel) {
        boolean usable = target != null && !target.getType().isAir()
                && target.getType() != Material.BOOK && target.getType() != Material.ENCHANTED_BOOK;
        if (!usable) {
            return new EnchantBookRules.Target(false, false, false, 0);
        }
        Integer levelCap = capSettings.levelCap(grades, ItemGradeTag.stars(target).orElse(null), payload.kind(), maxLevel);
        if (payload.kind() == EnchantBookPayload.Kind.VANILLA) {
            Enchantment enchantment = vanilla(payload);
            boolean conflicts = false;
            for (Enchantment existing : target.getEnchantments().keySet()) {
                if (!existing.equals(enchantment) && enchantment.conflictsWith(existing)) {
                    conflicts = true;
                    break;
                }
            }
            return new EnchantBookRules.Target(true, enchantment.canEnchantItem(target), conflicts,
                    target.getEnchantmentLevel(enchantment), levelCap);
        }
        boolean compatible = EnchantBookRules.customCompatible(target.getType().getMaxDurability());
        return new EnchantBookRules.Target(true, compatible, false, customLevel(target, payload.enchantmentKey()), levelCap);
    }

    /**
     * The max level the grade cap divides: the enchantment definition's, stamped on the book when it was built
     * (v1 divided its own {@code Enchantments.MaxLevel}); for books from before KNG-6, the vanilla / registry max.
     * Custom enchantments never go past the registry max ({@code EnchantmentRepository} silently skips a level above it).
     */
    private static int maxLevel(ItemStack book, EnchantBookPayload payload) {
        Integer stamped = EnchantBookItems.definitionMaxLevel(book).orElse(null);
        if (payload.kind() == EnchantBookPayload.Kind.VANILLA) {
            return stamped != null ? stamped : vanilla(payload).getMaxLevel();
        }
        int registryMax = EnchantmentRegistry.getInstance().getById(payload.enchantmentKey())
                .map(net.knightsandkings.knk.core.domain.enchantment.Enchantment::maxLevel)
                .orElse(payload.level());
        return stamped != null ? Math.min(stamped, registryMax) : registryMax;
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
