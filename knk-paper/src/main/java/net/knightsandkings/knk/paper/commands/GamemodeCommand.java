package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;

/**
 * {@code /gamemode <mode> [player]} (alias {@code /gm}) - port of v1's {@code GameModeCommand} and v2's
 * {@code Gamemode} (docs/specs/legacy/commands-v1.md, commands-v2.md), so a rank can be given the game mode
 * without being op (alpha test run 1, PandiO/knk-workspace#158: vanilla {@code /gamemode} needs
 * {@code minecraft.command.gamemode}, which in-house grants never reach).
 * <ul>
 *   <li>{@code /gamemode <mode>} sets your own game mode; needs {@code knk.gamemode.<mode>}.</li>
 *   <li>{@code /gamemode <mode> <player>} sets an online player's; needs {@code knk.gamemode.<mode>} and
 *       {@value #NODE_OTHERS}.</li>
 * </ul>
 * Modes: {@code survival|s|0}, {@code creative|c|1}, {@code adventure|a|2}, {@code spectator|sp|3}.
 * {@code knk.gamemode.*} covers every mode and {@code .others}. Declaring {@code gamemode} in plugin.yml makes
 * the bare label resolve to this plugin (as {@code /tp}); vanilla stays reachable as {@code /minecraft:gamemode}.
 * Permissions resolve through {@code KnkPermissible}, hence no {@code permission:} on the plugin.yml entry.
 */
public class GamemodeCommand implements TabExecutor {

    public static final String NODE_PREFIX = "knk.gamemode.";
    public static final String NODE_OTHERS = NODE_PREFIX + "others";
    /** Every node the command checks: one per mode, and {@value #NODE_OTHERS}. */
    public static final List<String> NODES = List.of(node(GameMode.SURVIVAL), node(GameMode.CREATIVE),
        node(GameMode.ADVENTURE), node(GameMode.SPECTATOR), NODE_OTHERS);

    private static final List<String> MODE_WORDS = List.of("survival", "creative", "adventure", "spectator");

    private final PlayerCommandSupport support;

    public GamemodeCommand(PlayerCommandSupport support) {
        this.support = support;
    }

    /** {@code knk.gamemode.<mode>}, e.g. {@code knk.gamemode.creative}. */
    public static String node(GameMode mode) {
        return NODE_PREFIX + mode.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            sendUsage(sender);
            return true;
        }
        GameMode mode = parseMode(args[0]);
        if (mode == null) {
            sender.sendMessage(ChatColor.RED + "Unknown game mode '" + args[0] + "'. Use survival, creative, adventure or spectator.");
            return true;
        }

        Player target;
        if (args.length == 1) {
            target = support.requirePlayer(sender);
            if (target == null) {
                sender.sendMessage(ChatColor.YELLOW + "Usage from the console: /gamemode <mode> <player>");
                return true;
            }
        } else {
            target = support.requireOnlinePlayer(sender, args[1], null);
            if (target == null) {
                return true;
            }
        }

        boolean self = PlayerCommandSupport.isSelf(sender, target);
        support.whenAllowed(sender, node(mode), () -> {
            if (self) {
                apply(sender, target, true, mode);
            } else {
                support.whenAllowed(sender, NODE_OTHERS, () -> apply(sender, target, false, mode));
            }
        });
        return true;
    }

    private void apply(CommandSender sender, Player target, boolean self, GameMode mode) {
        if (!target.isOnline()) {
            sender.sendMessage(ChatColor.RED + target.getName() + " went offline.");
            return;
        }
        target.setGameMode(mode);
        String name = ChatColor.WHITE + displayName(mode) + ChatColor.GRAY;
        if (self) {
            target.sendMessage(ChatColor.GRAY + "Game mode set to " + name + ".");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "Game mode of " + ChatColor.WHITE + target.getName() + ChatColor.GRAY + " set to " + name + ".");
        target.sendMessage(ChatColor.GRAY + "Your game mode was set to " + name + " by " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ".");
    }

    /** survival/s/0, creative/c/1, adventure/a/2, spectator/sp/3 (any case); null for anything else. */
    static GameMode parseMode(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "survival", "s", "0" -> GameMode.SURVIVAL;
            case "creative", "c", "1" -> GameMode.CREATIVE;
            case "adventure", "a", "2" -> GameMode.ADVENTURE;
            case "spectator", "sp", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static String displayName(GameMode mode) {
        String lower = mode.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void sendUsage(CommandSender sender) {
        // KNG-107: the [player] form only for holders of its node.
        sender.sendMessage(ChatColor.YELLOW + (support.holds(sender, NODE_OTHERS)
            ? "Usage: /gamemode <survival|creative|adventure|spectator> [player]"
            : "Usage: /gamemode <survival|creative|adventure|spectator>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // KNG-107: only the modes the sender holds; player names with knk.gamemode.others (cached checks).
        if (args.length == 1) {
            List<String> modes = new ArrayList<>();
            for (String word : MODE_WORDS) {
                if (support.holds(sender, NODE_PREFIX + word)) {
                    modes.add(word);
                }
            }
            return PlayerCommandSupport.completeWords(args[0], modes);
        }
        if (args.length == 2 && parseMode(args[0]) != null && support.holds(sender, NODE_OTHERS)) {
            return support.completePlayers(sender, args[1]);
        }
        return Collections.emptyList();
    }
}
