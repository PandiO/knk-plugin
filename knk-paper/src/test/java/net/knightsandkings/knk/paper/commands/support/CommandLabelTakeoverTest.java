package net.knightsandkings.knk.paper.commands.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * PandiO/knk-workspace#580: WorldGuard enables before KnK and holds the plain /heal label; KnK takes it over in the
 * server's command map, WorldGuard's stays reachable as /worldguard:heal. Registration order as on a real server:
 * WorldGuard's commands first (its onEnable), then KnK's plugin.yml commands.
 */
class CommandLabelTakeoverTest {

    private ServerMock server;
    private Plugin knk;
    private Plugin worldGuard;
    private CommandMap map;
    private Command worldGuardHeal;
    private PluginCommand knkHeal;
    private PluginCommand knkFeed;

    /** KnK's plugin.yml commands are PluginCommands, whose constructor is protected. */
    private static PluginCommand pluginCommand(String name, Plugin owner, String... aliases) {
        try {
            Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);
            PluginCommand command = constructor.newInstance(name, owner);
            command.setAliases(List.of(aliases));
            return command;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Like WorldGuard's DynamicPluginCommand: registered in onEnable, identified by its plugin. */
    private static final class OtherPluginCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin owner;

        OtherPluginCommand(String name, Plugin owner) {
            super(name);
            this.owner = owner;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }

        @Override
        public Plugin getPlugin() {
            return owner;
        }
    }

    /** A command no plugin owns, standing in for a vanilla/Bukkit one. */
    private static final class ServerCommand extends Command {
        ServerCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        knk = MockBukkit.createMockPlugin("KnightsAndKings");
        worldGuard = MockBukkit.createMockPlugin("WorldGuard");
        map = server.getCommandMap();
        worldGuardHeal = new OtherPluginCommand("heal", worldGuard);
        map.register("worldguard", worldGuardHeal);
        knkHeal = pluginCommand("heal", knk);
        knkFeed = pluginCommand("feed", knk);
        map.register("knightsandkings", knkHeal);
        map.register("knightsandkings", knkFeed);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void withoutTheTakeoverThePlainHealIsWorldGuards() {
        assertSame(worldGuardHeal, map.getCommand("heal"));
        assertSame(knkHeal, map.getCommand("knightsandkings:heal"));
    }

    @Test
    void healIsTakenOverAndWorldGuardsStaysNamespaced() {
        List<String> taken = CommandLabelTakeover.takeOver(map, knk, CommandLabelTakeover.TAKEN_OVER);

        assertEquals(List.of("/heal now runs KnightsAndKings's command instead of WorldGuard's"), taken);
        assertSame(knkHeal, map.getCommand("heal"));
        assertSame(knkHeal, map.getCommand("knightsandkings:heal"));
        assertSame(worldGuardHeal, map.getCommand("worldguard:heal"));
        assertEquals("heal", knkHeal.getLabel());
        assertSame(knkFeed, map.getCommand("feed"));
    }

    @Test
    void takingOverAgainChangesNothing() {
        CommandLabelTakeover.takeOver(map, knk, CommandLabelTakeover.TAKEN_OVER);

        assertEquals(List.of(), CommandLabelTakeover.takeOver(map, knk, CommandLabelTakeover.TAKEN_OVER));
        assertSame(knkHeal, map.getCommand("heal"));
    }

    @Test
    void aPluginRegisteringHealLaterDoesNotTakeItBack() {
        CommandLabelTakeover.takeOver(map, knk, CommandLabelTakeover.TAKEN_OVER);
        Plugin later = MockBukkit.createMockPlugin("LaterPlugin");
        PluginCommand laterHeal = pluginCommand("heal", later);

        map.register("laterplugin", laterHeal);

        assertSame(knkHeal, map.getCommand("heal"));
        assertSame(laterHeal, map.getCommand("laterplugin:heal"));
    }

    @Test
    void aLabelKnkHasNoCommandForIsLeftAlone() {
        Command worldGuardGod = new OtherPluginCommand("god", worldGuard);
        map.register("worldguard", worldGuardGod);

        assertEquals(List.of(), CommandLabelTakeover.takeOver(map, knk, Set.of("god")));
        assertSame(worldGuardGod, map.getCommand("god"));
    }

    @Test
    void clashesListOtherPluginsLabelsButNotServerCommands() {
        map.register("worldguard", new OtherPluginCommand("god", worldGuard));
        map.register("minecraft", new ServerCommand("spawnpoint"));
        PluginCommand knkGodMode = pluginCommand("godmode", knk, "god", "spawnpoint");
        map.register("knightsandkings", knkGodMode);

        assertEquals(List.of("heal (WorldGuard)", "god (WorldGuard)"),
            CommandLabelTakeover.clashes(map, knk, List.of("heal", "feed", "godmode", "god", "spawnpoint")));

        CommandLabelTakeover.takeOver(map, knk, CommandLabelTakeover.TAKEN_OVER);

        assertEquals(List.of("god (WorldGuard)"), CommandLabelTakeover.clashes(map, knk, List.of("heal", "feed", "godmode", "god", "spawnpoint")));
    }

    @Test
    void labelsComeFromPluginYmlNamesAndAliases() {
        Map<String, Map<String, Object>> commands = new LinkedHashMap<>();
        commands.put("Heal", Map.of());
        commands.put("msg", Map.of("aliases", List.of("tell", "W")));
        commands.put("gamemode", Map.of("aliases", "gm"));
        commands.put("feed", null);

        assertEquals(List.of("heal", "msg", "tell", "w", "gamemode", "gm", "feed"),
            List.copyOf(CommandLabelTakeover.labelsOf(commands)));
    }

    @Test
    void healIsReachableByItsPlainLabelThroughDispatch() {
        boolean[] ran = {false};
        knkHeal.setExecutor((sender, command, label, args) -> {
            ran[0] = true;
            return true;
        });
        CommandLabelTakeover.takeOver(map, knk, CommandLabelTakeover.TAKEN_OVER);

        assertTrue(map.dispatch(server.getConsoleSender(), "heal"));
        assertTrue(ran[0]);
    }
}
