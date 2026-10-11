package net.knightsandkings.knk.core.roads.route;

/** Public door to the package-private {@link NetworkFixture} for tests in other packages. */
public final class NetworkFixtureAccess {

    public static final int A = NetworkFixture.A;
    public static final int B = NetworkFixture.B;
    public static final int C = NetworkFixture.C;
    public static final int E = NetworkFixture.E;
    public static final int E_AB = NetworkFixture.E_AB;
    public static final int E_BE = NetworkFixture.E_BE;
    public static final int GATE_DOOR = NetworkFixture.GATE_DOOR;

    private NetworkFixtureAccess() {
    }

    public static RoadNetworkSnapshot town() {
        return NetworkFixture.town();
    }
}
