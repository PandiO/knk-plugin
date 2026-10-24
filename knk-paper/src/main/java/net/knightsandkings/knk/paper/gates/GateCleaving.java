package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Siege playtest 2026-10-24: the <b>Cleaving</b> custom enchantment (knk-core {@code EnchantmentRegistry},
 * levels I-IV) is the gate breaker's enchantment. Two effects, both only against gate doors:
 * <ul>
 *   <li>every melee hit on a door block does {@code 1 + damageBonusPerLevel × level} times the weapon's gate
 *       damage ({@link GateMeleeDamage}): ×1.25 / ×1.5 / ×1.75 / ×2 with the default, so a netherite sword's
 *       8 becomes 10 / 12 / 14 / 16 per hit;</li>
 *   <li>a right-click on a door block with the strike ready is a <b>charged strike</b>: one hit of
 *       {@code chargedHitMultiplier[level]} times that boosted hit (5 / 6 / 7 / 8 by default: 50 / 72 / 98 / 128
 *       of a default 500 HP gate), with wood splinters and breaking sounds, after which the strike cools down
 *       for {@code chargedCooldownSeconds[level]} (60 / 50 / 40 / 30 s).</li>
 * </ul>
 * The strike goes through {@link GateDoorHitService#handleDamage} like every other hit, so only a closed,
 * active, standing gate is hurt, the siege listener can veto it (a defender can't cleave their own gate) and
 * the statistics see the loss; the cooldown starts only when the hit counted. The cooldown is per player
 * (the shared {@link CooldownManager}, id {@value #CLEAVING_ID}), not per weapon.
 */
public class GateCleaving {
    public static final String CLEAVING_ID = "cleaving";
    private static final Logger LOGGER = Logger.getLogger(GateCleaving.class.getName());

    /** The outcome of a right-click with a cleaving weapon. */
    public enum Strike {
        /** The player holds no cleaving weapon (or isn't in survival): nothing happened, handle the click as usual. */
        NOT_APPLICABLE,
        /** The strike is cooling down; the player was told how long is left. */
        ON_COOLDOWN,
        /** The hit was fired but didn't count (vetoed by a listener or the gate doesn't qualify). */
        VETOED,
        /** The gate took the charged strike. */
        STRUCK
    }

    /** What the players see of a strike; replaceable for tests. */
    interface Effects {
        void strike(Player player, Block block, int level);

        void cooling(Player player, long remainingMillis);
    }

    private final EnchantmentRepository enchantments;
    private final CooldownManager cooldowns;
    private final GateDoorHitService hitService;
    private final double damageBonusPerLevel;
    private final List<Double> chargedHitMultipliers;
    private final List<Integer> chargedCooldownSeconds;
    private Effects effects = new BukkitEffects();

    /**
     * @param damageBonusPerLevel    extra share of the weapon's gate damage per level (0.25 = ×1.25 at level I)
     * @param chargedHitMultipliers  the charged strike as a multiple of the boosted melee hit, per level (index 0 = I);
     *                               a missing level uses the last entry
     * @param chargedCooldownSeconds seconds the strike cools down, per level (index 0 = I); a missing level uses the last entry
     */
    public GateCleaving(EnchantmentRepository enchantments, CooldownManager cooldowns, GateDoorHitService hitService,
                        double damageBonusPerLevel, List<Double> chargedHitMultipliers, List<Integer> chargedCooldownSeconds) {
        this.enchantments = enchantments;
        this.cooldowns = cooldowns;
        this.hitService = hitService;
        this.damageBonusPerLevel = Math.max(0.0, damageBonusPerLevel);
        this.chargedHitMultipliers = chargedHitMultipliers == null || chargedHitMultipliers.isEmpty()
            ? List.of(5.0, 6.0, 7.0, 8.0) : List.copyOf(chargedHitMultipliers);
        this.chargedCooldownSeconds = chargedCooldownSeconds == null || chargedCooldownSeconds.isEmpty()
            ? List.of(60, 50, 40, 30) : List.copyOf(chargedCooldownSeconds);
    }

    void setEffects(Effects effects) {
        this.effects = effects;
    }

    /** The Cleaving level of the player's held weapon, 0 for none. */
    public int levelOf(Player player) {
        if (player == null) {
            return 0;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon == null || weapon.isEmpty()) {
            return 0;
        }
        ItemMeta meta = weapon.getItemMeta();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();
        if (lore == null || lore.isEmpty()) {
            return 0;
        }
        try {
            Map<String, Integer> found = enchantments.getEnchantments(lore).join();
            Integer level = found.get(CLEAVING_ID);
            return level == null ? 0 : Math.max(0, level);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Reading the enchantments of " + player.getName() + "'s weapon failed", e);
            return 0;
        }
    }

    /** The factor a melee hit on a gate is multiplied by at {@code level} (1 for none). */
    public double damageMultiplier(int level) {
        return level <= 0 ? 1.0 : 1.0 + damageBonusPerLevel * level;
    }

    /** {@link #damageMultiplier(int)} for the player's held weapon. */
    public double damageMultiplierOf(Player player) {
        return damageMultiplier(levelOf(player));
    }

    /** The charged strike as a multiple of the boosted melee hit at {@code level}. */
    public double chargedHitMultiplier(int level) {
        return perLevel(chargedHitMultipliers, level);
    }

    /** The cooldown of the charged strike at {@code level}, in milliseconds. */
    public long chargedCooldownMillis(int level) {
        return Math.max(0L, perLevel(chargedCooldownSeconds, level)) * 1000L;
    }

    /** The damage of a charged strike for a weapon whose regular (un-boosted) gate hit is {@code meleeDamage}. */
    public double chargedDamage(double meleeDamage, int level) {
        return Math.max(0.0, meleeDamage) * damageMultiplier(level) * chargedHitMultiplier(level);
    }

    /**
     * A right-click on {@code block} of {@code gate}.
     *
     * @param meleeDamage the weapon's regular gate hit ({@link GateMeleeDamage#damageOf}); null uses the flat default of 10
     */
    public Strike tryStrike(Player player, CachedGateDoor gate, Block block, Double meleeDamage) {
        if (player == null || gate == null || block == null || player.getGameMode() != GameMode.SURVIVAL) {
            return Strike.NOT_APPLICABLE;
        }
        int level = levelOf(player);
        if (level <= 0) {
            return Strike.NOT_APPLICABLE;
        }
        UUID id = player.getUniqueId();
        long remaining = cooldowns.getRemainingCooldown(id, CLEAVING_ID).join();
        if (remaining > 0L) {
            effects.cooling(player, remaining);
            return Strike.ON_COOLDOWN;
        }
        double damage = chargedDamage(meleeDamage == null ? 10.0 : meleeDamage, level);
        var event = hitService.handleDamage(gate, player, block, net.knightsandkings.knk.paper.events.GateDoorDamageEvent.Cause.ENCHANTMENT, damage);
        if (event == null || event.isCancelled()) {
            return Strike.VETOED;
        }
        cooldowns.applyCooldown(id, CLEAVING_ID, chargedCooldownMillis(level)).join();
        effects.strike(player, block, level);
        return Strike.STRUCK;
    }

    private static <T extends Number> T perLevel(List<T> values, int level) {
        int index = Math.max(0, Math.min(values.size() - 1, level - 1));
        return values.get(index);
    }

    /** Wood splinters off the struck block and the sounds of a plank giving way. */
    static final class BukkitEffects implements Effects {
        @Override
        public void strike(Player player, Block block, int level) {
            World world = block.getWorld();
            if (world == null) {
                return;
            }
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            int splinters = 20 + 10 * level;
            try {
                world.spawnParticle(Particle.BLOCK, at, splinters, 0.45, 0.45, 0.45, block.getBlockData());
            } catch (RuntimeException e) {
                // A block without displayable data (shouldn't happen for a door block): the sweep below still shows.
            }
            world.spawnParticle(Particle.SWEEP_ATTACK, at, 1);
            world.spawnParticle(Particle.CRIT, at, 8 + 4 * level, 0.4, 0.4, 0.4, 0.2);
            world.playSound(at, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.0f, 0.7f);
            world.playSound(at, Sound.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 1.0f, 0.8f);
            world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 0.6f);
            player.sendActionBar(Component.text("Cleaving strike!", NamedTextColor.GOLD));
        }

        @Override
        public void cooling(Player player, long remainingMillis) {
            long seconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
            player.sendActionBar(Component.text("Cleaving strike ready in " + seconds + " s", NamedTextColor.GRAY));
        }
    }
}
