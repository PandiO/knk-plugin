package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RemoveEnchantmentCommand implements EnchantmentSubcommand {
    private final EnchantmentCommandHandler handler;
    private final EnchantmentRepository repository;
    private final EnchantmentCommandValidator validator;

    public RemoveEnchantmentCommand(
            EnchantmentCommandHandler handler,
            EnchantmentRepository repository,
            EnchantmentCommandValidator validator
    ) {
        this.handler = handler;
        this.repository = repository;
        this.validator = validator;
    }

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public String permission() {
        return "customenchantments.command.remove";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-usage-remove", "&eUsage: /ce remove <enchantment>")));
            return true;
        }

        Optional<Player> playerOptional = validator.requirePlayer(sender);
        if (playerOptional.isEmpty()) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-player-only", "&cThis command can only be executed by players.")));
            return true;
        }

        Player player = playerOptional.get();
        Optional<ItemStack> heldItemOptional = validator.requireHeldItem(player);
        if (heldItemOptional.isEmpty()) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-hand", "&cPlease put an item in your hand and run the command.")));
            return true;
        }

        String enchantmentId = validator.normalizeEnchantmentId(args[0]);
        Optional<Enchantment> enchantmentOptional = validator.resolveEnchantment(enchantmentId);
        if (enchantmentOptional.isEmpty()) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-unknown-enchantment", "&cUnknown enchantment: %enchantment%", Map.of("%enchantment%", enchantmentId))));
            return true;
        }

        ItemStack itemInHand = heldItemOptional.get();
        ItemMeta itemMeta = itemInHand.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        // The shared pipeline: also drops the blank spacer that sat under the enchantment block.
        List<String> updatedLore = CustomEnchantmentLore.remove(repository, lore, enchantmentId);

        if (itemMeta == null) {
            itemMeta = handler.plugin().getServer().getItemFactory().getItemMeta(itemInHand.getType());
        }
        if (itemMeta != null) {
            itemMeta.setLore(updatedLore);
            itemInHand.setItemMeta(itemMeta);
            player.getInventory().setItemInMainHand(itemInHand);
        }

        sender.sendMessage(handler.colorize(handler.message("messages.cmd-remove-success", "&aEnchantment was removed from item successfully.")));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0];
            return handler.enchantmentIds().stream()
                    .filter(value -> value.startsWith(prefix.toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
