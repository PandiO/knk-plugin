package net.knightsandkings.knk.paper.siege;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Siege Phase 7a (DESIGN §8.5, {@code LockdownScenarioArea}): while a round is in HUB/IN_PROGRESS,
 * non-members can't enter the scenario's districts (their WorldGuard regions), and non-members inside
 * are moved just outside when the lockdown starts. Scoped to the match (no Domain.AllowEntry rows are
 * edited). {@code knk.siege.bypass.lockdown} (in-house permission; ops pass) skips it.
 * {@code SiegeAreaLockdownListener} cancels the movement; this class owns the state and the checks.
 * A scenario without districts isn't locked (readiness warns about it).
 */
public final class SiegeAreaLockdown implements SiegeMatchObserver {

    public static final String PERMISSION_BYPASS = "knk.siege.bypass.lockdown";

    /** One locked scenario area. */
    public record Area(SiegeLobbyRuntime runtime, String worldName, Set<String> regionIds) {}

    private final SiegeService service;
    private final Logger logger;
    private final Map<Integer, Area> byLobby = new HashMap<>();

    public SiegeAreaLockdown(SiegeService service) {
        this.service = service;
        this.logger = service.plugin().getLogger();
    }

    @Override
    public void areaLockdownStarted(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario) {
        if (!scenario.lockdownScenarioArea()) return;
        Set<String> regions = new HashSet<>();
        for (KnkSiegeDistrict district : scenario.districts()) {
            if (district.wgRegionId() != null && !district.wgRegionId().isBlank()) {
                regions.add(district.wgRegionId().trim().toLowerCase(Locale.ROOT));
            }
        }
        if (regions.isEmpty()) {
            logger.info("[Siege] Scenario " + scenario.id() + " has no districts with a region; the area isn't locked down");
            return;
        }
        Optional<Location> hub = SiegeBukkit.toLocation(scenario.hubLocation());
        if (hub.isEmpty() || hub.get().getWorld() == null) {
            logger.warning("[Siege] Scenario " + scenario.id() + " hub world isn't loaded; the area isn't locked down");
            return;
        }
        Area area = new Area(lobby, hub.get().getWorld().getName(), regions);
        byLobby.put(lobby.id(), area);
        evacuate(area, hub.get().getWorld());
    }

    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        byLobby.remove(lobby.id());
    }

    public boolean isActive() {
        return !byLobby.isEmpty();
    }

    /** Phase 7b: is this lobby's scenario area locked down right now? */
    public boolean isLocked(int lobbyId) {
        return byLobby.containsKey(lobbyId);
    }

    /**
     * The locked area a player may not step into with this move: {@code to} is inside it, {@code from}
     * isn't (someone already inside can always walk out), and they are neither a member of that round
     * nor allowed to bypass.
     */
    public Optional<Area> blockingEntry(Player player, Location from, Location to) {
        if (byLobby.isEmpty() || to == null || to.getWorld() == null) return Optional.empty();
        Set<String> toRegions = null;
        Set<String> fromRegions = null;
        for (Area area : byLobby.values()) {
            if (!area.worldName().equals(to.getWorld().getName())) continue;
            if (area.runtime().isMember(player.getUniqueId())) continue;
            if (toRegions == null) toRegions = regionsAt(to);
            if (!intersects(toRegions, area.regionIds())) continue;
            if (from != null && from.getWorld() != null && from.getWorld().getName().equals(area.worldName())) {
                if (fromRegions == null) fromRegions = regionsAt(from);
                if (intersects(fromRegions, area.regionIds())) continue;
            }
            if (service.hasPermission(player, PERMISSION_BYPASS)) return Optional.empty();
            return Optional.of(area);
        }
        return Optional.empty();
    }

    // ---- lockdown start: move non-members out ----

    private void evacuate(Area area, World world) {
        int moved = 0;
        for (Player player : world.getPlayers()) {
            if (area.runtime().isMember(player.getUniqueId())) continue;
            if (!intersects(regionsAt(player.getLocation()), area.regionIds())) continue;
            if (service.hasPermission(player, PERMISSION_BYPASS)) continue;
            Location exit = exitPoint(area, world, player.getLocation());
            if (exit == null) continue;
            player.teleport(exit);
            player.sendMessage(SiegeMessages.bad("The siege " + area.runtime().displayName()
                    + " is starting here; the area is closed until it ends."));
            moved++;
        }
        if (moved > 0) logger.info("[Siege] Lobby " + area.runtime().key() + ": moved " + moved + " non-member(s) out of the siege area");
    }

    /**
     * Just outside the bounding box of the locked regions, on the side nearest to the player, on the
     * highest block there. (An approximation: another locked district could border that side.)
     */
    private Location exitPoint(Area area, World world, Location from) {
        RegionManager manager = regionManager(world);
        if (manager == null) return null;
        Integer minX = null, minZ = null, maxX = null, maxZ = null;
        for (String id : area.regionIds()) {
            ProtectedRegion region = manager.getRegion(id);
            if (region == null) continue;
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            minX = minX == null ? min.x() : Math.min(minX, min.x());
            minZ = minZ == null ? min.z() : Math.min(minZ, min.z());
            maxX = maxX == null ? max.x() : Math.max(maxX, max.x());
            maxZ = maxZ == null ? max.z() : Math.max(maxZ, max.z());
        }
        if (minX == null) return null;
        int x = from.getBlockX();
        int z = from.getBlockZ();
        int[][] candidates = {
                {minX - 2, z}, {maxX + 2, z}, {x, minZ - 2}, {x, maxZ + 2}
        };
        int[] best = candidates[0];
        double bestDistance = Double.MAX_VALUE;
        for (int[] c : candidates) {
            double d = Math.hypot(c[0] - x, c[1] - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        int y = world.getHighestBlockYAt(best[0], best[1]) + 1;
        return new Location(world, best[0] + 0.5, y, best[1] + 0.5, from.getYaw(), from.getPitch());
    }

    // ---- WorldGuard ----

    private static Set<String> regionsAt(Location location) {
        Set<String> ids = new HashSet<>();
        if (location == null || location.getWorld() == null) return ids;
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        ApplicableRegionSet set = container.createQuery().getApplicableRegions(BukkitAdapter.adapt(location));
        for (ProtectedRegion region : set) {
            ids.add(region.getId().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static RegionManager regionManager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String id : a) {
            if (b.contains(id)) return true;
        }
        return false;
    }
}
