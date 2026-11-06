package net.knightsandkings.knk.paper.visibility;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.core.visibility.PlayerVisibilityRules;
import net.knightsandkings.knk.core.visibility.PlayerVisibilityRules.Verdict;

/**
 * The plugin's one player-visibility arbiter (Siege Survival link S3, PandiO/knk-workspace#41): the only place that
 * calls {@link Player#hidePlayer} / {@link Player#showPlayer}. It applies {@link PlayerVisibilityRules} per
 * viewer/target pair, combining owner/staff vanish ({@code ModeService}, which owns this arbiter and routes every
 * visibility refresh through it) with the Survival <b>ghost</b> state (DESIGN §3b, S20):
 * <ul>
 *   <li>{@link #markGhost}: the player is hidden from everyone who is not a living or ghost member of the given
 *       team and made translucent to teammates (vanilla Invisibility without particles; the siege boards already
 *       turn on see-friendly-invisibles for the viewer's own team only, {@code SiegeScoreboardPresenter}). Nothing
 *       in {@code ModeService} - a join, a permission change, leaving a mode - un-hides a ghost: every refresh
 *       re-applies the rule, and the ghost reason wins.</li>
 *   <li>{@link #clearGhost}: the Invisibility is removed and the player is shown again, subject to the vanish rule.</li>
 * </ul>
 * Without ghosts the outcomes are exactly the pre-S3 {@code ModeService} ones (regression-tested): a non-vanished
 * target is shown, a vanished one hidden and then shown to viewers whose staff/owner check resolves true.
 * <p>
 * Main thread only. Spectator mode, the invisibility enchantment ability's equipment packets and siege team colours
 * are not this arbiter's business: they never call {@code hidePlayer}.
 */
public final class PlayerVisibilityArbiter {

    private static final Logger LOGGER = Logger.getLogger(PlayerVisibilityArbiter.class.getName());

    private final Plugin plugin;
    private final Predicate<Player> vanished;
    private final Function<Player, CompletableFuture<Boolean>> canSeeVanished;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final BooleanSupplier primaryThread;
    private final Consumer<Runnable> runOnMain;
    private final Consumer<Player> applyTranslucency;
    private final Consumer<Player> removeTranslucency;
    private final Map<UUID, GhostTeam> ghosts = new ConcurrentHashMap<>();

