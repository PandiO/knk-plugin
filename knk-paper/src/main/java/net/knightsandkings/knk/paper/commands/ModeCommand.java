package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * /ownermode and /staffmode (docs/specs/user-features/DESIGN.md §6.1, vision §5.5) - one
 * executor instance per mode. Ports v1's OwnerCommands argument shape:
 * <ul>
 *   <li>{@code /<mode>} - toggle (switches over from the other mode if the player is in it)</li>
 *   <li>{@code /<mode> on|enable} / {@code off|disable} - explicit enable/disable</li>
 *   <li>{@code /<mode> on|off onquit|oq} - don't change anything now; take effect on the
 *       player's next join (v1: enable without a join message / disable without a leave message).
 *       With the mode now persisted, this is simply "persist the new mode without applying it to
 *       the current session".</li>
 *   <li>{@code /<mode> help}</li>
 * </ul>
 * Gated on {@code knk.mode.owner}/{@code knk.mode.staff} through {@code KnkPermissible} (real
 * async resolution, op bypass included), not a plugin.yml {@code permission:} - Bukkit would
 * check that against its own permission tree before this executor ever ran, same reasoning as
 * /knk's per-subcommand nodes. Unlike v1, /staffmode is not refused for owners/co-owners: anyone
 * holding the staff node may use it.
 */
public class ModeCommand implements CommandExecutor, TabCompleter {

    private enum Action { TOGGLE, ENABLE, DISABLE }

    private final ModeService modeService;
    private final ActiveMode mode;

    public ModeCommand(ModeService modeService, ActiveMode mode) {
        this.modeService = Objects.requireNonNull(modeService, "modeService must not be null");
        if (mode == null || mode == ActiveMode.NONE) {
            throw new IllegalArgumentException("ModeCommand needs a concrete mode, got " + mode);
        }
        this.mode = mode;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.").color(ColorOptions.falsecommand));
            return true;
        }

        Action action;
        boolean onQuit = false;
        if (args.length == 0) {
            action = Action.TOGGLE;
        } else {
            String first = args[0].toLowerCase(Locale.ROOT);
            switch (first) {
                case "on", "enable" -> action = Action.ENABLE;
                case "off", "disable" -> action = Action.DISABLE;
                case "help" -> {
                    sendHelp(player, label);
                    return true;
                }
                default -> {
                    sendUsage(player, label);
                    return true;
                }
            }
            if (args.length == 2) {
                String second = args[1].toLowerCase(Locale.ROOT);
                if (!second.equals("onquit") && !second.equals("oq")) {
                    sendUsage(player, label);
                    return true;
                }
                onQuit = true;
            } else if (args.length > 2) {
                sendUsage(player, label);
                return true;
            }
        }

        final Action resolvedAction = action;
        final boolean resolvedOnQuit = onQuit;
        modeService.whenHasModePermission(player, mode, allowed -> {
            if (!player.isOnline()) {
                return;
            }
            if (!allowed) {
                player.sendMessage(Component.text("You don't have permission for this command!").color(ColorOptions.falsecommand));
                return;
            }
            execute(player, resolvedAction, resolvedOnQuit);
        });
        return true;
    }

    private void execute(Player player, Action action, boolean onQuit) {
        ActiveMode current = modeService.getActiveMode(player);
        String name = ModeService.displayName(mode);

        if (onQuit) {
            ActiveMode target;
            if (action == Action.ENABLE) {
                target = mode;
            } else {
                if (current != mode && modeService.getPersistedMode(player) != mode) {
                    player.sendMessage(Component.text("You are not in " + name + ".").color(ColorOptions.falsecommand));
                    return;
                }
                target = ActiveMode.NONE;
            }
            modeService.persist(player, target)
                .thenRun(() -> player.sendMessage(Component.text(capitalize(name) + " will be "
                    + (target == ActiveMode.NONE ? "disabled" : "enabled") + " when you next join the server.")
                    .color(ColorOptions.messageachievement)))
                .exceptionally(ex -> {
                    player.sendMessage(Component.text("Failed to save your " + name + " change; nothing will change on your next join.")
                        .color(ColorOptions.falsecommand));
                    return null;
                });
            return;
        }

        ActiveMode target;
        switch (action) {
            case TOGGLE -> target = (current == mode) ? ActiveMode.NONE : mode;
            case ENABLE -> {
                if (current == mode) {
                    player.sendMessage(Component.text("You are already in " + name + ".").color(ColorOptions.falsecommand));
                    return;
                }
                target = mode;
            }
            case DISABLE -> {
                if (current != mode) {
                    player.sendMessage(Component.text("You are not in " + name + ".").color(ColorOptions.falsecommand));
                    return;
                }
                target = ActiveMode.NONE;
            }
            default -> throw new IllegalStateException("Unhandled action " + action);
        }

        modeService.applyMode(player, target, true);
        modeService.persist(player, target).exceptionally(ex -> {
            player.sendMessage(Component.text("Failed to save your " + name + " state; it may reset the next time you join.")
                .color(ColorOptions.falsecommand));
            return null;
        });
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage(Component.text("Usage: /" + label + " [on|off] [onquit] - or /" + label + " help")
            .color(ColorOptions.falsecommand));
    }

    private void sendHelp(Player player, String label) {
        String name = ModeService.displayName(mode);
        List<String> lines = List.of(
            "/" + label + " (toggles " + name + ")",
            "/" + label + " enable (instantly enables " + name + ")",
            "/" + label + " enable onquit (enables " + name + " from your next join, no join message)",
            "/" + label + " disable (instantly disables " + name + ")",
            "/" + label + " disable onquit (disables " + name + " from your next join, no leave message)"
        );
        player.sendMessage(Component.text("List of " + name + " commands").color(ColorOptions.statsformat));
        for (String line : lines) {
            player.sendMessage(Component.text("- " + line).color(ColorOptions.stats));
        }
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("on", "off", "enable", "disable", "help"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("help")) {
            return filter(List.of("onquit"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lowered)).toList();
    }
}
