package net.knightsandkings.knk.paper.npc.nav;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * NAV-08, "measure, then guard" (implementation plan Phase 1.3): while running, a monitor on every event type counts
 * the events whose player or entity is an NPC ({@link NpcEntities#isNpc}), and the report lists, per event type, which
 * of this plugin's listeners receive it. Those are the listeners to guard. A count, not a guess: most of the plugin's
 * player listeners never see an NPC. Event types first used after {@link #start} are picked up by starting again.
 * <p>
 * Damage events also list their causes. The listeners named are the event type's handler list, which Bukkit shares
 * between {@code EntityDamageEvent} and {@code EntityDamageByEntityEvent}: a listener for hits is listed for plain
 * damage too, but never called for it. A listed listener is a receiver, not proof that it acts: the guarded ones
 * return at once for an NPC (PandiO/knk-workspace#490).
 */
public final class NpcEventAudit {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);

    private final Plugin plugin;
    private final Listener marker = new Listener() {
    };
    private final Map<Class<? extends Event>, LongAdder> counts = new ConcurrentHashMap<>();
    private final Map<Class<? extends Event>, HandlerList> handlerLists = new ConcurrentHashMap<>();
    /** Damage events by cause, so the report says why NPCs take damage (#490). */
    private final Map<Class<? extends Event>, Map<String, LongAdder>> damageCauses = new ConcurrentHashMap<>();
    private volatile boolean running;
    private LocalDateTime startedAt;

    public NpcEventAudit(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    /** Starts (or restarts, keeping counts) the monitor on every event type registered so far. */
    public int start() {
        HandlerList.unregisterAll(marker);
        int lists = 0;
        for (HandlerList list : HandlerList.getHandlerLists()) {
            list.register(new RegisteredListener(marker, (listener, event) -> observe(event), EventPriority.MONITOR,
                plugin, false));
            lists++;
        }
        if (!running) {
            startedAt = LocalDateTime.now();
        }
        running = true;
        return lists;
    }

    public void stop() {
        HandlerList.unregisterAll(marker);
        running = false;
    }

    public void reset() {
        counts.clear();
        handlerLists.clear();
        damageCauses.clear();
        startedAt = running ? LocalDateTime.now() : null;
    }

    void observe(Event event) {
        if (!involvesNpc(event)) {
            return;
        }
        counts.computeIfAbsent(event.getClass(), c -> new LongAdder()).increment();
        handlerLists.putIfAbsent(event.getClass(), event.getHandlers());
        if (event instanceof EntityDamageEvent damage) {
            String cause = damage.getCause() == null ? "UNKNOWN" : damage.getCause().name();
            damageCauses.computeIfAbsent(event.getClass(), c -> new ConcurrentHashMap<>())
                .computeIfAbsent(cause, c -> new LongAdder()).increment();
        }
    }

    static boolean involvesNpc(Event event) {
        Entity subject = null;
        if (event instanceof PlayerEvent playerEvent) {
            subject = playerEvent.getPlayer();
        } else if (event instanceof EntityEvent entityEvent) {
            subject = entityEvent.getEntity();
        }
        if (NpcEntities.isNpc(subject)) {
            return true;
        }
        return event instanceof EntityDamageByEntityEvent damage && NpcEntities.isNpc(damage.getDamager());
    }

    /** Report lines: per event type the NPC count and this plugin's listeners that receive it, most frequent first. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        lines.add("NPC event audit" + (startedAt == null ? "" : " since " + startedAt.withNano(0))
            + (running ? " (running)" : " (stopped)"));
        if (counts.isEmpty()) {
            lines.add("  no events involving NPCs");
            return lines;
        }
        counts.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<Class<? extends Event>, LongAdder> e) -> e.getValue().sum()).reversed())
            .forEach(entry -> {
                Set<String> ours = new TreeSet<>();
                HandlerList list = handlerLists.get(entry.getKey());
                if (list != null) {
                    for (RegisteredListener registered : list.getRegisteredListeners()) {
                        if (registered.getPlugin() == plugin && registered.getListener() != marker) {
                            ours.add(registered.getListener().getClass().getName()
                                .replace("net.knightsandkings.knk.paper.", ""));
                        }
                    }
                }
                lines.add(String.format(Locale.ROOT, "  %-40s %8d  -> %s", entry.getKey().getSimpleName(),
                    entry.getValue().sum(), ours.isEmpty() ? "(no K&K listener)" : String.join(", ", ours)));
                Map<String, LongAdder> causes = damageCauses.get(entry.getKey());
                if (causes != null) {
                    lines.add("      causes: " + causes.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> c) -> c.getValue().sum())
                            .reversed().thenComparing(Map.Entry::getKey))
                        .map(c -> c.getKey() + " " + c.getValue().sum())
                        .collect(Collectors.joining(", ")));
                }
            });
        return lines;
    }

    /** Writes the report to {@code plugins/<plugin>/npc-logs/<time>_npc-event-audit.log}; returns the file name. */
    public String writeReport() throws IOException {
        Path folder = plugin.getDataFolder().toPath().resolve("npc-logs");
        Files.createDirectories(folder);
        Path file = folder.resolve(LocalDateTime.now().format(FILE_TIME) + "_npc-event-audit.log");
        Files.write(file, report(), StandardCharsets.UTF_8);
        return file.getFileName().toString();
    }
}
