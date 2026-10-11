package net.knightsandkings.knk.paper.regions.access;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.regions.access.RefusalGuard;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.Refusal;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.RegionView;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * Decides and answers every domain AllowEntry/AllowExit question on the game server (KNG-56).
 *
 * <p>The rules live on the WorldGuard regions themselves ({@link DomainAccessFlags}), written there
 * by {@link DomainAccessFlagSync} from the API and saved by WorldGuard - so they are known at
 * startup, at join and while the API is down. Moves are policed by WorldGuard's session machinery
 * through {@link DomainAccessHandler} (walk, glide, swim, ride, embark, teleport); this service adds
 * mounting, respawning and the join check ({@code DomainAccessListener}), the teleport engine's
 * up-front refusal and the shared deny-message throttle and load guard ({@link RefusalGuard}).
 *
 * <p>Bypass: {@code knk.region.bypass} (and a staff teleport carrying it), and WorldGuard's own
 * region bypass ({@code worldguard.region.bypass.<world>}, which ops hold).
 */
public class DomainAccessService {
    private static final Logger LOGGER = Logger.getLogger(DomainAccessService.class.getName());

    /** The regions at a location, as the rules see them for one player. */
    public interface RegionLookup {
        List<RegionView> at(Location location, Player player);

        /** WorldGuard's own region bypass for this player in this location's world. */
        boolean hasWorldGuardBypass(Player player, Location location);
    }

    /** Carries out a load-guard action (main thread). */
    public interface Enforcer {
        void sendToSpawn(Player player);

        void kick(Player player, String reason);
    }

    private final RegionAccessRules rules = new RegionAccessRules();
    private final RegionLookup regions;
    private final RefusalGuard guard;
    private final LongSupplier clock;
    private final Consumer<Runnable> nextTick;
    private volatile Predicate<Player> bypass = player -> false;
    private volatile Enforcer enforcer;
    // Players being moved by the plugin itself to undo or prevent a refused move: never judged.
    private final Set<UUID> exempt = new HashSet<>();

    public DomainAccessService(RegionLookup regions, RefusalGuard guard, LongSupplier clock, Consumer<Runnable> nextTick) {
        this.regions = regions;
        this.guard = guard;
        this.clock = clock;
        this.nextTick = nextTick;
    }

    public void setBypass(Predicate<Player> bypass) {
        this.bypass = bypass != null ? bypass : player -> false;
    }

    public void setEnforcer(Enforcer enforcer) {
        this.enforcer = enforcer;
    }

    public boolean bypasses(Player player, Location where) {
        if (player == null) {
            return false;
        }
        if (exempt.contains(player.getUniqueId())) {
            return true;
        }
        return bypass.test(player) || (where != null && regions.hasWorldGuardBypass(player, where));
    }

    /**
     * A border crossing as WorldGuard reports it: {@code entered}/{@code exited} are the regions
     * entered and left since the player's last allowed position.
     *
     * @param cancellable whether the move can be refused (WorldGuard ignores refusals otherwise)
     * @return the refusal, or empty when the move may happen
     */
    public Optional<Refusal> onCrossing(Player player, Location to, Collection<? extends RegionView> entered,
                                        Collection<? extends RegionView> exited, boolean cancellable) {
        if ((entered == null || entered.isEmpty()) && (exited == null || exited.isEmpty())) {
            return Optional.empty();
        }
        Optional<Refusal> refusal = rules.evaluate(entered, exited);
        if (refusal.isEmpty() || !cancellable || bypasses(player, to)) {
            return Optional.empty();
        }
        refused(player, refusal.get());
        return refusal;
    }

    /**
     * Would moving {@code player} from {@code from} to {@code to} be refused? Judged on the regions
     * at both locations, side-effect free (no message, no load-guard count).
     */
    public Optional<Refusal> preview(Player player, Location from, Location to) {
        if (player == null || to == null || to.getWorld() == null) {
            return Optional.empty();
        }
        Map<String, RegionView> before = byId(from != null && from.getWorld() != null ? regions.at(from, player) : List.of());
        Map<String, RegionView> after = byId(regions.at(to, player));
        // KNG-112: regions of another world are other regions, even with the same id: a move between worlds leaves
        // every region at `from` and enters every region at `to`.
        boolean sameWorld = from != null && from.getWorld() != null && from.getWorld().equals(to.getWorld());
        List<RegionView> entered = after.entrySet().stream()
            .filter(e -> !sameWorld || !before.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        List<RegionView> exited = before.entrySet().stream()
            .filter(e -> !sameWorld || !after.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        if (entered.isEmpty() && exited.isEmpty()) {
            return Optional.empty();
        }
        Optional<Refusal> refusal = rules.evaluate(entered, exited);
        if (refusal.isEmpty() || bypasses(player, to)) {
            return Optional.empty();
        }
        return refusal;
    }

    /** Is {@code location} inside a region whose entry {@code player} is refused (for the join check)? */
    public Optional<Refusal> refusedToBeAt(Player player, Location location) {
        return preview(player, null, location);
    }

    /**
     * Tell the player (throttled) and run the load guard for one refused move, mount or respawn
     * that the caller has already blocked. The message goes to the action bar and, the first time
     * in a refusal episode, to chat as well (KNG-74) - the action bar is shared with other HUDs
     * such as the navigation arrow, which yields to it while {@link #holdsActionBar} is true.
     */
    public void refused(Player player, Refusal refusal) {
        RefusalGuard.Outcome outcome = guard.onRefusal(player.getUniqueId(), clock.getAsLong(), refusal.message());
        if (outcome.showMessage()) {
            Component message = Component.text(refusal.message()).color(ColorOptions.error);
            player.sendActionBar(message);
            if (outcome.showInChat()) {
                player.sendMessage(message);
            }
        }
        Enforcer current = enforcer;
        if (current == null) {
            return;
        }
        switch (outcome.action()) {
            case TELEPORT_TO_SPAWN -> {
                LOGGER.warning("[KnK Access] " + player.getName() + " keeps forcing " + refusal.regionId()
                    + " at a rate no client produces; sending them to spawn");
                nextTick.accept(() -> current.sendToSpawn(player));
            }
            case KICK -> {
                LOGGER.warning("[KnK Access] " + player.getName() + " kept forcing " + refusal.regionId()
                    + " after being sent to spawn; kicking");
                nextTick.accept(() -> current.kick(player, "Too many attempts to enter or leave a restricted area."));
            }
            case NONE -> { }
        }
    }

    /**
     * Run {@code move} (a teleport the plugin makes to undo or prevent a refused move) without it
     * being judged - it may have to take the player out of a domain they may not leave.
     */
    public void exemptWhile(Player player, Runnable move) {
        UUID id = player.getUniqueId();
        boolean added = exempt.add(id);
        try {
            move.run();
        } finally {
            if (added) {
                exempt.remove(id);
            }
        }
    }

    /**
     * Whether a deny message was just put in this player's action bar and should not be overwritten
     * yet by other action-bar users (KNG-74: the navigation HUD's arrow).
     */
    public boolean holdsActionBar(UUID playerId) {
        return playerId != null && guard.holdsActionBar(playerId, clock.getAsLong());
    }

    public void forget(Player player) {
        guard.forget(player.getUniqueId());
    }

    private static Map<String, RegionView> byId(List<RegionView> views) {
        Map<String, RegionView> map = new LinkedHashMap<>();
        for (RegionView view : views) {
            map.put(view.regionId(), view);
        }
        return map;
    }
}
