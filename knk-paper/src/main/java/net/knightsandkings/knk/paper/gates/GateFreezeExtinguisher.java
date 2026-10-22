package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The counter to the gate fire mechanic: a defender who hits a burning door block with a weapon that carries
 * the freeze custom enchantment puts that block's fire out (see {@link GateFireSystem#extinguishBlock}). Only
 * the hit block is put out and only for defenders - the players on the side that holds the gate in a running
 * siege - so an attacker can't douse their own fire. Outside a siege lockdown nobody holds a gate, so
 * nobody puts out its fire.
 */
public class GateFreezeExtinguisher {
    static final String FREEZE_ID = "freeze";
    private static final Logger LOGGER = Logger.getLogger(GateFreezeExtinguisher.class.getName());

    private final GateFireSystem fireSystem;
    private final EnchantmentRepository enchantments;
    private final BiPredicate<Player, CachedGateDoor> isDefender;

    /**
     * @param isDefender whether the player belongs to the side holding the gate (a siege lockdown's owner alliance)
     */
    public GateFreezeExtinguisher(GateFireSystem fireSystem, EnchantmentRepository enchantments,
                                  BiPredicate<Player, CachedGateDoor> isDefender) {
        this.fireSystem = fireSystem;
        this.enchantments = enchantments;
        this.isDefender = isDefender;
    }

    /**
     * A player hit {@code block} of {@code gate}.
     *
     * @return true when this put the block's fire out
     */
    public boolean onHit(Player player, CachedGateDoor gate, Block block) {
        if (player == null || gate == null || block == null || !gate.isOnFire() || player.getGameMode() != GameMode.SURVIVAL) {
            return false;
        }
        if (!isDefender.test(player, gate) || !holdsFreeze(player)) {
            return false;
        }
        return fireSystem.extinguishBlock(gate, block);
    }

    private boolean holdsFreeze(Player player) {
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon == null || weapon.isEmpty()) {
            return false;
        }
        ItemMeta meta = weapon.getItemMeta();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();
        if (lore == null || lore.isEmpty()) {
            return false;
        }
        try {
            Map<String, Integer> found = enchantments.getEnchantments(lore).join();
            Integer level = found.get(FREEZE_ID);
            return level != null && level >= 1;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Reading the enchantments of " + player.getName() + "'s weapon failed", e);
            return false;
        }
    }
}
