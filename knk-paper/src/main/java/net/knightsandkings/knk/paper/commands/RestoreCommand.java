package net.knightsandkings.knk.paper.commands;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;

/**
 * {@code /heal [player|all]} and {@code /feed [player|all]} - ports of v2's {@code Heal}/{@code Feed}
 * (docs/specs/legacy/commands-v2.md §7, KNG-9). Same shape for both, so one class:
 * <ul>
 *   <li>no argument: yourself ({@code knk.<kind>})</li>
 *   <li>{@code <player>}: an online player ({@code knk.<kind>.others})</li>
 *   <li>{@code all}: every online player ({@code knk.<kind>.all}); the keyword wins over a player
 *       literally named "all", as in v2</li>
 * </ul>
 * v2's {@code Feed} checked {@code /heal}'s nodes (a copy-paste bug); {@code /feed} has its own
 * {@code knk.feed.*} nodes here, so granting {@code /heal} no longer grants {@code /feed}.
 */
public class RestoreCommand implements TabExecutor {

    public enum Kind {
        HEAL("heal", "healed", "Healed") {
            @Override
            void restore(Player player) {
                AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
                player.setFireTicks(0);
            }
        },
        FEED("feed", "fed", "Fed") {
            @Override
            void restore(Player player) {
                player.setFoodLevel(MAX_FOOD);
                player.setSaturation(MAX_FOOD);
                player.setExhaustion(0f);
            }
        };

        private static final int MAX_FOOD = 20;

        private final String label;
        private final String pastTense;
        private final String pastTenseCapitalized;

        Kind(String label, String pastTense, String pastTenseCapitalized) {
            this.label = label;
            this.pastTense = pastTense;
            this.pastTenseCapitalized = pastTenseCapitalized;
        }

        abstract void restore(Player player);

        public String node() {
            return "knk." + label;
        }

        public String othersNode() {
            return node() + ".others";
        }

        public String allNode() {
            return node() + ".all";
        }
    }

    private static final String ALL = "all";

    private final PlayerCommandSupport support;
    private final Kind kind;

    public RestoreCommand(PlayerCommandSupport support, Kind kind) {
        this.support = support;
        this.kind = kind;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + kind.label + " [player|all]");
            return true;
        }

        if (args.length == 0) {
            Player self = support.requirePlayer(sender);
            if (self == null) {
                sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /" + kind.label + " <player|all>");
                return true;
            }
            support.whenAllowed(sender, kind.node(), () -> restoreOne(sender, self, true));
            return true;
        }

        if (ALL.equalsIgnoreCase(args[0])) {
            support.whenAllowed(sender, kind.allNode(), () -> restoreAll(sender));
            return true;
        }

        Player target = support.requireOnlinePlayer(sender, args[0], null);
        if (target == null) {
            return true;
        }
        boolean self = PlayerCommandSupport.isSelf(sender, target);
        support.whenAllowed(sender, self ? kind.node() : kind.othersNode(), () -> restoreOne(sender, target, self));
        return true;
    }

    private void restoreOne(CommandSender sender, Player target, boolean self) {
        if (!target.isOnline() || target.isDead()) {
            sender.sendMessage(ChatColor.RED + target.getName() + " can't be " + kind.pastTense + " right now.");
            return;
        }
        kind.restore(target);
        if (self) {
            target.sendMessage(ChatColor.GREEN + "You have been " + kind.pastTense + ".");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + kind.pastTenseCapitalized + " " + ChatColor.WHITE + target.getName() + ChatColor.GREEN + ".");
        target.sendMessage(ChatColor.GREEN + "You have been " + kind.pastTense + " by " + ChatColor.WHITE + sender.getName() + ChatColor.GREEN + ".");
    }

    private void restoreAll(CommandSender sender) {
        int[] count = {0};
        Consumer<Player> restore = player -> {
            if (player.isDead()) {
                return;
            }
            kind.restore(player);
            count[0]++;
            if (!PlayerCommandSupport.isSelf(sender, player)) {
                player.sendMessage(ChatColor.GREEN + "You have been " + kind.pastTense + " by " + ChatColor.WHITE + sender.getName() + ChatColor.GREEN + ".");
            }
        };
        support.onlinePlayers().forEach(restore);
        sender.sendMessage(ChatColor.GREEN + kind.pastTenseCapitalized + " " + count[0] + " online player" + (count[0] == 1 ? "" : "s") + ".");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return support.completePlayers(args[0], ALL);
        }
        return Collections.emptyList();
    }
}
