package net.knightsandkings.knk.core.siege.weapons;

import java.util.ArrayList;
import java.util.List;

/**
 * SIEGE_WEAPONS § 4.6 (W44): a weapon is pasted into the spot exactly, so the spot task warns, without refusing, when
 * the layer under the spot isn't solid or the spot's volume isn't clear. Bukkit-free: the paper side answers the
 * {@link BlockProbe} from the world.
 */
public final class SpotSiteCheck {

    /**
     * Above this many blocks the volume isn't scanned block by block (a selection can be much larger than the large
     * class); the floor is always checked.
     */
    public static final long MAX_CHECKED_VOLUME = 32_768;

    /** What the check needs to know about one block. */
    public interface BlockProbe {
        /** A block a weapon can stand on (motion-blocking). */
        boolean isSolid(int x, int y, int z);

        /** Air or a block the paste may replace (grass, snow layers, flowers ...). */
        boolean isClear(int x, int y, int z);
    }

    /**
     * @param floorBlocks    blocks in the layer under the spot
     * @param openFloor      of those, the ones that aren't solid
     * @param volumeChecked  false when the volume was larger than {@link #MAX_CHECKED_VOLUME} and not scanned
     * @param blockedVolume  blocks inside the spot that aren't clear
     */
    public record Result(int floorBlocks, int openFloor, boolean volumeChecked, long blockedVolume) {

        public boolean hasWarnings() {
            return openFloor > 0 || blockedVolume > 0 || !volumeChecked;
        }

        /** One line per warning, empty when the site is fine. */
        public List<String> warnings() {
            List<String> lines = new ArrayList<>();
            if (openFloor > 0) {
                lines.add("The floor isn't solid: " + openFloor + " of " + floorBlocks
                        + " blocks under the spot are air or not solid, so a weapon would hang in the air.");
            }
            if (!volumeChecked) {
                lines.add("The spot is larger than " + MAX_CHECKED_VOLUME + " blocks, so its volume wasn't checked block by block.");
            } else if (blockedVolume > 0) {
                lines.add("The volume isn't clear: " + blockedVolume
                        + " blocks inside the spot would be replaced by the weapon (they come back when it is removed).");
            }
            return lines;
        }
    }

    private SpotSiteCheck() {
    }

    public static Result check(SpotBounds bounds, BlockProbe probe) {
        int floorBlocks = 0;
        int openFloor = 0;
        int floorY = bounds.minY() - 1;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                floorBlocks++;
                if (!probe.isSolid(x, floorY, z)) openFloor++;
            }
        }
        if (bounds.volume() > MAX_CHECKED_VOLUME) {
            return new Result(floorBlocks, openFloor, false, 0);
        }
        long blocked = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (!probe.isClear(x, y, z)) blocked++;
                }
            }
        }
        return new Result(floorBlocks, openFloor, true, blocked);
    }
}
