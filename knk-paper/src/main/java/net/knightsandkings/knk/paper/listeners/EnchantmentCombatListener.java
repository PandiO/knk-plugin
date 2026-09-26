package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Runs the attacker's weapon enchantments (poison, wither, freeze, blindness, confusion, strength) on a
 * melee hit.
 * <p>
 * {@code MONITOR} with {@code ignoreCancelled}: effects apply only to hits that actually land, after
 * every other plugin has decided - WorldGuard's region flags, admin freeze, join loading and the siege
 * rules (which cancel ally hits and un-cancel allowed enemy hits at {@code HIGHEST}). At {@code LOWEST}
 * (before KNG-11) a hit that a later listener cancelled still poisoned the victim. This handler only
 * reads the event.
 * <p>
 * No effects on a player in a Town/District safezone ({@link CombatSafezoneCheck}, KNG-11) - one gate
 * for all attack enchantments. A cancelled or safezone hit doesn't start any enchantment cooldown.
 */
public class EnchantmentCombatListener implements Listener {
    private final EnchantmentRepository enchantmentRepository;
    private final EnchantmentExecutor enchantmentExecutor;
    private final boolean disableForCreative;
    private final CombatSafezoneCheck safezones;

    public EnchantmentCombatListener(
            EnchantmentRepository enchantmentRepository,
            EnchantmentExecutor enchantmentExecutor,
            boolean disableForCreative
    ) {
        this(enchantmentRepository, enchantmentExecutor, disableForCreative, CombatSafezoneCheck.NONE);
    }

    public EnchantmentCombatListener(
            EnchantmentRepository enchantmentRepository,
            EnchantmentExecutor enchantmentExecutor,
            boolean disableForCreative,
            CombatSafezoneCheck safezones
    ) {
        this.enchantmentRepository = enchantmentRepository;
        this.enchantmentExecutor = enchantmentExecutor;
        this.disableForCreative = disableForCreative;
        this.safezones = safezones != null ? safezones : CombatSafezoneCheck.NONE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }

        if (disableForCreative && attacker.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || weapon.isEmpty()) { // air or amount 0
            return;
        }

        if (safezones.isProtected(attacker, target)) {
            return;
        }

        ItemMeta itemMeta = weapon.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();

        enchantmentRepository.getEnchantments(lore)
                .thenCompose(enchantments -> enchantmentExecutor.executeOnMeleeHit(
                        enchantments,
                        attacker.getUniqueId(),
                        target.getUniqueId(),
                        event.getDamage()
                ));
    }
}
