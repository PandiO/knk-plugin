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
        HEAL("heal", "health") {
            @Override
            double restore(Player player) {
                AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = maxHealthAttribute != null ? maxHealthAttribute.getValue() : 20.0;
                double before = player.getHealth();
                player.setHealth(maxHealth);
                player.setFireTicks(0);
                return Math.max(0, maxHealth - before);
            }
        },
        FEED("feed", "hunger") {
            @Override
            double restore(Player player) {
                int before = player.getFoodLevel();
                player.setFoodLevel(MAX_FOOD);
                player.setSaturation(MAX_FOOD);
                player.setExhaustion(0f);
                return Math.max(0, MAX_FOOD - before);
            }
        };

        private static final int MAX_FOOD = 20;

        private final String label;
        private final String resource;

        Kind(String label, String resource) {
            this.label = label;
            this.resource = resource;
        }

        /** Refills the player and returns how much was added (health points, or food points). */
        abstract double restore(Player player);

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
            sender.sendMessage(ChatColor.RED + target.getName() + " can't be replenished right now.");
            return;
        }
        String amount = amount(kind.restore(target));
        if (self) {
            target.sendMessage(ChatColor.GREEN + "Replenished " + ChatColor.WHITE + amount + ChatColor.GREEN + " " + kind.resource + ".");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Replenished " + ChatColor.WHITE + target.getName() + ChatColor.GREEN + " with "
                + ChatColor.WHITE + amount + ChatColor.GREEN + " " + kind.resource + ".");
        notifyTarget(sender, target, amount);
    }

    private void restoreAll(CommandSender sender) {
        int[] count = {0};
        double[] total = {0};
        Consumer<Player> restore = player -> {
            if (player.isDead()) {
                return;
            }
            double restored = kind.restore(player);
            count[0]++;
            total[0] += restored;
            if (!PlayerCommandSupport.isSelf(sender, player)) {
                notifyTarget(sender, player, amount(restored));
            }
        };
        support.onlinePlayers().forEach(restore);
        sender.sendMessage(ChatColor.GREEN + "Replenished " + ChatColor.WHITE + count[0] + ChatColor.GREEN + " online player"
                + (count[0] == 1 ? "" : "s") + " with " + ChatColor.WHITE + amount(total[0]) + ChatColor.GREEN + " " + kind.resource + " in total.");
    }

    private void notifyTarget(CommandSender sender, Player target, String amount) {
        target.sendMessage(ChatColor.GREEN + "You were replenished with " + ChatColor.WHITE + amount + ChatColor.GREEN + " "
                + kind.resource + " by " + ChatColor.WHITE + sender.getName() + ChatColor.GREEN + ".");
    }

    /** "7", "7.5" - health comes in half points. */
    static String amount(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return support.completePlayers(args[0], ALL);
        }
        return Collections.emptyList();
    }
}
