package net.knightsandkings.knk.paper.regions;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Whether {@code victim} is protected from {@code attacker}'s custom enchantment effects and abilities
 * because they're in a combat safezone (KNG-11). Main thread only.
 */
@FunctionalInterface
public interface CombatSafezoneCheck {

    /** No safezones anywhere - used where the WorldGuard-backed check isn't wired (e.g. tests). */
    CombatSafezoneCheck NONE = (attacker, victim) -> false;

    boolean isProtected(Player attacker, LivingEntity victim);
}
