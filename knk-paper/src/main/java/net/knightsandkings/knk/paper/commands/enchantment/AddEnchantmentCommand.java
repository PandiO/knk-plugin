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

public class AddEnchantmentCommand implements EnchantmentSubcommand {
    private final EnchantmentCommandHandler handler;
    private final EnchantmentRepository repository;
    private final EnchantmentCommandValidator validator;

    public AddEnchantmentCommand(
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
        return "add";
    }

    @Override
    public String permission() {
        return "customenchantments.command.add";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-usage-add", "&eUsage: /ce add <enchantment> <level>")));
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

        if (!validator.hasEnchantmentPermission(sender, enchantmentId)) {
            sender.sendMessage(handler.colorize(handler.message("messages.ench-use", "&cYou cannot use %enchantment%.", Map.of("%enchantment%", enchantmentId))));
            return true;
        }

        Optional<Integer> parsedLevel = validator.parsePositiveLevel(args[1]);
        if (parsedLevel.isEmpty()) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-invalid-level", "&cLevel must be a positive number.")));
            return true;
        }

        Enchantment enchantment = enchantmentOptional.get();
        int level = parsedLevel.get();
        if (level > enchantment.maxLevel()) {
            sender.sendMessage(handler.colorize(handler.message(
                    "messages.cmd-add-level",
                    "&cCannot add %enchantment%, max level is %lvl%.",
                    Map.of("%enchantment%", enchantment.displayName(), "%lvl%", Integer.toString(enchantment.maxLevel()))
            )));
            return true;
        }

        ItemStack itemInHand = heldItemOptional.get();
        ItemMeta itemMeta = itemInHand.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        List<String> updatedLore = repository.applyEnchantment(lore, enchantment.id(), level).join();
        updatedLore = reorderLoreEnchantmentsFirst(updatedLore);

        if (itemMeta == null) {
            itemMeta = handler.plugin().getServer().getItemFactory().getItemMeta(itemInHand.getType());
        }
        if (itemMeta != null) {
            itemMeta.setLore(updatedLore);
            itemInHand.setItemMeta(itemMeta);
            player.getInventory().setItemInMainHand(itemInHand);
        }

        sender.sendMessage(handler.colorize(handler.message("messages.cmd-add-success", "&aEnchantment was added to item successfully.")));
        return true;
    }

    private List<String> reorderLoreEnchantmentsFirst(List<String> loreLines) {
        return CustomEnchantmentLore.enchantmentsFirst(repository, loreLines);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0];
            return handler.enchantmentIds().stream()
                    .filter(value -> value.startsWith(prefix.toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            String prefix = args[1] == null ? "" : args[1];
            return List.of("1", "2", "3").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
