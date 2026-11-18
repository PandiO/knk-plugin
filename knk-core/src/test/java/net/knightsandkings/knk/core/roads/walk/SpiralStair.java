package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;

/**
 * A square spiral stair of width 3 in a tower (off-road phase 6.1, DESIGN § 13 A15): a 1x1 newel at (3, 3), the stair
 * on every column at Chebyshev distance 1 to 3 from it, an outer wall at distance 4. A column's floor rises one block
 * every quarter turn ({@link #SECTORS} sectors by the angle of its centre round the newel's), {@link #RISE} blocks per
 * turn, for {@link #TURNS} turns, so each column holds a floor every {@link #RISE} blocks; every floor is a stair
 * block (stepped up without a jump). Floors from 64.
 */
final class SpiralStair {

    static final int CX = 3;
    static final int CZ = 3;
    static final int BASE = 64;
    static final int SECTORS = 16;
    static final int RISE = 4;
    static final int TURNS = 3;
    static final int TOP = BASE + RISE * TURNS + 6;

    private SpiralStair() {
    }

    /** The sector (0..15) of a column, counted from east (+x) towards +z. */
    static int sector(int x, int z) {
        double a = Math.atan2(z - CZ, x - CX);
        if (a < 0) {
            a += 2 * Math.PI;
        }
        return Math.min(SECTORS - 1, (int) Math.floor(a / (2 * Math.PI) * SECTORS + 1e-9));
    }

    /** The floor of a stair column on turn {@code turn}. */
    static int floor(int x, int z, int turn) {
        return BASE + RISE * turn + sector(x, z) * RISE / SECTORS;
    }

    static int ring(int x, int z) {
        return Math.max(Math.abs(x - CX), Math.abs(z - CZ));
    }

    static WalkFixture fixture() {
        WalkFixture f = new WalkFixture();
        for (int x = CX - 4; x <= CX + 4; x++) {
            for (int z = CZ - 4; z <= CZ + 4; z++) {
                int ring = ring(x, z);
                if (ring == 0 || ring == 4) {
                    f.column(x, z, BASE - 1, TOP, GridFixture.STONE); // the newel and the tower wall
                    continue;
                }
                for (int turn = 0; turn < TURNS; turn++) {
                    f.block(x, floor(x, z, turn), z, GridFixture.STAIRS);
                }
            }
        }
        return f;
    }

    /** The distance from a point to the newel's square, horizontally. */
    static double fromNewel(double x, double z) {
        double dx = Math.max(Math.max(CX - x, 0), x - (CX + 1));
        double dz = Math.max(Math.max(CZ - z, 0), z - (CZ + 1));
        return Math.hypot(dx, dz);
    }

    /** The distance from a point inside the tower to its wall's inner face, horizontally. */
    static double fromTowerWall(double x, double z) {
        double lo = CX - 3;
        double hi = CX + 4;
        return Math.min(Math.min(x - lo, hi - x), Math.min(z - (CZ - 3), (CZ + 4) - z));
    }
}
