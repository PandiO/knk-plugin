package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

/**
 * The WorldGuard side of lootboxes (spawn-area checks and {@code /knk lootbox area}). An interface so the scheduler
 * and the area command are testable without WorldGuard, which is compile-only and absent from the test classpath.
 * Main thread only.
 */
public interface LootboxRegions {

    /** The prefix of the regions {@code /knk lootbox area create} makes; only those are ever removed by the plugin. */
    String AREA_REGION_PREFIX = "lootbox_";

    enum CreateStatus { CREATED, NO_SELECTION, FAILED }

    /** The outcome of {@link #createFullHeightFromSelection}; {@code message} explains a failure. */
    record CreateResult(CreateStatus status, String message) {
        public static CreateResult created() {
            return new CreateResult(CreateStatus.CREATED, null);
        }
    }

    boolean regionExists(World world, String regionId);

    /** The region's bounding box, or empty when it doesn't exist in that world. */
    Optional<LootboxSpawnPlanner.Bounds> bounds(World world, String regionId);

    boolean contains(World world, String regionId, int x, int y, int z);

    /** Every region at the block (for {@code ExcludedRegionIds}). */
    Set<String> regionIdsAt(World world, int x, int y, int z);

    /**
     * Builds a region with id {@code regionId} from the player's WorldEdit selection in their current world, stretched
     * to the world's full build height, priority 0 and no flags, and adds it to that world's region manager.
     */
    CreateResult createFullHeightFromSelection(Player player, String regionId);

    /** Removes the region; false when it didn't exist. */
    boolean removeRegion(World world, String regionId);

    /** {@code lootbox_<lower-cased name>}. */
    static String areaRegionId(String areaName) {
        return AREA_REGION_PREFIX + areaName.toLowerCase(java.util.Locale.ROOT);
    }
}
