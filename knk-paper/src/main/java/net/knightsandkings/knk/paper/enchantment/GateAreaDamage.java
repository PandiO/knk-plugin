package net.knightsandkings.knk.paper.enchantment;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * How an area custom enchantment (chaos, flash_chaos) reaches gate doors: it hurts every gate whose door
 * blocks are in range, with the damage the enchantment itself deals. Implemented by the gate package
 * ({@code GateAreaDamageService}); the enchantment package only knows this seam, and effects get it late
 * (the gates start after the enchantments do).
 */
@FunctionalInterface
public interface GateAreaDamage {

    GateAreaDamage NONE = (source, center, radius, damage) -> {
    };

    /**
     * Damage once each gate that has a door block within {@code radius} of {@code center}.
     *
     * @param source the player whose enchantment went off (credited, and checked against siege rules)
     * @param damage the damage the enchantment deals to an entity in range
     */
    void damageGatesAround(Player source, Location center, double radius, double damage);
}
