package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
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
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Siege playtest 2026-10-24: the <b>Cleaving</b> custom enchantment (knk-core {@code EnchantmentRegistry},
 * levels I-IV) is the gate breaker's enchantment. Two effects, both only against gate doors:
 * <ul>
 *   <li>every melee hit on a door block does {@code 1 + gate damage bonus × level} times the weapon's gate
 *       damage ({@link GateMeleeDamage}): ×1.25 / ×1.5 / ×1.75 / ×2 with the default, so a netherite sword's
 *       8 becomes 10 / 12 / 14 / 16 per hit;</li>
 *   <li>a right-click on a door block with the strike ready is a <b>charged strike</b>: one hit of
 *       the strike multiplier at its level times that boosted hit (5 / 6 / 7 / 8 by default: 50 / 72 / 98 / 128
 *       of a default 500 HP gate), with wood splinters and breaking sounds, after which the strike cools down
 *       for its cooldown at that level (60 / 50 / 40 / 30 s).</li>
 * </ul>
 * PandiO/knk-workspace#259: the numbers are Cleaving's combat values ({@link EnchantmentTuning#gateStrike()}, the
 * strike's level-I cooldown is the enchantment's cooldown), read per hit, so an admin's change in the web app applies
 * after {@code /ce reload} or the periodic refresh; config.yml {@code gates.cleaving} is gone.
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
    private final Supplier<EnchantmentTuning> tuning;
    private Effects effects = new BukkitEffects();

    /** Reads Cleaving's combat values from {@link EnchantmentTuningTable} per hit (#259). */
    public GateCleaving(EnchantmentRepository enchantments, CooldownManager cooldowns, GateDoorHitService hitService) {
        this(enchantments, cooldowns, hitService, () -> EnchantmentTuningTable.getInstance().get(CLEAVING_ID));
    }

    /** @param tuning Cleaving's current combat values, read per hit */
    GateCleaving(EnchantmentRepository enchantments, CooldownManager cooldowns, GateDoorHitService hitService,
                 Supplier<EnchantmentTuning> tuning) {
        this.enchantments = enchantments;
        this.cooldowns = cooldowns;
        this.hitService = hitService;
        this.tuning = tuning;
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
        return tuning.get().gateStrike().damageMultiplierAt(level);
    }

    /** {@link #damageMultiplier(int)} for the player's held weapon. */
    public double damageMultiplierOf(Player player) {
        return damageMultiplier(levelOf(player));
    }

    /** The charged strike as a multiple of the boosted melee hit at {@code level}. */
    public double chargedHitMultiplier(int level) {
        return tuning.get().gateStrike().strikeMultiplierAt(level);
    }

    /** The cooldown of the charged strike at {@code level}, in milliseconds. */
    public long chargedCooldownMillis(int level) {
        return tuning.get().strikeCooldownMsAt(level);
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
