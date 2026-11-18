package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.paper.enchantment.EnchantmentPermissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

public class EnchantmentCommandValidator {
    private final EnchantmentPermissions permissions;

    public EnchantmentCommandValidator() {
        this(EnchantmentPermissions.bukkitOnly());
    }

    public EnchantmentCommandValidator(EnchantmentPermissions permissions) {
        this.permissions = permissions != null ? permissions : EnchantmentPermissions.bukkitOnly();
    }

    public Optional<Player> requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return Optional.of(player);
        }
        return Optional.empty();
    }

    public Optional<ItemStack> requireHeldItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        return Optional.of(item);
    }

    public Optional<Integer> parsePositiveLevel(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        try {
            int level = Integer.parseInt(input.trim());
            return level > 0 ? Optional.of(level) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public Optional<Enchantment> resolveEnchantment(String rawId) {
        String id = normalizeEnchantmentId(rawId);
        if (id.isBlank()) {
            return Optional.empty();
        }
        return EnchantmentRegistry.getInstance().getById(id);
    }

    public String normalizeEnchantmentId(String rawId) {
        if (rawId == null) {
            return "";
        }
        return rawId.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public boolean hasEnchantmentPermission(CommandSender sender, String enchantmentId) {
        return permissions.canUse(sender, normalizeEnchantmentId(enchantmentId));
    }
}
