package net.knightsandkings.knk.paper.commands.support;

import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * KNG-107 (live test): which other plugins' commands to leave out of a player's "/" list. A player
 * saw WorldEdit, WorldGuard, FastAsyncWorldEdit and AutoPluginLoader commands they can't run.
 * <p>
 * Hidden (answers true) for a non-op when the label is another plugin's command and either the
 * command's own permission test refuses the player, or it declares no permission and has no test of
 * its own - a plain plugin.yml command without {@code permission:}, which Bukkit offers to everyone.
 * Commands with their own test (WorldEdit's and WorldGuard's check their nodes there) are trusted.
 * Left alone: KnK's own commands (gated by {@code CommandNodes}), vanilla/Bukkit/Paper commands
 * (Paper already filters them by permission), unknown labels and the configured {@code alwaysShow}
 * labels. Bukkit permission checks only, no API calls.
 */
public final class OtherPluginCommandVisibility implements BiPredicate<Player, String> {

    private final Function<String, Command> lookup;
    private final Plugin self;
    private final Set<String> alwaysShow;

    /** @param lookup the server's command for a label (e.g. {@code Bukkit.getCommandMap()::getCommand}), null when none */
    public OtherPluginCommandVisibility(Function<String, Command> lookup, Plugin self, Set<String> alwaysShow) {
        this.lookup = lookup;
        this.self = self;
        this.alwaysShow = alwaysShow.stream().map(label -> label.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean test(Player player, String label) {
        if (player.isOp() || alwaysShow.contains(label.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Command command = lookup.apply(label);
        if (!(command instanceof PluginIdentifiableCommand owned) || owned.getPlugin() == self) {
            return false;
        }
        if (!command.testPermissionSilent(player)) {
            return true;
        }
        String permission = command.getPermission();
        return (permission == null || permission.isBlank()) && !hasOwnPermissionTest(command);
    }

    private static boolean hasOwnPermissionTest(Command command) {
        try {
            return command.getClass().getMethod("testPermissionSilent", CommandSender.class).getDeclaringClass() != Command.class;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }
}
