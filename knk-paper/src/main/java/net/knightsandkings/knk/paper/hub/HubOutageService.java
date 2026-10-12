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
import java.util.function.BiPredicate;
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

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
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

    static final String KICK_MESSAGE = "The server lost its connection to its services. Please come back later: "
        + "you can then return to where you were.";

    private final HubService hub;
    private final Supplier<KnkGameSettings> settings;
    private final Function<UUID, List<PermissionGroupRef>> groups;
    private final Function<Player, ActiveMode> activeMode;
    private final HubOutageStore store;
    private final BooleanSupplier synced;
    private final Predicate<UUID> releaseFromMinigame;
    private final Consumer<Player> arrivedInHub;
    private final Clock clock;
    private final OutageClock outage;
    /** Players sent to their world's spawn in this outage: that action runs once per outage. */
    private final Set<UUID> sentToWorldSpawn = new HashSet<>();
    private Supplier<Collection<? extends Player>> online = Bukkit::getOnlinePlayers;
    private Function<String, World> worlds = Bukkit::getWorld;
    private BiPredicate<World, Location> safe = (world, at) -> net.knightsandkings.knk.core.teleport.SafeLocationFinder.isSafe(
        new net.knightsandkings.knk.paper.teleport.BukkitBlockProbe(world), at.getBlockX(), at.getBlockY(), at.getBlockZ());

    /**
     * @param groups              a player's groups in precedence order (empty when unknown)
     * @param synced              every durable spool is empty (the backlog is synced)
     * @param releaseFromMinigame frees the player from a Siege round (aborting it); false = not now, try later
     * @param arrivedInHub        after an evacuation into the hub: the hub world's game mode, as on a join
     */
    public HubOutageService(HubService hub, Supplier<KnkGameSettings> settings, Function<UUID, List<PermissionGroupRef>> groups,
                            Function<Player, ActiveMode> activeMode, HubOutageStore store, BooleanSupplier synced,
                            Predicate<UUID> releaseFromMinigame, Consumer<Player> arrivedInHub, Clock clock) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.activeMode = Objects.requireNonNull(activeMode, "activeMode");
        this.store = Objects.requireNonNull(store, "store");
        this.synced = Objects.requireNonNull(synced, "synced");
        this.releaseFromMinigame = Objects.requireNonNull(releaseFromMinigame, "releaseFromMinigame");
        this.arrivedInHub = Objects.requireNonNull(arrivedInHub, "arrivedInHub");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outage = new OutageClock(store.outageStartedAt().orElse(null));
    }

    /** Tests: the online players, loaded worlds and the safe-spot check without a server. */
    void useForTests(Supplier<Collection<? extends Player>> online, Function<String, World> worlds,
                     BiPredicate<World, Location> safe) {
        this.online = online;
        this.worlds = worlds;
        this.safe = safe;
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
        connectivityChanged(event.getNewState());
    }

    /** A KNG-115 state, from the change event or the state already known at enable; repeats are ignored. */
    public void connectivityChanged(ApiConnectivityState state) {
        Instant now = clock.instant();
        if (state == ApiConnectivityState.DOWN) {
            if (outage.inOutage()) {
                return;
            }
            if (outage.down(now)) {
                store.setOutageStartedAt(outage.startedAt().orElse(now));
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
                for (Player player : List.copyOf(online.get())) {
                    offerReturn(player);
                }
            }
        }
    }

    /** Every second: a full recovery resets the timer; during an outage, the due actions run. */
    public void tick() {
        Instant now = clock.instant();
        if (outage.tick(now, synced.getAsBoolean())) {
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
            // §13: no usable hub - kick with a clear message rather than leave them in unprotected gameplay.
            kick(player, now, "The server lost its connection to its services and its hub is not available. "
                + "Please come back later.");
            return;
        }
        if (!prepare(player, now)) {
            return;
        }
        if (player.teleport(spawn.get())) {
            arrivedInHub.accept(player);
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
        sentToWorldSpawn.add(player.getUniqueId());
        if (player.teleport(world.getSpawnLocation())) {
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

    /** Saves where the player is now as their return spot, unless they already have one (the original wins). */
    void saveReturn(Player player, Instant now) {
        Location at = player.getLocation();
        if (at.getWorld() == null) {
            return;
        }
        store.saveIfAbsent(new ReturnRecord(player.getUniqueId(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
            at.getYaw(), at.getPitch(), now));
    }

    /**
     * A join placed in the hub because of the outage, away from where they logged out: that spot is their return spot.
     * Called from the asynchronous spawn-location event; the store is thread-safe.
     */
    public void savedOnJoin(UUID player, Location loggedOutAt) {
        if (loggedOutAt == null || loggedOutAt.getWorld() == null || hub.inHub(loggedOutAt)) {
            return;
        }
        store.saveIfAbsent(new ReturnRecord(player, loggedOutAt.getWorld().getName(), loggedOutAt.getX(), loggedOutAt.getY(),
            loggedOutAt.getZ(), loggedOutAt.getYaw(), loggedOutAt.getPitch(), clock.instant()));
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

    /** What {@code /hub return} did. */
    public enum ReturnOutcome { RETURNED, NO_RECORD, STALE, SERVICES_DOWN, INVALIDATED, FAILED }

    /**
     * {@code /hub return <token>} (§12): back to the saved spot, only after the player chose it, with the API up, and
     * only for the current record ({@code token} is its save time, so an old or repeated click does nothing). A spot in a
     * missing world, an unsafe spot or one the player may no longer enter removes the record with a message; it never
     * sends them somewhere else. The record is consumed only after a successful teleport.
     */
    public ReturnOutcome returnTo(Player player, long token, Access access, Transfer transfer) {
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
        World world = worlds.apply(record.world());
        if (world == null) {
            return invalidate(player, "Your saved spot is in world '" + record.world() + "', which is not available.");
        }
        Location target = new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch());
        if (!safe.test(world, target)) {
            return invalidate(player, "Your saved spot is no longer safe to stand on.");
        }
        Optional<String> refused = access.refusal(player, target);
        if (refused.isPresent()) {
            return invalidate(player, "You may no longer go to your saved spot: " + refused.get());
        }
        if (!transfer.move(player, target)) {
            player.sendMessage(Component.text("The return failed. Your spot stays saved; try again.").color(ColorOptions.error));
            return ReturnOutcome.FAILED;
        }
        store.remove(player.getUniqueId());
        player.sendMessage(Component.text("Welcome back.").color(ColorOptions.message));
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
                .hoverEvent(HoverEvent.showText(Component.text(r.world() + " " + Math.round(r.x()) + ", "
                    + Math.round(r.y()) + ", " + Math.round(r.z())))))
            .append(Component.text(" Or stay; the offer stays until you use it or enter the game another way.")
                .color(ColorOptions.message)));
    }
}
