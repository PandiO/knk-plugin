package net.knightsandkings.knk.paper.listeners;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.paper.commands.support.CommandPermissions;

/**
 * Hides commands gated on an in-house node from players who lack it (KNG-24), the way Bukkit hides a
 * command whose plugin.yml {@code permission:} the player lacks - the gate moved out of plugin.yml
 * (see {@link net.knightsandkings.knk.paper.commands.support.PermissionGatedCommand}), so without
 * this every player would see /freeze, /unfreeze and /staffchat in suggestions.
 * <p>
 * The command list is sent at join, before the permission cache is warm, so the first list uses the
 * cache-only check ({@link CommandPermissions#has}). The real answers are then fetched; when they
 * differ from what was sent, the list is re-sent ({@link Player#updateCommands()}), and that re-send
 * uses those answers - so it settles after one round instead of looping. Hiding is cosmetic: the
 * executor checks the node again either way.
 */
public final class GatedCommandVisibilityListener implements Listener {

    private static final String PLUGIN_NAMESPACE = "knightsandkings:";

    private final CommandPermissions permissions;
    private final Executor mainThread;
    private final Map<String, String> nodeByLabel = new HashMap<>();
    private final Map<UUID, Map<String, Boolean>> resolved = new ConcurrentHashMap<>();

    public GatedCommandVisibilityListener(CommandPermissions permissions, Executor mainThread) {
        this.permissions = permissions;
        this.mainThread = mainThread;
    }

    /** Hides the command (its name and aliases, plain and namespaced) from players lacking {@code node}. */
    public void gate(PluginCommand command, String node) {
        Set<String> labels = new LinkedHashSet<>();
        labels.add(command.getName());
        labels.addAll(command.getAliases());
        gate(labels, node);
    }

    /** As {@link #gate(PluginCommand, String)} for bare labels. */
    public void gate(Collection<String> labels, String node) {
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.ROOT);
            nodeByLabel.put(lower, node);
            nodeByLabel.put(PLUGIN_NAMESPACE + lower, node);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (nodeByLabel.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        Map<String, Boolean> known = resolved.get(player.getUniqueId());
        Map<String, Boolean> shown = new HashMap<>();
        for (String node : Set.copyOf(nodeByLabel.values())) {
            Boolean answer = known == null ? null : known.get(node);
            shown.put(node, answer != null ? answer : permissions.has(player, node));
        }
        event.getCommands().removeIf(label -> {
            String node = nodeByLabel.get(label.toLowerCase(Locale.ROOT));
            return node != null && !shown.get(node);
        });
        refresh(player, shown);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        resolved.remove(event.getPlayer().getUniqueId());
    }

    private void refresh(Player player, Map<String, Boolean> shown) {
        UUID id = player.getUniqueId();
        List<String> nodes = List.copyOf(shown.keySet());
        List<CompletableFuture<Boolean>> checks = nodes.stream()
                .map(node -> permissions.hasAsync(player, node))
                .toList();
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenRun(() -> mainThread.execute(() -> {
            if (!player.isOnline()) {
                return;
            }
            Map<String, Boolean> answers = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                answers.put(nodes.get(i), checks.get(i).join());
            }
            resolved.put(id, answers);
            if (!answers.equals(shown)) {
                player.updateCommands();
            }
        }));
    }
}
