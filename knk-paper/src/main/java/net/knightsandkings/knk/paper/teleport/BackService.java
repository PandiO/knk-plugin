package net.knightsandkings.knk.paper.teleport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.teleport.BackLocationBook;
import net.knightsandkings.knk.core.teleport.TeleportBackSettings;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;

/**
 * {@code /back} (docs/specs/teleport/IMPLEMENTATION_PLAN.md Phase 7; developer decision Q5): back to
 * where you last died - death location only, within {@code teleport.back.expire-seconds} (5 min) of
 * the death, once per death, never after a siege death ({@link BackDeathExclusion}).
 * <p>
 * Deaths are recorded by {@code listeners/BackDeathListener}; {@link #start} claims the death and
 * runs a {@link TeleportPlan#back} through the {@link TeleportService} (warmup, cooldown, combat tag,
 * freeze/region/siege guards, safe spot - a lava or void death spot is refused as unsafe when there's
 * no safe ground within {@code teleport.safe-search-radius}). Arriving uses the death up; any other
 * ending (cancelled warmup, refused, unsafe) gives it back, so the player may try again while it
 * lasts.
 * <p>
 * Everything is recorded, whatever the player's permissions (a death costs no API call); the
 * {@value TeleportNodes#BACK} node is checked by the command, and only its holders are told about
 * {@code /back} when they die. In memory only; a relog keeps the death, a restart forgets it.
 * Main thread only.
 */
public class BackService {

    private static final Logger LOGGER = Logger.getLogger(BackService.class.getName());

    public static final String NO_DEATH = "no-death";
    public static final String IN_PROGRESS = "in-progress";
    public static final String DISABLED = "disabled";

    /** Where a player died - the world by id, not a Bukkit Location (which would pin or lose an unloaded world). */
    public record DeathSpot(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {

        public static DeathSpot of(Location location) {
            World world = location.getWorld();
            return new DeathSpot(world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        }

        public String label() {
            return "death spot " + worldName + " " + (int) Math.floor(x) + "," + (int) Math.floor(y) + "," + (int) Math.floor(z);
        }
    }

    private final TeleportService engine;
    private final Executor mainThread;
    private final TeleportService.PermissionLookup permissions;
    private final Function<UUID, World> worlds;
    private final BackLocationBook<DeathSpot> book;
    private final List<BackDeathExclusion> exclusions = new CopyOnWriteArrayList<>();

    /**
     * @param worlds a loaded world by id, e.g. {@code Bukkit::getWorld}; null when it's gone
     */
    public BackService(TeleportService engine, Executor mainThread, TeleportService.PermissionLookup permissions,
                       Function<UUID, World> worlds) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.book = new BackLocationBook<>(settings().expireSeconds());
    }

    /** Add a rule that keeps a death from giving a {@code /back} (the siege's match deaths). */
    public void registerDeathExclusion(BackDeathExclusion exclusion) {
        exclusions.add(Objects.requireNonNull(exclusion, "exclusion must not be null"));
    }

    public TeleportBackSettings settings() {
        return engine.settings().back();
    }

    public boolean isEnabled() {
        return settings().enabled();
    }

    /**
     * Whether {@code player}'s death, happening now, must not give a {@code /back}. Asked by the death
     * listener before anything else handles the death; a failing exclusion counts as excluded.
     */
    public boolean isExcluded(Player player) {
        for (BackDeathExclusion exclusion : exclusions) {
            try {
                if (exclusion.excludes(player)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                LOGGER.log(Level.SEVERE, "/back death exclusion " + exclusion.getClass().getName() + " failed", ex);
                return true;
            }
        }
        return false;
    }

    /**
     * {@code player} died at {@code location}. An excluded death (siege) records nothing and forgets any
     * older one. Holders of {@value TeleportNodes#BACK} are told how long {@code /back} works.
     */
    public void recordDeath(Player player, Location location, boolean excluded) {
        UUID id = player.getUniqueId();
        if (excluded || !isEnabled() || location == null || location.getWorld() == null) {
            book.clear(id);
            return;
        }
        book.setExpireSeconds(settings().expireSeconds());
        BackLocationBook.Entry<DeathSpot> entry = book.recordDeath(id, DeathSpot.of(location), engine.now());
        CompletableFuture<Boolean> holds;
        try {
            holds = permissions.has(player, TeleportNodes.BACK);
        } catch (RuntimeException ex) {
            holds = CompletableFuture.failedFuture(ex);
        }
        holds.exceptionally(ex -> false).thenAccept(allowed -> mainThread.execute(() -> {
            if (Boolean.TRUE.equals(allowed) && player.isOnline()
                    && book.available(id, engine.now()).map(current -> current.id() == entry.id()).orElse(false)) {
                player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + "/back" + ChatColor.GRAY + " within "
                    + describeSeconds(settings().expireSeconds()) + " to return to where you died.");
            }
        }));
    }

    /**
     * Take {@code player} back to their last death through the engine. Completes on the main thread;
     * without a usable death it is {@code DENIED} with code {@link #NO_DEATH}, {@link #IN_PROGRESS}
     * or {@link #DISABLED}. The caller checks {@value TeleportNodes#BACK} first.
     */
    public CompletableFuture<TeleportOutcome> start(Player player) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(TeleportOutcome.denied(
                TeleportDenial.of(DISABLED, "/back is turned off on this server.")));
        }
        UUID id = player.getUniqueId();
        long now = engine.now();
        Optional<BackLocationBook.Entry<DeathSpot>> claimed = book.claim(id, now);
        if (claimed.isEmpty()) {
            if (book.isClaimed(id)) {
                return CompletableFuture.completedFuture(TeleportOutcome.denied(
                    TeleportDenial.of(IN_PROGRESS, "You're already on your way back.")));
            }
            return CompletableFuture.completedFuture(TeleportOutcome.denied(TeleportDenial.of(NO_DEATH,
                "You have no death location to go back to. /back works for "
                    + describeSeconds(settings().expireSeconds()) + " after you die.")));
        }
        BackLocationBook.Entry<DeathSpot> entry = claimed.get();
        DeathSpot spot = entry.location();
        CompletableFuture<TeleportOutcome> outcome;
        try {
            outcome = engine.start(TeleportPlan.back(player, () -> toLocation(spot), spot.label()));
        } catch (RuntimeException ex) {
            book.release(entry);
            throw ex;
        }
        return outcome.whenComplete((result, ex) -> {
            if (ex == null && result != null && result.isTeleported()) {
                book.consume(entry);
            } else {
                book.release(entry);
            }
        });
    }

    /** Seconds left to start a {@code /back} for {@code player}; 0 when there's no usable death. */
    public int secondsLeft(UUID player) {
        long now = engine.now();
        return book.available(player, now).map(entry -> entry.secondsLeft(now)).orElse(0);
    }

    /** Drop expired deaths (from the engine's heartbeat). */
    public void purgeExpired() {
        book.purgeExpired(engine.now());
    }

    private Location toLocation(DeathSpot spot) {
        World world = worlds.apply(spot.worldId());
        if (world == null) {
            return null;
        }
        return new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }

    /** "5 minutes", "90 seconds", "1 minute". */
    static String describeSeconds(int seconds) {
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}
