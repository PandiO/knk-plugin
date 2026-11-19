package net.knightsandkings.knk.core.siege.weapons;

/**
 * The block box of a spot: both corners inclusive, as a WorldEdit cuboid selection or a WorldGuard region's bounding
 * box gives them. The constructor sorts the corners, so any two opposite corners can be passed.
 */
public record SpotBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public SpotBounds {
        if (minX > maxX) { int t = minX; minX = maxX; maxX = t; }
        if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }
        if (minZ > maxZ) { int t = minZ; minZ = maxZ; maxZ = t; }
    }

    /** Blocks along x (east-west). */
    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    /** Blocks along z (north-south). */
    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** W70: blocks across the facing (x for north/south, z for east/west). */
    public int widthAcross(SpotFacing facing) {
        return facing.alongZ() ? sizeX() : sizeZ();
    }

    /** W70: blocks along the facing (z for north/south, x for east/west). */
    public int lengthAlong(SpotFacing facing) {
        return facing.alongZ() ? sizeZ() : sizeX();
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** "(1, 64, 2) to (7, 68, 10)". */
    public String describe() {
        return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
