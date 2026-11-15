package net.knightsandkings.knk.paper.commands.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Takes plain command labels back from plugins that enabled before KnK (PandiO/knk-workspace#580).
 * <p>
 * KnK {@code depend}s on WorldGuard, so WorldGuard enables first and registers its own {@code /heal} in its
 * {@code onEnable}. When Bukkit then registers KnK's plugin.yml commands, the plain {@code heal} label is taken and
 * KnK's {@code /heal} is left only as {@code /knightsandkings:heal}: a player granted {@code knk.heal} in-house who
 * types {@code /heal} reaches WorldGuard's command and its {@code worldguard.heal} check. Vanilla commands don't have
 * this problem (Bukkit lets plugin commands win over them, which is how KnK's {@code /tp} and {@code /gamemode} work).
 * <p>
 * {@link #takeOver} points the plain label at KnK's command in the server's command map, so the label, tab
 * completion, the console and the "/" list all reach KnK, and fixes the command's label so a plugin registering the
 * label later can't silently take it back. The other plugin's command keeps its namespaced label
 * ({@code /worldguard:heal}). {@link #clashes} lists every other plain label of KnK's that another plugin holds,
 * for a startup warning.
 */
public final class CommandLabelTakeover {

    /** Labels KnK takes over from another plugin (developer decision 2026-11-15 on PandiO/knk-workspace#580). */
    public static final Set<String> TAKEN_OVER = Set.of("heal");

    private CommandLabelTakeover() {
    }

    /**
     * Points each plain label at {@code self}'s command of that name when another command holds it. Returns one
     * log-ready line per label taken over (empty when KnK already had them all).
     */
    public static List<String> takeOver(CommandMap map, Plugin self, Collection<String> labels) {
        List<String> taken = new ArrayList<>();
        Map<String, Command> known = map.getKnownCommands();
        for (String raw : labels) {
            String label = raw.toLowerCase(Locale.ROOT);
            Command own = map.getCommand(namespace(self) + ":" + label);
            if (!(own instanceof PluginCommand)) {
                continue;
            }
            Command holder = known.get(label);
            if (holder == own) {
                continue;
            }
            known.put(label, own);
            fixLabel(map, own, label);
            taken.add("/" + label + " now runs " + self.getName() + "'s command"
                + (holder == null ? "" : " instead of " + describe(holder) + "'s"));
        }
        return taken;
    }

    /**
     * The plain labels of plugin.yml's {@code commands:} section (each name and its {@code aliases}, a list or a single
     * string), lower case. Read from plugin.yml, not from the registered commands: an alias Bukkit could not register
     * is dropped from {@code Command#getAliases()}.
     */
    public static Set<String> labelsOf(Map<String, Map<String, Object>> commands) {
        Set<String> labels = new LinkedHashSet<>();
        commands.forEach((name, settings) -> {
            labels.add(name.toLowerCase(Locale.ROOT));
            Object aliases = settings == null ? null : settings.get("aliases");
            if (aliases instanceof Collection<?> list) {
                list.forEach(alias -> labels.add(String.valueOf(alias).toLowerCase(Locale.ROOT)));
            } else if (aliases != null) {
                labels.add(aliases.toString().toLowerCase(Locale.ROOT));
            }
        });
        return labels;
    }

    /**
     * Every one of {@code ownLabels} that another plugin's command holds, as "label (Plugin)". Vanilla commands are
     * not listed: KnK's commands win over them.
     */
    public static List<String> clashes(CommandMap map, Plugin self, Collection<String> ownLabels) {
        List<String> clashes = new ArrayList<>();
        for (String raw : ownLabels) {
            String label = raw.toLowerCase(Locale.ROOT);
            Command holder = map.getKnownCommands().get(label);
            if (holder instanceof PluginIdentifiableCommand owned && owned.getPlugin() != self) {
                clashes.add(label + " (" + owned.getPlugin().getName() + ")");
            }
        }
        return clashes;
    }

    /** Takes over {@link #TAKEN_OVER} and warns about any other clash; safe to call again (it then does nothing). */
    public static void apply(CommandMap map, Plugin self, Collection<String> ownLabels, Logger log) {
        for (String line : takeOver(map, self, TAKEN_OVER)) {
            log.info(line + " (PandiO/knk-workspace#580)");
        }
        List<String> clashes = clashes(map, self, ownLabels);
        if (!clashes.isEmpty()) {
            log.warning("These KnK command labels belong to another plugin, so KnK's command is only reachable as "
                + "/" + namespace(self) + ":<label>: " + String.join(", ", clashes));
        }
    }

    /** Takes {@link #TAKEN_OVER} over again once every plugin is enabled (a plugin enabling after KnK may take one). */
    public static final class OnServerLoad implements Listener {

        private final Plugin self;

        public OnServerLoad(Plugin self) {
            this.self = self;
        }

        @EventHandler
        public void onServerLoad(ServerLoadEvent event) {
            for (String line : takeOver(self.getServer().getCommandMap(), self, TAKEN_OVER)) {
                self.getLogger().info(line + " (PandiO/knk-workspace#580)");
            }
        }
    }

    /** Bukkit renamed a command that lost its label to "prefix:label"; give it the plain one back. */
    private static void fixLabel(CommandMap map, Command command, String label) {
        if (label.equals(command.getLabel())) {
            return;
        }
        command.setLabel(label);
        if (command.unregister(map)) {
            command.register(map);
        }
    }

    private static String describe(Command command) {
        if (command instanceof PluginIdentifiableCommand owned) {
            return owned.getPlugin().getName();
        }
        return command.getClass().getSimpleName();
    }

    private static String namespace(Plugin plugin) {
        return plugin.getName().toLowerCase(Locale.ROOT);
    }
}
