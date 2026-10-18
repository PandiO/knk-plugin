package net.knightsandkings.knk.paper.commands.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** KNG-107 live test: other plugins' commands a player can't run are left out of their "/" list. */
class OtherPluginCommandVisibilityTest {

    private final Plugin knk = mock(Plugin.class);
    private final Plugin other = mock(Plugin.class);
    private final Player player = mock(Player.class);
    private final Map<String, Command> commands = new HashMap<>();
    private final OtherPluginCommandVisibility hidden = new OtherPluginCommandVisibility(commands::get, knk, Set.of("Rules"));

    /** A plain plugin.yml command: Bukkit's own permission test. */
    private static class PlainCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;

        PlainCommand(String name, Plugin plugin, String permission) {
            super(name, "", "/" + name, List.of());
            this.plugin = plugin;
            setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }

    /** WorldEdit-style: no permission field, its own test. */
    private static final class SelfTestedCommand extends PlainCommand {
        private final boolean allowed;

        SelfTestedCommand(String name, Plugin plugin, boolean allowed) {
            super(name, plugin, null);
            this.allowed = allowed;
        }

        @Override
        public boolean testPermissionSilent(CommandSender target) {
            return allowed;
        }
    }

    @Test
    void anotherPluginsCommandWithoutAPermission_isHidden() {
        commands.put("autopluginloader", new PlainCommand("autopluginloader", other, null));

        assertTrue(hidden.test(player, "autopluginloader"));
    }

    @Test
    void anotherPluginsCommand_followsItsPermission() {
        commands.put("warn", new PlainCommand("warn", other, "other.warn"));

        assertTrue(hidden.test(player, "warn"));
        when(player.hasPermission("other.warn")).thenReturn(true);
        assertFalse(hidden.test(player, "warn"));
    }

    @Test
    void aCommandWithItsOwnPermissionTest_isTrusted() {
        commands.put("/pos1", new SelfTestedCommand("/pos1", other, false));
        commands.put("/pos2", new SelfTestedCommand("/pos2", other, true));

        assertTrue(hidden.test(player, "/pos1"));
        assertFalse(hidden.test(player, "/pos2"));
    }

    @Test
    void knkCommands_vanillaCommands_unknownLabels_alwaysShown_andOps_areLeftAlone() {
        commands.put("msg", new PlainCommand("msg", knk, null));
        commands.put("rules", new PlainCommand("rules", other, null));
        commands.put("tp", mock(Command.class)); // not a plugin command: Paper filters it already

        assertFalse(hidden.test(player, "msg"));
        assertFalse(hidden.test(player, "rules"));
        assertFalse(hidden.test(player, "tp"));
        assertFalse(hidden.test(player, "nothing"));

        commands.put("autopluginloader", new PlainCommand("autopluginloader", other, null));
        when(player.isOp()).thenReturn(true);
        assertFalse(hidden.test(player, "autopluginloader"));
    }
}