    /**
     * The production arbiter.
     *
     * @param vanished       whether a target is in owner/staff mode ({@code ModeService::isVanished})
     * @param canSeeVanished whether a viewer may see vanished players (the staff/owner permission check; async-safe)
     */
    public static PlayerVisibilityArbiter bukkit(Plugin plugin, Predicate<Player> vanished,
                                                 Function<Player, CompletableFuture<Boolean>> canSeeVanished) {
        return new PlayerVisibilityArbiter(plugin, vanished, canSeeVanished, Bukkit::getOnlinePlayers, Bukkit::isPrimaryThread,
                task -> Bukkit.getScheduler().runTask(plugin, task),
                player -> player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION,
                        0, false, false, false)),
                player -> player.removePotionEffect(PotionEffectType.INVISIBILITY));
    }

    /**
     * @param onlinePlayers      the players to refresh against ({@code Bukkit::getOnlinePlayers})
     * @param primaryThread      whether the caller is on the main thread (an already-resolved check applies inline)
     * @param runOnMain          schedules a task on the main thread
     * @param applyTranslucency  makes a ghost see-through for its teammates (vanilla Invisibility, no particles)
     * @param removeTranslucency undoes it
     */
    PlayerVisibilityArbiter(Plugin plugin, Predicate<Player> vanished, Function<Player, CompletableFuture<Boolean>> canSeeVanished,
                            Supplier<Collection<? extends Player>> onlinePlayers, BooleanSupplier primaryThread,
                            Consumer<Runnable> runOnMain, Consumer<Player> applyTranslucency, Consumer<Player> removeTranslucency) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.vanished = Objects.requireNonNull(vanished, "vanished");
        this.canSeeVanished = Objects.requireNonNull(canSeeVanished, "canSeeVanished");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.primaryThread = Objects.requireNonNull(primaryThread, "primaryThread");
        this.runOnMain = Objects.requireNonNull(runOnMain, "runOnMain");
        this.applyTranslucency = Objects.requireNonNull(applyTranslucency, "applyTranslucency");
        this.removeTranslucency = Objects.requireNonNull(removeTranslucency, "removeTranslucency");
    }

    // ==================== Ghosts (Survival DESIGN §3b; marked and cleared by the Survival runtime, link S5) ====================

    /**
     * The player becomes a ghost of {@code team}: hidden from every online player the team doesn't include, translucent
     * to the team's members, and kept so through every later refresh until {@link #clearGhost}. Marking an existing
     * ghost again only updates its team. Main thread.
     */
    public void markGhost(Player player, GhostTeam team) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(team, "team");
        GhostTeam previous = ghosts.put(player.getUniqueId(), team);
        if (previous == null) {
            safely("translucency", () -> applyTranslucency.accept(player));
        }
        refreshVisibilityOf(player);
    }

    /** The ghost state ends (intermission respawn, match end, restore): shown again under the remaining rules. Main thread. */
    public void clearGhost(Player player) {
        Objects.requireNonNull(player, "player");
        if (ghosts.remove(player.getUniqueId()) == null) {
            return;
        }
        safely("translucency", () -> removeTranslucency.accept(player));
        refreshVisibilityOf(player);
    }

    public boolean isGhost(UUID playerId) {
        return playerId != null && ghosts.containsKey(playerId);
    }

    public Optional<GhostTeam> ghostTeamOf(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(ghosts.get(playerId));
    }

    /** The current ghosts (a snapshot). */
    public Set<UUID> ghosts() {
        return Set.copyOf(ghosts.keySet());
    }

    /** The player left the server: nothing to show or hide any more. */
    public void forget(Player player) {
        if (player != null) {
            ghosts.remove(player.getUniqueId());
        }
    }

    // ==================== Refreshes (the ModeService entry points) ====================

    /** Re-evaluate how every other online player sees {@code target} (main thread). */
    public void refreshVisibilityOf(Player target) {
        for (Player viewer : onlinePlayers.get()) {
            if (!viewer.equals(target)) {
                apply(viewer, target);
            }
        }
    }

    /** Re-evaluate what {@code viewer} sees of every other online player (main thread) - a join, a permission change. */
    public void refreshVisibilityFor(Player viewer) {
        for (Player target : onlinePlayers.get()) {
            if (!target.equals(viewer)) {
                apply(viewer, target);
            }
        }
    }

    /**
     * Apply the rule to one pair (main thread): the verdict's first state at once, and for
     * {@link Verdict#HIDDEN_UNLESS_STAFF} the reveal once the viewer's check resolves true - re-checked then, as either
     * player may have left, the target may have left their mode or become a ghost meanwhile.
     */
    public void apply(Player viewer, Player target) {
        Verdict verdict = verdict(viewer, target);
        switch (verdict) {
            case VISIBLE -> viewer.showPlayer(plugin, target);
            case HIDDEN -> viewer.hidePlayer(plugin, target);
            case HIDDEN_UNLESS_STAFF -> {
                viewer.hidePlayer(plugin, target);
                onMain(canSeeVanished.apply(viewer), allowed -> {
                    if (allowed && viewer.isOnline() && target.isOnline() && verdict(viewer, target) == Verdict.HIDDEN_UNLESS_STAFF) {
                        viewer.showPlayer(plugin, target);
                    }
                });
            }
        }
    }

    /** The rule's verdict for this pair, before any permission check. */
    public Verdict verdict(Player viewer, Player target) {
        return PlayerVisibilityRules.decide(ghosts.get(target.getUniqueId()), viewer.getUniqueId(), vanished.test(target));
    }

    private void onMain(CompletableFuture<Boolean> future, Consumer<Boolean> consumer) {
        if (future.isDone() && primaryThread.getAsBoolean()) {
            // Ops and already-resolved checks: apply inline, avoiding a hide-then-show flicker.
            consumer.accept(future.getNow(false));
            return;
        }
        future.exceptionally(ex -> false).thenAccept(value -> runOnMain.accept(() -> consumer.accept(value)));
    }

    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Ghost " + what + " failed", e);
        }
    }
}
