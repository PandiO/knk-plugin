package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.core.domain.enchantment.GateStrike;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
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
    static final String OTHERS_PERMISSION = "customenchantments.command.info.others";

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
            // PandiO/knk-workspace#193: the node was declared but never checked, so anyone could look.
            if (!handler.permissions().commands().has(sender, OTHERS_PERMISSION)) {
                sender.sendMessage(handler.colorize(handler.message("messages.cmd-no-permission", "&cYou don't have permission to use this command.")));
                return true;
            }
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
            sender.sendMessage(handler.colorize(handler.message(
                    "messages.cmd-info-tuning",
                    "&8   chance %chance%%, duration %duration%s, cooldown %cooldownSetting%",
                    tuningPlaceholders(EnchantmentTuningTable.getInstance().get(enchantmentId), level))));
            EnchantmentTuning tuning = EnchantmentTuningTable.getInstance().get(enchantmentId);
            if (tuning.effectLevel() > 0 || tuning.secondaryDurationTicks() > 0) {
                // #201: Flash Chaos's Slowness level and Nausea duration.
                sender.sendMessage(handler.colorize(handler.message(
                        "messages.cmd-info-tuning-effects",
                        "&8   effect level %effectLevel%, second effect %secondDuration%s",
                        effectPlaceholders(tuning, level))));
            }
            if (tuning.area().reach() > 0.0d) {
                // #223: Chaos / Flash Chaos area damage, gate damage and radii.
                sender.sendMessage(handler.colorize(handler.message(
                        "messages.cmd-info-tuning-area",
                        AREA_LINE_DEFAULT,
                        areaPlaceholders(tuning, level))));
            }
            if (!tuning.gateStrike().equals(GateStrike.NONE)) {
                // #259: Cleaving's gate bonus, charged strike and strike cooldown.
                sender.sendMessage(handler.colorize(handler.message(
                        "messages.cmd-info-tuning-gate",
                        GATE_LINE_DEFAULT,
                        gatePlaceholders(tuning, level))));
            }
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && handler.permissions().commands().hasForCompletion(sender, OTHERS_PERMISSION)) {
            return handler.visiblePlayerNames(sender, args[0]);
        }
        return List.of();
    }

    /** PandiO/knk-workspace#151: the combat values in effect at this level (admin-set or built-in). */
    static Map<String, String> tuningPlaceholders(EnchantmentTuning tuning, int level) {
        String chance = formatNumber(tuning.chanceAt(level) * 100.0d);
        String duration = formatNumber(tuning.durationTicksAt(level) / 20.0d);
        String cooldown = tuning.cooldownEnabled() ? formatNumber(tuning.cooldownMs() / 1000.0d) + "s" : "off";
        return Map.of("%chance%", chance, "%duration%", duration, "%cooldownSetting%", cooldown);
    }

    /** PandiO/knk-workspace#201: the effect level and the second effect's duration at this level. */
    static Map<String, String> effectPlaceholders(EnchantmentTuning tuning, int level) {
        return Map.of(
                "%effectLevel%", Integer.toString(tuning.effectLevel()),
                "%secondDuration%", formatNumber(tuning.secondaryDurationTicksAt(level) / 20.0d));
    }

    static final String AREA_LINE_DEFAULT =
            "&8   area damage %areaDamage% within %damageRadius% blocks, gates %gateDamage%, knockback within %effectRadius% blocks";

    /** PandiO/knk-workspace#223: a blast's damage and gate damage at this level, and its radii. */
    static Map<String, String> areaPlaceholders(EnchantmentTuning tuning, int level) {
        return Map.of(
                "%areaDamage%", formatNumber(tuning.areaDamageAt(level)),
                "%gateDamage%", formatNumber(tuning.gateDamageAt(level)),
                "%damageRadius%", formatNumber(tuning.area().damageRadius()),
                "%effectRadius%", formatNumber(tuning.area().reach()));
    }

    static final String GATE_LINE_DEFAULT =
            "&8   gate hits +%gateBonus%%, charged strike %strike%x, strike cooldown %strikeCooldown%";

    /** PandiO/knk-workspace#259: Cleaving's gate bonus, strike multiplier and strike cooldown at this level. */
    static Map<String, String> gatePlaceholders(EnchantmentTuning tuning, int level) {
        long cooldownMs = tuning.strikeCooldownMsAt(level);
        return Map.of(
                "%gateBonus%", formatNumber((tuning.gateStrike().damageMultiplierAt(level) - 1.0d) * 100.0d),
                "%strike%", formatNumber(tuning.gateStrike().strikeMultiplierAt(level)),
                "%strikeCooldown%", cooldownMs > 0 ? formatNumber(cooldownMs / 1000.0d) + "s" : "off");
    }

    private static String formatNumber(double value) {
        double rounded = Math.round(value * 100.0d) / 100.0d;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
    }
}
