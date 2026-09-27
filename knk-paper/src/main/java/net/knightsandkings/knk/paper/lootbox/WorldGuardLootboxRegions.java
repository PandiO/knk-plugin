package net.knightsandkings.knk.paper.lootbox;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@link LootboxRegions} on WorldGuard/WorldEdit, through {@link WorldGuardIntegration}. */
public final class WorldGuardLootboxRegions implements LootboxRegions {

    private static final Logger LOGGER = Logger.getLogger(WorldGuardLootboxRegions.class.getName());

    private final WorldGuardIntegration worldGuard;

    public WorldGuardLootboxRegions(WorldGuardIntegration worldGuard) {
        this.worldGuard = worldGuard;
    }

    @Override
    public boolean regionExists(World world, String regionId) {
        return worldGuard.regionExists(regionId, world);
    }

    @Override
    public Optional<LootboxSpawnPlanner.Bounds> bounds(World world, String regionId) {
        ProtectedRegion region = region(world, regionId);
        if (region == null) {
            return Optional.empty();
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return Optional.of(new LootboxSpawnPlanner.Bounds(
                min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(), max.getBlockY(), max.getBlockZ()));
    }

    @Override
    public boolean contains(World world, String regionId, int x, int y, int z) {
        ProtectedRegion region = region(world, regionId);
        return region != null && region.contains(x, y, z);
    }

    @Override
    public Set<String> regionIdsAt(World world, int x, int y, int z) {
        RegionManager manager = worldGuard.regionManager(world);
        Set<String> ids = new HashSet<>();
        if (manager == null) {
            return ids;
        }
        for (ProtectedRegion region : manager.getApplicableRegions(BlockVector3.at(x, y, z))) {
            ids.add(region.getId());
        }
        return ids;
    }

    @Override
    public CreateResult createFullHeightFromSelection(Player player, String regionId) {
        World world = player.getWorld();
        Region selection;
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            selection = session.getSelection(BukkitAdapter.adapt(world));
        } catch (IncompleteRegionException e) {
            return new CreateResult(CreateStatus.NO_SELECTION, null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the WorldEdit selection of " + player.getName(), e);
            return new CreateResult(CreateStatus.FAILED, "Could not read your WorldEdit selection: " + e.getMessage());
        }
        if (selection == null) {
            return new CreateResult(CreateStatus.NO_SELECTION, null);
        }

        try {
            ProtectedRegion region = WorldGuardIntegration.createFullHeightRegion(
                    selection, regionId, 0, world.getMinHeight(), world.getMaxHeight() - 1);
            if (!worldGuard.addRegion(region, world)) {
                return new CreateResult(CreateStatus.FAILED, "WorldGuard has no region manager for world " + world.getName() + ".");
            }
            return CreateResult.created();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not create region " + regionId, e);
            return new CreateResult(CreateStatus.FAILED, "Could not create the region: " + e.getMessage());
        }
    }

    @Override
    public boolean removeRegion(World world, String regionId) {
        return worldGuard.removeRegion(regionId, world);
    }

    private ProtectedRegion region(World world, String regionId) {
        RegionManager manager = worldGuard.regionManager(world);
        return manager == null || regionId == null ? null : manager.getRegion(regionId);
    }
}
