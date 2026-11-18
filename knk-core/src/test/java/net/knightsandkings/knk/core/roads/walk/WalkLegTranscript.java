package net.knightsandkings.knk.core.roads.walk;

import java.util.List;
import java.util.function.UnaryOperator;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.util.BlockKey;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, O24): today's walk legs as text - doors, a gap, water, a ladder, a stair,
 * a drop and an open field, for today's player profiles. Uses only the API from before phase 4, so the same file builds
 * the golden transcript on the base branch ({@code src/test/resources/route-modes/walk-default.txt}); the test hooks
 * the mode's profile in.
 */
final class WalkLegTranscript {

    private static final String G = GridFixture.GRASS;

    private WalkLegTranscript() {
    }

    private record Leg(String name, WalkFixture fixture, int[] from, int[] to) {
    }

    private static List<Leg> legs() {
        WalkFixture ladder = new WalkFixture().floor(0, 0, 2, 0, 64, G).column(3, 0, 64, 68, GridFixture.STONE)
            .floor(4, 0, 5, 0, 68, G).column(2, 0, 65, 68, WalkFixture.LADDER);
        WalkFixture stairs = new WalkFixture().floor(0, 0, 9, 4, 64, G)
            .block(4, 65, 0, GridFixture.STONE).block(5, 66, 0, GridFixture.STONE).floor(6, 0, 9, 0, 66, G)
            .layer(65, "..........", "....//....", "..........", "..........", "..........");
        WalkFixture drop = new WalkFixture().floor(0, 0, 3, 2, 68, G).floor(4, 0, 9, 2, 64, G)
            .floor(0, 3, 9, 6, 64, G).column(3, 3, 64, 68, GridFixture.STONE);
        return List.of(
            new Leg("door", new WalkFixture().floor(0, 0, 4, 4, 64, G).layer(65,
                ".....", ".....", "##D##", ".....", "....."), new int[] {2, 64, 0}, new int[] {2, 64, 4}),
            new Leg("door-or-gap", new WalkFixture().floor(0, 0, 4, 4, 64, G).layer(65,
                ".....", ".....", "##D#.", ".....", "....."), new int[] {2, 64, 0}, new int[] {2, 64, 4}),
            new Leg("gate-or-gap", new WalkFixture().floor(0, 0, 6, 4, 64, G).layer(65,
                ".......", ".......", "##F###.", ".......", "......."), new int[] {2, 64, 0}, new int[] {2, 64, 4}),
            new Leg("puddle", new WalkFixture().floor(0, 0, 4, 2, 64, G).block(1, 65, 0, GridFixture.WATER)
                .block(2, 65, 0, GridFixture.WATER).block(3, 65, 0, GridFixture.WATER),
                new int[] {0, 64, 0}, new int[] {4, 64, 0}),
            new Leg("channel", new WalkFixture().floor(0, 0, 4, 0, 64, G).block(2, 65, 0, GridFixture.WATER),
                new int[] {0, 64, 0}, new int[] {4, 64, 0}),
            new Leg("ladder", ladder, new int[] {0, 64, 0}, new int[] {5, 68, 0}),
            new Leg("stairs", stairs, new int[] {0, 64, 0}, new int[] {8, 66, 0}),
            new Leg("drop", drop, new int[] {1, 68, 1}, new int[] {1, 64, 5}),
            new Leg("field", new WalkFixture().floor(0, 0, 9, 9, 64, G), new int[] {0, 64, 0}, new int[] {9, 64, 5}),
            new Leg("walled-field", new WalkFixture().floor(0, 0, 9, 9, 64, G).layer(65,
                "..........", "..........", "..#####...", "..#.......", "..#.......", "..........",
                "..........", "..........", "..........", ".........."), new int[] {0, 64, 0}, new int[] {5, 64, 5}));
    }

    static String transcript(UnaryOperator<MovementProfile> hook) {
        StringBuilder sb = new StringBuilder();
        List<Object[]> profiles = List.of(
            new Object[] {"player", MovementProfile.PLAYER},
            new Object[] {"geometry", WalkFixture.GEOMETRY_PLAYER},
            new Object[] {"guidance-v2", MovementProfile.PLAYER.withClearance(5, MovementProfile.DEFAULT_WALL_CLEARANCE)});
        for (Object[] p : profiles) {
            for (Leg leg : legs()) {
                WalkRequest request = leg.fixture().request(leg.from()[0], leg.from()[1], leg.from()[2], leg.to()[0],
                    leg.to()[1], leg.to()[2]);
                request = request.withProfile(hook.apply((MovementProfile) p[1]));
                WalkResult result = new WalkSearch().find(request);
                sb.append(p[0]).append(' ').append(leg.name()).append(": ").append(result.status())
                    .append(" expansions=").append(result.expansions());
                result.path().ifPresent(path -> {
                    sb.append(" length=").append(path.length()).append(" cost=").append(path.cost()).append(" cells=");
                    for (int i = 0; i < path.size(); i++) {
                        long k = path.cell(i);
                        sb.append(path.isLadder(i) ? "L" : "").append(BlockKey.x(k)).append(',').append(BlockKey.y(k))
                            .append(',').append(BlockKey.z(k)).append(' ');
                    }
                });
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
