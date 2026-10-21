package net.knightsandkings.knk.paper.duel;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.view.ArenaMenuView;
import net.knightsandkings.knk.core.duel.view.DuelHudView;
import net.knightsandkings.knk.core.duel.view.DuelMatchSnapshot;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The duel sidebar and boss bar (arena duels DESIGN §9.3, §5.1; plan Phase 6) for the duelists of a running match
 * and for the players standing in an arena's spectator area while it has live duels. Refreshed every second (the
 * runtime's ticker calls {@link #tick}) and on every phase change.
 * <ul>
 *   <li>Each viewer gets their own board ({@link DuelHudView#sidebar}: their own duel first, then the arena's live
 *       duels and its queue); the board they had before comes back when they stop viewing.</li>
 *   <li>The boss bar follows the duelist's own match; a spectator's follows the arena's newest match on a
 *       battleground, else its newest live match ({@link DuelHudView#followed}).</li>
 *   <li>{@link #isViewing} is what {@code DuelParticipation.keepsOwnScoreboard} asks (through
 *       {@link DuelService#showsDuelBoard}), so the registry keeps {@code ScoreboardUtil} off these boards.</li>
 * </ul>
 * Main thread only.
 */
public final class DuelHud implements DuelObserver {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String OBJECTIVE = "duel";

    /** What one viewer is shown. */
    private static final class Viewer {
        final Scoreboard previous;
        final Scoreboard board;
        final BossBar bar;
        int arenaId;

        Viewer(Scoreboard previous, Scoreboard board, BossBar bar) {
            this.previous = previous;
            this.board = board;
            this.bar = bar;
        }
    }

    private final DuelService service;
    private final DuelSpectators spectators;
    private final Function<UUID, Player> players;
    private final Function<Component, Scoreboard> newBoard;
    private final Logger logger;
    private final Map<UUID, Viewer> viewers = new HashMap<>();

    /**
     * @param newBoard a fresh scoreboard with the sidebar objective {@value #OBJECTIVE} titled as given
     *                 ({@link #bukkitBoards()} on the server)
     */
    public DuelHud(DuelService service, DuelSpectators spectators, Function<UUID, Player> players,
                   Function<Component, Scoreboard> newBoard, Logger logger) {
        this.service = Objects.requireNonNull(service, "service");
        this.spectators = Objects.requireNonNull(spectators, "spectators");
        this.players = Objects.requireNonNull(players, "players");
        this.newBoard = Objects.requireNonNull(newBoard, "newBoard");
        this.logger = logger != null ? logger : Logger.getLogger(DuelHud.class.getName());
    }

    /** The server's boards: a new scoreboard with a blank-numbered sidebar objective. */
    public static Function<Component, Scoreboard> bukkitBoards() {
        return title -> {
            Scoreboard board = org.bukkit.Bukkit.getScoreboardManager().getNewScoreboard();
            Objective sidebar = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title);
            sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
            sidebar.numberFormat(NumberFormat.blank());
            return board;
        };
    }

    /** Whether {@code playerId} is shown the duel sidebar now. */
    public boolean isViewing(UUID playerId) {
        return viewers.containsKey(playerId);
    }

    @Override
    public void phaseChanged(DuelMatch match, DuelPhase from, DuelPhase to) {
        tick();
    }

    @Override
    public void released(DuelMatch match) {
        tick();
    }

    /**
     * Recompute who sees what and repaint (every second, and on phase changes). Never throws: the runtime calls this
     * from inside its own steps, and a display failure must not stop a duel.
     */
    public void tick() {
        try {
            refresh();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "[Duel] sidebar/boss bar refresh failed", e);
        }
    }

    private void refresh() {
        List<DuelMatchSnapshot> all = DuelSnapshots.all(service);
        Map<Integer, List<DuelMatchSnapshot>> byArena = new LinkedHashMap<>();
        for (DuelMatchSnapshot m : all) byArena.computeIfAbsent(m.arenaId(), id -> new ArrayList<>()).add(m);

        // Who should see which arena: duelists their own; spectators an arena with a live match.
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        Map<UUID, DuelMatchSnapshot> own = new HashMap<>();
        for (DuelMatchSnapshot m : all) {
            if (m.phase() == DuelPhase.CLOSED) continue;
            for (UUID id : List.of(m.challenger().playerId(), m.opponent().playerId())) {
                wanted.put(id, m.arenaId());
                own.put(id, m);
            }
        }
        for (KnkArena arena : spectators.arenas()) {
            List<DuelMatchSnapshot> live = byArena.getOrDefault(arena.id(), List.of());
            if (live.stream().noneMatch(DuelMatchSnapshot::isListed)) continue;
            for (Player p : spectators.spectatorsOf(arena)) wanted.putIfAbsent(p.getUniqueId(), arena.id());
        }

        for (UUID id : new ArrayList<>(viewers.keySet())) {
            if (!wanted.containsKey(id)) stop(id);
        }
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            Player player = players.apply(e.getKey());
            if (player == null || !player.isOnline()) {
                stop(e.getKey());
                continue;
            }
            int arenaId = e.getValue();
            List<DuelMatchSnapshot> arenaMatches = byArena.getOrDefault(arenaId, List.of());
            DuelMatchSnapshot followed = own.get(e.getKey());
            if (followed == null) followed = DuelHudView.followed(arenaMatches).orElse(null);
            show(player, arenaId, arenaName(arenaMatches, arenaId),
                    DuelHudView.sidebar(e.getKey(), arenaMatches, queueOf(arenaId)),
                    followed);
        }
    }

    /** Every viewer back to their own board, every bar hidden (plugin disable). */
    public void shutdown() {
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            try {
                stop(id);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[Duel] restoring the board of " + id + " failed", e);
            }
        }
    }

    private DuelHudView.QueueInfo queueOf(int arenaId) {
        int max = spectators.arenas().stream().filter(a -> a.id() == arenaId).findFirst()
                .map(KnkArena::maxQueuedMatches).orElse(0);
        return DuelSnapshots.queueOf(service, arenaId, max);
    }

    private String arenaName(List<DuelMatchSnapshot> matches, int arenaId) {
        if (!matches.isEmpty()) return matches.getFirst().arenaName();
        Optional<KnkArena> arena = spectators.arenas().stream().filter(a -> a.id() == arenaId).findFirst();
        return arena.map(ArenaMenuView::displayName).orElse("Arena");
    }

    private void show(Player player, int arenaId, String arenaName, List<String> lines, DuelMatchSnapshot followed) {
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null) {
            Scoreboard board = newBoard.apply(LEGACY.deserialize(DuelHudView.sidebarTitle(arenaName)));
            viewer = new Viewer(player.getScoreboard(), board, BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE,
                    BossBar.Overlay.PROGRESS));
            viewer.arenaId = arenaId;
            viewers.put(player.getUniqueId(), viewer);
            player.setScoreboard(board);
        } else if (viewer.arenaId != arenaId) {
            viewer.arenaId = arenaId;
        }
        Objective sidebar = viewer.board.getObjective(OBJECTIVE);
        if (sidebar != null) sidebar.displayName(LEGACY.deserialize(DuelHudView.sidebarTitle(arenaName)));
        render(viewer.board, lines);
        if (followed != null) {
            DuelHudView.BossBar view = DuelHudView.bossBar(followed);
            viewer.bar.name(LEGACY.deserialize(view.text()));
            viewer.bar.progress(view.progress());
            viewer.bar.color(color(view.color()));
            player.showBossBar(viewer.bar);
        } else {
            player.hideBossBar(viewer.bar);
        }
    }

    private void stop(UUID playerId) {
        Viewer viewer = viewers.remove(playerId);
        if (viewer == null) return;
        Player player = players.apply(playerId);
        if (player == null) return;
        player.hideBossBar(viewer.bar);
        if (player.getScoreboard() == viewer.board && viewer.previous != null) player.setScoreboard(viewer.previous);
    }

    /** Fixed entry keys with descending scores; the text is each score's custom name (as the siege board does). */
    static void render(Scoreboard board, List<String> lines) {
        Objective sidebar = board.getObjective(OBJECTIVE);
        if (sidebar == null) return;
        int count = Math.min(DuelHudView.MAX_LINES, lines.size());
        for (int i = 0; i < DuelHudView.MAX_LINES; i++) {
            String entry = "line" + i;
            if (i < count) {
                Score score = sidebar.getScore(entry);
                score.setScore(count - i);
                score.customName(LEGACY.deserialize(lines.get(i)));
            } else {
                board.resetScores(entry);
            }
        }
    }

    static BossBar.Color color(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return BossBar.Color.WHITE;
        }
    }
}
