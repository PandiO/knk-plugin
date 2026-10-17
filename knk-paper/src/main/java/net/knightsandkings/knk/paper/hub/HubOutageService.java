package net.knightsandkings.knk.paper.hub;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings.OutageAction;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;
import net.knightsandkings.knk.core.hub.HubOutageStore;
import net.knightsandkings.knk.core.hub.HubOutageStore.ReturnRecord;
import net.knightsandkings.knk.core.hub.OutageClock;
import net.knightsandkings.knk.core.hub.OutagePolicy;
import net.knightsandkings.knk.paper.events.ApiConnectivityChangedEvent;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The outage actions (hub DESIGN §2, §10, §12, §13; decisions 2026-10-12). While the API is down, every second each
 * online player outside the hub whose delay has run out gets their action: sent to the hub (kept there for the rest of
 * the outage), kicked, sent once to the spawn of the world they are in, or nothing. A player is exempt while their
 * active staff/owner mode is ticked for one of their groups; leaving the mode during the outage makes the action apply
 * within a second.
 * <p>
 * Before a player is moved or kicked, a Siege round they are in is aborted (decision 6) and their spot is saved as a
 * return record, unless they already have one. After the outage they get a clickable offer to return
 * ({@code /hub return}); nothing returns them automatically. Main thread.
 */
public final class HubOutageService implements Listener {

    private static final Logger LOGGER = Logger.getLogger(HubOutageService.class.getName());

    /** How far around a saved spot the return looks for a safe place to stand (smoke test 2026-10-17). */
    static final int NEARBY_RADIUS = 5;

    static final String KICK_MESSAGE = "The server lost its connection to its services. Please come back later: "
        + "you can then return to where you were.";

    private final HubService hub;
    private final Supplier<KnkGameSettings> settings;
    private final Function<UUID, List<PermissionGroupRef>> groups;
    private final Function<Player, ActiveMode> activeMode;
    private final HubOutageStore store;
    private final BooleanSupplier synced;
    private final Predicate<UUID> releaseFromMinigame;
    private final Consumer<Player> settle;
    private final Transfer transfer;
    private final Clock clock;
    private final OutageClock outage;
    /** Players sent to their world's spawn in this outage: that action runs once per outage. */
    private final Set<UUID> sentToWorldSpawn = new HashSet<>();
    private Supplier<Collection<? extends Player>> online = Bukkit::getOnlinePlayers;
    private Function<String, World> worlds = Bukkit::getWorld;
    private BiFunction<World, Location, Optional<Location>> safeSpot = HubOutageService::safeSpotNear;
    private Runnable onRecovery = () -> { };
    /** The spawn of the town, district or structure at a spot (innermost first), for a saved spot with nothing safe nearby. */
    private Function<Location, CompletableFuture<Optional<KnkLocation>>> domainSpawn =
        at -> CompletableFuture.completedFuture(Optional.empty());
    private Executor mainThread = Runnable::run;
    /** Players being returned to their spot now: that teleport out of the hub is not "leaving" the offer. */
    private final Set<UUID> returning = new HashSet<>();
    /** The spools are read at most every few seconds while recovering (it parses their files). */
    private int ticksUntilSyncCheck;

