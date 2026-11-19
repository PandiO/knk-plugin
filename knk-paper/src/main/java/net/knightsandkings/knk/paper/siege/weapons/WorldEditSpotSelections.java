package net.knightsandkings.knk.paper.siege.weapons;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** {@link SpotSelections} on WorldEdit and WorldGuard (both are hard dependencies of the plugin). */
public final class WorldEditSpotSelections implements SpotSelections {

    @Override
    public Read worldEditSelection(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            Region selection = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            return Read.of(bounds(selection.getMinimumPoint(), selection.getMaximumPoint()),
                    !(selection instanceof CuboidRegion));
        } catch (IncompleteRegionException e) {
            return Read.failed("You have no complete WorldEdit selection in this world. Select the spot with the wand"
                    + " (or //pos1 and //pos2), or type 'region <id>' to use a WorldGuard region.");
        } catch (RuntimeException e) {
            return Read.failed("Your WorldEdit selection can't be read: " + e.getMessage());
        }
    }

    @Override
    public Read worldGuardRegion(World world, String regionId) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        ProtectedRegion region = manager == null ? null : manager.getRegion(regionId);
        if (region == null) {
            return Read.failed("No WorldGuard region '" + regionId + "' in " + world.getName() + ".");
        }
        if (ProtectedRegion.GLOBAL_REGION.equalsIgnoreCase(region.getId())) {
            return Read.failed("The global region has no bounds; pick a region with corners.");
        }
        return Read.of(bounds(region.getMinimumPoint(), region.getMaximumPoint()), !(region instanceof ProtectedCuboidRegion));
    }

    private static SpotBounds bounds(BlockVector3 min, BlockVector3 max) {
        return new SpotBounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }
}
