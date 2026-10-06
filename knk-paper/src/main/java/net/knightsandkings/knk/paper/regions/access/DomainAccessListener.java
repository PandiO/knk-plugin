package net.knightsandkings.knk.paper.regions.access;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import net.knightsandkings.knk.core.regions.access.RegionAccessRules.Refusal;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * The domain AllowEntry/AllowExit cases WorldGuard's session doesn't refuse on its own (KNG-56):
 *
 * <ul>
 *   <li><b>Mounting</b> any entity: the rider ends up where the mount is, so mounting is judged as a
 *       move from the player to the mount - no mounting out of a domain the player may not leave,
 *       nor into one they may not enter. (WorldGuard's embark check covers vehicles only.)</li>
 *   <li><b>Respawning</b>: WorldGuard can't cancel a respawn. A respawn point the player may not
 *       reach from where they died - inside a domain they may not enter (bed, anchor), or outside one
 *       they may not leave - is replaced by the first allowed one of: the world spawn, the highest
 *       block above the death spot, the death spot. WorldGuard's session is then re-synced.</li>
 *   <li><b>Join</b>: a player standing in a domain they may not enter (with the region data already
 *       on disk, so also while the API is down) is sent to the world spawn once their account has
 *       loaded (bypass is known then).</li>
 * </ul>
 */
public final class DomainAccessListener implements Listener {

    private final DomainAccessService access;
    private final Consumer<Player> resyncWorldGuardSession;
    private final Predicate<Player> respawnExempt;
    private final Map<UUID, Location> deathLocations = new ConcurrentHashMap<>();

    /**
     * @param resyncWorldGuardSession run one tick after a respawn: makes WorldGuard's session take
     *     the player's real position as their last allowed one
     * @param respawnExempt players whose respawn point is someone else's to choose (siege participants: the match
     *     respawns them at their team spawn)
     */
    public DomainAccessListener(DomainAccessService access, Consumer<Player> resyncWorldGuardSession,
                                Predicate<Player> respawnExempt) {
        this.access = access;
        this.resyncWorldGuardSession = resyncWorldGuardSession;
        this.respawnExempt = respawnExempt != null ? respawnExempt : player -> false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Entity mount = event.getMount();
        Optional<Refusal> refusal = access.preview(player, player.getLocation(), mount.getLocation());
        if (refusal.isPresent()) {
            event.setCancelled(true);
            access.refused(player, refusal.get());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        deathLocations.put(event.getEntity().getUniqueId(), event.getEntity().getLocation().clone());
    }

    /** HIGHEST: after every plugin that picks a respawn point (respawn town, spawn destination). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location died = deathLocations.remove(player.getUniqueId());
        Location target = event.getRespawnLocation();
        if (died == null || target == null || respawnExempt.test(player)) {
            return;
        }
        Optional<Refusal> refusal = access.preview(player, died, target);
        if (refusal.isEmpty()) {
            return;
        }
        Location replacement = firstAllowed(player, died, List.of(
            () -> died.getWorld() != null ? died.getWorld().getSpawnLocation() : null,
            () -> highestAbove(died),
            () -> died));
        event.setRespawnLocation(replacement);
        player.sendMessage(Component.text(refusal.get().message() + " You respawned elsewhere.").color(ColorOptions.error));
        access.refused(player, refusal.get());
        resyncWorldGuardSession.accept(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUserDataLoaded(UserDataLoadedEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        Optional<Refusal> refusal = access.refusedToBeAt(player, player.getLocation());
        if (refusal.isEmpty()) {
            return;
        }
        player.sendMessage(Component.text(refusal.get().message()).color(ColorOptions.error));
        World world = player.getWorld();
        access.exemptWhile(player, () -> player.teleport(world.getSpawnLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        deathLocations.remove(event.getPlayer().getUniqueId());
        access.forget(event.getPlayer());
    }

    private Location firstAllowed(Player player, Location died, List<Supplier<Location>> candidates) {
        for (Supplier<Location> next : candidates) {
            Location candidate = next.get();
            if (candidate != null && candidate.getWorld() != null && access.preview(player, died, candidate).isEmpty()) {
                return candidate;
            }
        }
        return died;
    }

    private static Location highestAbove(Location died) {
        World world = died.getWorld();
        if (world == null) {
            return null;
        }
        Block top = world.getHighestBlockAt(died);
        if (top == null || top.isLiquid() || !top.isSolid()) {
            return null;
        }
        return top.getLocation().add(0.5, 1, 0.5);
    }
}