    /**
     * @param groups              a player's groups in precedence order (empty when unknown)
     * @param synced              every durable spool is empty (the backlog is synced)
     * @param releaseFromMinigame frees the player from a Siege round (aborting it); false = not now, try later
     * @param settle              after the plugin moved a player into another world: that world's game mode, as on a join
     * @param transfer            moves a player on the plugin's behalf (exit pass, WorldGuard resync)
     */
    public HubOutageService(HubService hub, Supplier<KnkGameSettings> settings, Function<UUID, List<PermissionGroupRef>> groups,
                            Function<Player, ActiveMode> activeMode, HubOutageStore store, BooleanSupplier synced,
                            Predicate<UUID> releaseFromMinigame, Consumer<Player> settle, Transfer transfer, Clock clock) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.activeMode = Objects.requireNonNull(activeMode, "activeMode");
        this.store = Objects.requireNonNull(store, "store");
        this.synced = Objects.requireNonNull(synced, "synced");
        this.releaseFromMinigame = Objects.requireNonNull(releaseFromMinigame, "releaseFromMinigame");
        this.settle = Objects.requireNonNull(settle, "settle");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outage = new OutageClock(store.outageStartedAt().orElse(null));
    }

    /** Tests: the online players, loaded worlds and the safe-spot search without a server. */
    void useForTests(Supplier<Collection<? extends Player>> online, Function<String, World> worlds,
                     BiFunction<World, Location, Optional<Location>> safeSpot) {
        this.online = online;
        this.worlds = worlds;
        this.safeSpot = safeSpot;
    }

    /**
     * Where a return goes when nothing near the saved spot is safe: the spawn of the domain the spot is in, looked up
     * off the main thread and finished on {@code mainThread}.
     */
    public void setDomainSpawn(Function<Location, CompletableFuture<Optional<KnkLocation>>> domainSpawn, Executor mainThread) {
        this.domainSpawn = Objects.requireNonNull(domainSpawn, "domainSpawn");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    /** Runs when the API comes back after an outage (main thread), e.g. restarting the Siege lobbies it stopped. */
    public void setOnRecovery(Runnable onRecovery) {
        this.onRecovery = onRecovery != null ? onRecovery : () -> { };
    }

    /**
     * The saved spot, or the nearest safe one within {@link #NEARBY_RADIUS} blocks of it (standing on a slab, a path or
     * farmland puts the feet inside that block; a jump or a fall saves a spot just above the ground; a block placed or
     * lava flowing there since). Empty when there is none.
     */
    static Optional<Location> safeSpotNear(World world, Location at) {
        var probe = new net.knightsandkings.knk.paper.teleport.BukkitBlockProbe(world);
        return net.knightsandkings.knk.core.teleport.SafeLocationFinder.find(probe, at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                NEARBY_RADIUS)
            .map(spot -> spot.x() == at.getBlockX() && spot.y() == at.getBlockY() && spot.z() == at.getBlockZ()
                ? at
                : new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5, at.getYaw(), at.getPitch()));
    }

    /** Whether the API is down now (outage rules apply). */
    public boolean inOutage() {
        return outage.inOutage();
    }

    public OutageClock.Phase phase() {
        return outage.phase();
    }

    public Optional<Instant> outageStartedAt() {
        return outage.startedAt();
    }

    /** Whether this player is exempt from the outage rules now. */
    public boolean exempt(Player player) {
        return OutagePolicy.exempt(settings.get(), groups.apply(player.getUniqueId()), activeMode.apply(player));
    }

    /** Whether this player is exempt from the outage rules while joining (their persisted mode). */
    public boolean exemptOnJoin(UUID player, ActiveMode persistedMode) {
        return OutagePolicy.exempt(settings.get(), groups.apply(player), persistedMode);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectivity(ApiConnectivityChangedEvent event) {
        connectivityChanged(event.getNewState(), event.getTransition().firstFailureAt());
    }

    /** A KNG-115 state, from the change event or the state already known at enable; repeats are ignored. */
    public void connectivityChanged(ApiConnectivityState state) {
        connectivityChanged(state, null);
    }

    /**
     * {@link #connectivityChanged(ApiConnectivityState)}, with when the API was lost: the first failed probe. A new
     * outage's timer starts there, not when the probes confirmed it some 20-30 s later (smoke test 2026-10-17), so
     * the delays count from the loss of the API.
     */
    public void connectivityChanged(ApiConnectivityState state, Instant lostAt) {
        Instant now = clock.instant();
        if (state == ApiConnectivityState.DOWN) {
            if (outage.inOutage()) {
                return;
            }
            if (outage.down(lostAt != null && lostAt.isBefore(now) ? lostAt : now)) {
                store.setOutageStartedAt(outage.startedAt().orElse(now));
                endEarlierOffersInHub(outage.startedAt().orElse(now));
                LOGGER.warning("[KnK Hub] API outage started; outage actions apply after each player's delay");
            } else {
                LOGGER.warning("[KnK Hub] API down again before the backlog was synced; the outage timer continues from "
                    + outage.startedAt().map(Instant::toString).orElse("?"));
            }
            tick();
        } else if (state == ApiConnectivityState.UP) {
            boolean wasOutage = outage.phase() != OutageClock.Phase.HEALTHY;
            outage.up(now);
            if (wasOutage) {
                LOGGER.info("[KnK Hub] API back; the outage timer resets once the backlog is synced");
                onRecovery.run();
                for (Player player : List.copyOf(online.get())) {
                    offerReturn(player);
                }
            }
        }
    }

    /**
     * A new outage: a player in the hub now who still has the offer of an earlier outage chose to stay, so that offer
     * ends. Otherwise it came back after this outage as if this one had moved them (smoke test 2026-10-17). Offline
     * players keep theirs: they may not have seen it yet.
     */
    private void endEarlierOffersInHub(Instant outageStart) {
        for (Player player : List.copyOf(online.get())) {
            store.returnRecord(player.getUniqueId())
                .filter(record -> record.savedAt().isBefore(outageStart) && hub.inHub(player.getLocation()))
                .ifPresent(record -> store.remove(player.getUniqueId()));
        }
    }

    /** Every second: a full recovery resets the timer; during an outage, the due actions run. */
    public void tick() {
        Instant now = clock.instant();
        if (outage.tick(now, this::syncedNow)) {
            store.setOutageStartedAt(null);
            sentToWorldSpawn.clear();
            LOGGER.info("[KnK Hub] Backlog synced after the outage; the outage timer is reset");
        }
        if (!outage.inOutage() || !hub.configured()) {
            return;
        }
        for (Player player : List.copyOf(online.get())) {
            applyIfDue(player, now);
        }
    }

    /** Whether the backlog is synced, asked at most every 5 seconds (the spools are parsed from disk). */
    private boolean syncedNow() {
        if (ticksUntilSyncCheck-- > 0) {
            return false;
        }
        ticksUntilSyncCheck = 4;
        return synced.getAsBoolean();
    }

    void applyIfDue(Player player, Instant now) {
        KnkGameSettings current = settings.get();
        OutagePolicy.Applicable applicable = OutagePolicy.applicable(current, groups.apply(player.getUniqueId()));
        if (applicable.action() == OutageAction.NONE || !outage.due(now, applicable.delaySeconds())) {
            return;
        }
        if (hub.inHub(player.getLocation()) || exempt(player)) {
            return;
        }
        switch (applicable.action()) {
            case SEND_TO_HUB -> sendToHub(player, now);
            case KICK -> kick(player, now, KICK_MESSAGE);
            case SEND_TO_WORLD_SPAWN -> sendToWorldSpawn(player, now);
            default -> { }
        }
    }

    private void sendToHub(Player player, Instant now) {
        Optional<Location> spawn = hub.admissionSpawn();
        if (spawn.isEmpty()) {
            if (player.isOp()) {
                return; // operators may join while the hub is unusable, to fix it; don't kick them out again
            }
            // §13: no usable hub - kick with a clear message rather than leave them in unprotected gameplay.
            kick(player, now, "The server lost its connection to its services and its hub is not available. "
                + "Please come back later.");
            return;
        }
        if (!prepare(player, now)) {
            return;
        }
        if (transfer.move(player, spawn.get())) {
            settle.accept(player);
            player.sendMessage(Component.text("The server lost its connection to its services, so you were moved to the "
                + "hub. Your spot is saved: you can return to it once the services are back.").color(ColorOptions.error));
            LOGGER.info("[KnK Hub] Outage: moved " + player.getName() + " to the hub");
        }
    }

    private void kick(Player player, Instant now, String message) {
        if (!prepare(player, now)) {
            return;
        }
        LOGGER.info("[KnK Hub] Outage: kicked " + player.getName());
        player.kick(Component.text(message));
    }

    private void sendToWorldSpawn(Player player, Instant now) {
        if (sentToWorldSpawn.contains(player.getUniqueId())) {
            return;
        }
        World world = player.getWorld();
        if (!prepare(player, now)) {
            return;
        }
        if (transfer.move(player, world.getSpawnLocation())) {
            sentToWorldSpawn.add(player.getUniqueId());
            player.sendMessage(Component.text("The server lost its connection to its services, so you were moved to this "
                + "world's spawn. Your spot is saved: you can return to it once the services are back.").color(ColorOptions.error));
            LOGGER.info("[KnK Hub] Outage: moved " + player.getName() + " to the spawn of " + world.getName());
        }
    }

    /** Frees the player from a minigame and saves their spot. False = not now (a match is ending). */
    private boolean prepare(Player player, Instant now) {
        if (!releaseFromMinigame.test(player.getUniqueId())) {
            return false;
        }
        saveReturn(player, now);
        return true;
    }

    /**
     * Saves where the player is now as their return spot. A spot saved earlier in this outage wins; one from an earlier
     * outage is replaced. Never a spot in the hub (a Siege member restored to where they queued, for example).
     */
    void saveReturn(Player player, Instant now) {
        Location at = player.getLocation();
        if (at.getWorld() == null || hub.inHub(at)) {
            return;
        }
        store.save(new ReturnRecord(player.getUniqueId(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
            at.getYaw(), at.getPitch(), now), store.outageStartedAt().orElse(null));
    }

    /**
     * A join placed in the hub because of the outage, away from where they logged out: that spot is their return spot.
     * Called from the asynchronous spawn-location event; the store is thread-safe.
     */
    public void savedOnJoin(UUID player, Location loggedOutAt) {
        if (loggedOutAt == null || loggedOutAt.getWorld() == null || hub.inHub(loggedOutAt)) {
            return;
        }
        store.save(new ReturnRecord(player, loggedOutAt.getWorld().getName(), loggedOutAt.getX(), loggedOutAt.getY(),
            loggedOutAt.getZ(), loggedOutAt.getYaw(), loggedOutAt.getPitch(), clock.instant()),
            store.outageStartedAt().orElse(null));
    }

    /** Once the account is loaded: the return offer, or while the API is still down, that the spot is kept. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUserDataLoaded(UserDataLoadedEvent event) {
        Player player = event.getPlayer();
        if (player != null && player.isOnline()) {
            offerReturn(player);
        }
    }

    /** Moves a player across hub borders on the plugin's behalf (scoped past the hub's AllowExit); false = it failed. */
    @FunctionalInterface
    public interface Transfer {
        boolean move(Player player, Location to);
    }

    /** Why a player may not be at a spot now (domain AllowEntry), or empty. */
    @FunctionalInterface
    public interface Access {
        Optional<String> refusal(Player player, Location at);
    }

    /** Why a player may not leave for their saved spot now (frozen, in a match), or empty. */
    @FunctionalInterface
    public interface Blocker {
        Optional<String> reason(Player player);
    }

    /** What {@code /hub return} did; LOOKING_UP: the spot is unsafe and the domain spawn is being looked up. */
    public enum ReturnOutcome { RETURNED, NO_RECORD, STALE, SERVICES_DOWN, BLOCKED, INVALIDATED, FAILED, LOOKING_UP }

    /**
     * {@code /hub return <token>} (§12): back to the saved spot, only after the player chose it, with the API up, and
     * only for the current record ({@code token} is its save time, so an old or repeated click does nothing). A spot in a
     * missing world or one the player may no longer enter removes the record with a message. An unsafe spot is replaced
     * by the nearest safe place within {@link #NEARBY_RADIUS} blocks, else by the spawn of the town or district it is in
     * (smoke test 2026-10-17); with neither, the record is kept for later. The record is consumed only after a
     * successful teleport; a frozen player or a match member can't use it.
     */
    public ReturnOutcome returnTo(Player player, long token, Access access, Blocker blocker) {
        Optional<ReturnRecord> found = store.returnRecord(player.getUniqueId());
        if (found.isEmpty()) {
            player.sendMessage(Component.text("You have no saved spot to return to.").color(ColorOptions.error));
            return ReturnOutcome.NO_RECORD;
        }
        ReturnRecord record = found.get();
        if (record.savedAt().toEpochMilli() != token) {
            player.sendMessage(Component.text("That return offer is out of date.").color(ColorOptions.error));
            return ReturnOutcome.STALE;
        }
        if (outage.phase() == OutageClock.Phase.OUTAGE) {
            player.sendMessage(Component.text("The server's services are not back yet. Your spot stays saved.")
                .color(ColorOptions.error));
            return ReturnOutcome.SERVICES_DOWN;
        }
        Optional<String> blocked = blocker.reason(player);
        if (blocked.isPresent()) {
            player.sendMessage(Component.text(blocked.get() + " Your spot stays saved.").color(ColorOptions.error));
            return ReturnOutcome.BLOCKED;
        }
        World world = worlds.apply(record.world());
        if (world == null) {
            return invalidate(player, "Your saved spot is in a world that is not available.");
        }
        Location saved = new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch());
        Optional<Location> safe = safeSpot.apply(world, saved);
        if (safe.isPresent()) {
            return finishReturn(player, safe.get(), access, "Welcome back.");
        }
        domainSpawn.apply(saved).whenComplete((spawn, error) -> mainThread.execute(() ->
            returnToDomainSpawn(player, token, error == null && spawn != null ? spawn : Optional.empty(), access, blocker)));
        return ReturnOutcome.LOOKING_UP;
    }

    /** The saved spot had nothing safe nearby: the spawn of the domain it is in, if the offer still holds. */
    private void returnToDomainSpawn(Player player, long token, Optional<KnkLocation> spawn, Access access, Blocker blocker) {
        if (!player.isOnline()) {
            return;
        }
        Optional<ReturnRecord> record = store.returnRecord(player.getUniqueId());
        if (record.isEmpty() || record.get().savedAt().toEpochMilli() != token || outage.phase() == OutageClock.Phase.OUTAGE
                || blocker.reason(player).isPresent()) {
            return; // used, replaced or blocked meanwhile: the click is simply out of date
        }
        Optional<Location> safe = spawn.flatMap(this::toLoaded).flatMap(at -> safeSpot.apply(at.getWorld(), at));
        if (safe.isEmpty()) {
            player.sendMessage(Component.text("Your saved spot is not safe to stand on right now. It stays saved; try "
                + "again later.").color(ColorOptions.error));
            return;
        }
        finishReturn(player, safe.get(), access, "Your spot was not safe to stand on, so you were taken to the spawn of "
            + "the place it is in.");
    }

    private Optional<Location> toLoaded(KnkLocation spot) {
        World world = spot.world() != null ? worlds.apply(spot.world()) : null;
        if (world == null || spot.x() == null || spot.y() == null || spot.z() == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw() != null ? spot.yaw() : 0f,
            spot.pitch() != null ? spot.pitch() : 0f));
    }

    private ReturnOutcome finishReturn(Player player, Location target, Access access, String welcome) {
        Optional<String> refused = access.refusal(player, target);
        if (refused.isPresent()) {
            return invalidate(player, "You may no longer go to your saved spot: " + refused.get());
        }
        boolean moved;
        returning.add(player.getUniqueId());
        try {
            moved = transfer.move(player, target);
        } finally {
            returning.remove(player.getUniqueId());
        }
        if (!moved) {
            player.sendMessage(Component.text("The return failed. Your spot stays saved; try again.").color(ColorOptions.error));
            return ReturnOutcome.FAILED;
        }
        store.remove(player.getUniqueId());
        settle.accept(player);
        player.sendMessage(Component.text(welcome).color(ColorOptions.message));
        LOGGER.info("[KnK Hub] " + player.getName() + " returned to their spot from before the outage");
        return ReturnOutcome.RETURNED;
    }

    private ReturnOutcome invalidate(Player player, String why) {
        store.remove(player.getUniqueId());
        player.sendMessage(Component.text(why + " The return offer was removed.").color(ColorOptions.error));
        return ReturnOutcome.INVALIDATED;
    }

    /** A voluntary gameplay entry (the hub portal) ends the return offer (§12, §13). */
    public void enteredGameplay(Player player) {
        store.remove(player.getUniqueId());
    }

    /** Leaving the hub any other way once the API is up (walking out, /spawn, /warp) also ends the return offer. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        leftHub(event.getPlayer(), from, to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        leftHub(event.getPlayer(), event.getFrom(), event.getTo());
    }

    void leftHub(Player player, Location from, Location to) {
        if (outage.inOutage() || returning.contains(player.getUniqueId()) || store.returnRecord(player.getUniqueId()).isEmpty()) {
            return;
        }
        if (hub.inHub(from) && !hub.inHub(to)) {
            store.remove(player.getUniqueId());
            player.sendMessage(Component.text("You left the hub, so the offer to return to your spot from before the "
                + "outage has ended.").color(ColorOptions.message));
        }
    }

    /** Sends the return offer (API up) or the "your spot is saved" notice (API down), when the player has a record. */
    public void offerReturn(Player player) {
        Optional<ReturnRecord> record = store.returnRecord(player.getUniqueId());
        if (record.isEmpty()) {
            return;
        }
        if (outage.inOutage()) {
            player.sendMessage(Component.text("Your spot from before the outage is saved. You can return to it once the "
                + "server's services are back.").color(ColorOptions.message));
            return;
        }
        ReturnRecord r = record.get();
        String command = "/hub return " + r.savedAt().toEpochMilli();
        player.sendMessage(Component.text("The server's services are back. ").color(ColorOptions.message)
            .append(Component.text("[Return to where you were]")
                .color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Back to your spot from before the outage"))))
            .append(Component.text(" Or stay; the offer stays until you use it or enter the game another way.")
                .color(ColorOptions.message)));
    }
}
