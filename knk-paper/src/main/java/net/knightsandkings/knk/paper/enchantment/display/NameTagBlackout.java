package net.knightsandkings.knk.paper.enchantment.display;

import net.knightsandkings.knk.core.effects.ViewerBlackoutLedger;
import net.knightsandkings.knk.paper.utils.ViewerScoreboards;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hides other players' name tags in one viewer's own view while a custom enchantment's blindness lasts
 * (PandiO/knk-workspace#169; round 1 used flash_chaos, the developer moved it to blindness on 2026-10-27).
 * <p>
 * <b>How:</b> name-tag visibility is a team option, and a player can be in only one team per board, so the players
 * are never re-teamed. Instead the viewer is shown a private <i>mirror</i> of their own board (the shared rank board
 * of {@code ScoreboardUtil} or their siege team's board): the same teams with the same colours, prefixes, suffixes,
 * options and members, the same sidebar and below-name objectives, but every team's name tag set to
 * {@link Team.OptionStatus#NEVER}, plus one catch-all team for online players who are on no team. Nobody else's view
 * changes. The mirror is re-synced from the real board every second, so the siege sidebar keeps ticking.
 * <p>
 * The real board is handed back when the blackout ends, when the effect behind it is gone sooner (the viewer drank
 * milk: see {@link #hide(Player, int, Predicate)}), on death and on quit. While it runs, the board owners set
 * and read the viewer's board through {@link ViewerScoreboards}, which this class answers: a siege match that starts
 * or ends during the blackout swaps the board behind the mirror, which the viewer gets back afterwards. Overlapping
 * blindness hits extend one blackout to the latest end. Main thread.
 */
public final class NameTagBlackout implements Listener, ViewerScoreboards.Overlay {

    /** The catch-all team for online players who are on no team of the real board. */
    static final String CATCH_ALL_TEAM = "knk_blackout";

    private static final Logger LOGGER = Logger.getLogger(NameTagBlackout.class.getName());

    private final ViewerBlackoutLedger ledger = new ViewerBlackoutLedger();
    private final Map<UUID, Scoreboard> underlying = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> mirrors = new ConcurrentHashMap<>();
    /** Whether the effect behind a blackout still runs (checked every second; false ends the blackout early). */
    private final Map<UUID, Predicate<Player>> stillApplies = new ConcurrentHashMap<>();
    private final Supplier<Scoreboard> newBoard;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final LongSupplier clock;

    public NameTagBlackout() {
        this(() -> Bukkit.getScoreboardManager().getNewScoreboard(), Bukkit::getOnlinePlayers, System::currentTimeMillis);
    }

    NameTagBlackout(Supplier<Scoreboard> newBoard, Supplier<Collection<? extends Player>> onlinePlayers, LongSupplier clock) {
        this.newBoard = newBoard;
        this.onlinePlayers = onlinePlayers;
        this.clock = clock;
    }

    /** Starts (or extends) the viewer's blackout for {@code durationTicks}. */
    public void hide(Player viewer, int durationTicks) {
        hide(viewer, durationTicks, null);
    }

    /**
     * Starts (or extends) the viewer's blackout for {@code durationTicks}, ending it sooner once {@code stillApplies}
     * says the effect behind it is gone (e.g. the blindness was cured with milk). Null: only the duration counts.
     */
    public void hide(Player viewer, int durationTicks, Predicate<Player> stillApplies) {
        if (viewer == null || durationTicks <= 0) {
            return;
        }
        UUID id = viewer.getUniqueId();
        boolean started = ledger.start(id, durationTicks * 50L, clock.getAsLong());
        if (ledger.isActive(id)) {
            if (stillApplies != null) {
                this.stillApplies.put(id, stillApplies);
            } else if (started) {
                this.stillApplies.remove(id);
            }
        }
        if (!started) {
            return;
        }
        Scoreboard own = viewer.getScoreboard();
        Scoreboard mirror = newBoard.get();
        underlying.put(id, own);
        mirrors.put(id, mirror);
        sync(mirror, own, onlinePlayers.get());
        viewer.setScoreboard(mirror);
    }

    /** Ends the viewer's blackout and gives them their real board back. */
    public void show(Player viewer) {
        if (viewer == null) {
            return;
        }
        UUID id = viewer.getUniqueId();
        ledger.end(id);
        mirrors.remove(id);
        stillApplies.remove(id);
        Scoreboard own = underlying.remove(id);
        if (own != null && viewer.isOnline()) {
            viewer.setScoreboard(own);
        }
    }

    public boolean isHidden(UUID viewerId) {
        return ledger.isActive(viewerId);
    }

    /** Every second: ends the blackouts that ran out (or whose effect is gone) and re-syncs the mirrors of the rest. */
    public void tick() {
        for (UUID id : ledger.expire(clock.getAsLong())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                show(viewer);
            } else {
                forget(id);
            }
        }
        for (UUID id : ledger.viewers()) {
            Player viewer = Bukkit.getPlayer(id);
            Scoreboard mirror = mirrors.get(id);
            Scoreboard own = underlying.get(id);
            if (viewer == null || mirror == null || own == null) {
                continue;
            }
            Predicate<Player> effect = stillApplies.get(id);
            if (effect != null && !effect.test(viewer)) {
                show(viewer);
                continue;
            }
            try {
                sync(mirror, own, onlinePlayers.get());
            } catch (RuntimeException e) {
                // A board that can't be mirrored must not leave the viewer stuck on a stale one.
                LOGGER.log(Level.WARNING, "Name-tag blackout of " + viewer.getName() + " ended early", e);
                show(viewer);
            }
        }
    }

    /** Plugin disable: everyone gets their real board back. */
    public void showAll() {
        for (UUID id : ledger.viewers()) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                show(viewer);
            } else {
                forget(id);
            }
        }
    }

    @Override
    public boolean redirect(Player player, Scoreboard board) {
        UUID id = player.getUniqueId();
        Scoreboard mirror = mirrors.get(id);
        if (mirror == null || !ledger.isActive(id)) {
            return false;
        }
        underlying.put(id, board);
        sync(mirror, board, onlinePlayers.get());
        return true;
    }

    @Override
    public Scoreboard underlying(Player player, Scoreboard shown) {
        Scoreboard mirror = mirrors.get(player.getUniqueId());
        if (mirror != null && mirror == shown) {
            Scoreboard own = underlying.get(player.getUniqueId());
            return own != null ? own : shown;
        }
        return shown;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        show(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        show(event.getPlayer());
    }

    private void forget(UUID id) {
        ledger.end(id);
        mirrors.remove(id);
        stillApplies.remove(id);
        underlying.remove(id);
    }

    // ==================== Mirroring ====================

    /** Makes {@code mirror} show what {@code own} shows, with every name tag hidden. */
    static void sync(Scoreboard mirror, Scoreboard own, Collection<? extends Player> online) {
        syncTeams(mirror, own, online);
        syncObjectives(mirror, own);
    }

    private static void syncTeams(Scoreboard mirror, Scoreboard own, Collection<? extends Player> online) {
        Set<String> teamNames = new HashSet<>();
        Set<String> teamed = new HashSet<>();
        for (Team source : own.getTeams()) {
            if (CATCH_ALL_TEAM.equals(source.getName())) {
                continue;
            }
            teamNames.add(source.getName());
            Team copy = mirror.getTeam(source.getName());
            if (copy == null) {
                copy = mirror.registerNewTeam(source.getName());
            }
            copyLook(source, copy);
            Set<String> entries = source.getEntries();
            teamed.addAll(entries);
            for (String entry : entries) {
                if (!copy.hasEntry(entry)) {
                    copy.addEntry(entry); // moves it off any other team of the mirror
                }
            }
            for (String entry : new ArrayList<>(copy.getEntries())) {
                if (!entries.contains(entry)) {
                    copy.removeEntry(entry);
                }
            }
        }
        for (Team copy : new ArrayList<>(mirror.getTeams())) {
            if (!CATCH_ALL_TEAM.equals(copy.getName()) && !teamNames.contains(copy.getName())) {
                copy.unregister();
            }
        }

        Team catchAll = mirror.getTeam(CATCH_ALL_TEAM);
        if (catchAll == null) {
            catchAll = mirror.registerNewTeam(CATCH_ALL_TEAM);
        }
        catchAll.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        Set<String> loose = new HashSet<>();
        for (Player other : online) {
            if (!teamed.contains(other.getName())) {
                loose.add(other.getName());
            }
        }
        for (String entry : loose) {
            if (!catchAll.hasEntry(entry)) {
                catchAll.addEntry(entry);
            }
        }
        for (String entry : new ArrayList<>(catchAll.getEntries())) {
            if (!loose.contains(entry)) {
                catchAll.removeEntry(entry);
            }
        }
    }

    private static void copyLook(Team source, Team copy) {
        copy.displayName(source.displayName());
        copy.prefix(source.prefix());
        copy.suffix(source.suffix());
        if (source.hasColor() && source.color() instanceof net.kyori.adventure.text.format.NamedTextColor named) {
            copy.color(named);
        }
        copy.setAllowFriendlyFire(source.allowFriendlyFire());
        copy.setCanSeeFriendlyInvisibles(source.canSeeFriendlyInvisibles());
        copy.setOption(Team.Option.COLLISION_RULE, source.getOption(Team.Option.COLLISION_RULE));
        copy.setOption(Team.Option.DEATH_MESSAGE_VISIBILITY, source.getOption(Team.Option.DEATH_MESSAGE_VISIBILITY));
        copy.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
    }

    /** The objectives on a display slot (sidebar, below name, list), with their scores. */
    private static void syncObjectives(Scoreboard mirror, Scoreboard own) {
        Set<String> shown = new HashSet<>();
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective source = own.getObjective(slot);
            if (source == null) {
                Objective stale = mirror.getObjective(slot);
                if (stale != null) {
                    mirror.clearSlot(slot);
                }
                continue;
            }
            shown.add(source.getName());
            Objective copy = mirror.getObjective(source.getName());
            if (copy != null && !java.util.Objects.equals(copy.getTrackedCriteria(), source.getTrackedCriteria())) {
                copy.unregister();
                copy = null;
            }
            if (copy == null) {
                copy = mirror.registerNewObjective(source.getName(), source.getTrackedCriteria(),
                        source.displayName(), source.getRenderType());
            } else {
                copy.displayName(source.displayName());
                copy.setRenderType(source.getRenderType());
            }
            copy.numberFormat(source.numberFormat());
            if (copy.getDisplaySlot() != slot) {
                copy.setDisplaySlot(slot);
            }
            if (source.isModifiable() && copy.isModifiable()) {
                copyScores(mirror, own, source, copy);
            }
        }
        for (Objective copy : new ArrayList<>(mirror.getObjectives())) {
            if (!shown.contains(copy.getName())) {
                copy.unregister();
            }
        }
    }

    private static void copyScores(Scoreboard mirror, Scoreboard own, Objective source, Objective copy) {
        Set<String> entries = new HashSet<>(own.getEntries());
        entries.addAll(mirror.getEntries());
        for (String entry : entries) {
            Score from = source.getScore(entry);
            Score to = copy.getScore(entry);
            if (!from.isScoreSet()) {
                if (to.isScoreSet()) {
                    to.resetScore();
                }
                continue;
            }
            if (!to.isScoreSet() || to.getScore() != from.getScore()) {
                to.setScore(from.getScore());
            }
            if (!java.util.Objects.equals(to.customName(), from.customName())) {
                to.customName(from.customName());
            }
            if (!java.util.Objects.equals(to.numberFormat(), from.numberFormat())) {
                to.numberFormat(from.numberFormat());
            }
        }
    }
}
