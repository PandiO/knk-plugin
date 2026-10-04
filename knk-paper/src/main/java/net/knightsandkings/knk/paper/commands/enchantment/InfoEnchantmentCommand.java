package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InfoEnchantmentCommand implements EnchantmentSubcommand {
    private final EnchantmentCommandHandler handler;
    private final EnchantmentRepository repository;
    private final CooldownManager cooldownManager;
    private final EnchantmentCommandValidator validator;

    public InfoEnchantmentCommand(
            EnchantmentCommandHandler handler,
            EnchantmentRepository repository,
            CooldownManager cooldownManager,
            EnchantmentCommandValidator validator
    ) {
        this.handler = handler;
        this.repository = repository;
        this.cooldownManager = cooldownManager;
        this.validator = validator;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String permission() {
        return "customenchantments.command.info";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player targetPlayer;

        if (args.length == 0) {
            Optional<Player> senderPlayer = validator.requirePlayer(sender);
            if (senderPlayer.isEmpty()) {
                sender.sendMessage(handler.colorize(handler.message("messages.cmd-usage-info", "&eUsage: /ce info [player]")));
                return true;
            }
            targetPlayer = senderPlayer.get();
        } else {
            targetPlayer = Bukkit.getPlayerExact(args[0]);
            if (targetPlayer == null) {
                sender.sendMessage(handler.colorize(handler.message("messages.cmd-player-not-found", "&cCould not find player %player%.", Map.of("%player%", args[0]))));
                return true;
            }
        }

        Optional<ItemStack> heldItemOptional = validator.requireHeldItem(targetPlayer);
        if (heldItemOptional.isEmpty()) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-hand-target", "&cTarget player must hold an item in main hand.")));
            return true;
        }

        ItemStack heldItem = heldItemOptional.get();
        ItemMeta itemMeta = heldItem.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        Map<String, Integer> enchantments = repository.getEnchantments(lore).join();

        if (enchantments.isEmpty()) {
            sender.sendMessage(handler.colorize(handler.message("messages.cmd-info-none", "&7No custom enchantments found on held item.")));
            return true;
        }

        sender.sendMessage(handler.colorize(handler.message("messages.cmd-info-header", "&6Custom enchantments on held item:")));
        UUID playerId = targetPlayer.getUniqueId();
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();

        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            String enchantmentId = entry.getKey();
            int level = entry.getValue() == null ? 0 : entry.getValue();
            Optional<Enchantment> definition = registry.getById(enchantmentId);
            String displayName = definition.map(Enchantment::displayName).orElse(enchantmentId);
            long cooldownMs = cooldownManager.getRemainingCooldown(playerId, enchantmentId).join();
            long seconds = cooldownMs > 0 ? (long) Math.ceil(cooldownMs / 1000.0d) : 0L;

            String line = handler.message(
                    "messages.cmd-info-line",
                    "&7- &f%name% %level% &8(id: %id%) &7cooldown: %cooldown%s",
                    Map.of(
                            "%name%", displayName,
                            "%level%", Integer.toString(level),
                            "%id%", enchantmentId,
                            "%cooldown%", Long.toString(seconds)
                    )
            );
            sender.sendMessage(handler.colorize(line));
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender.hasPermission("customenchantments.command.info.others")) {
            return handler.visiblePlayerNames(sender, args[0]);
        }
        return List.of();
    }
}
