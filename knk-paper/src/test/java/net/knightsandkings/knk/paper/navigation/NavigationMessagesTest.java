package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.BlockedExplainer;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.EtaEstimator;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The DESIGN §6 wording. */
class NavigationMessagesTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void bossBarTextHasTheDesignShape() {
        assertEquals("→ Merchantstreet · 340 m · ~1 min", NavigationMessages.bossBar("Merchantstreet", 340, EtaEstimator.defaults()));
    }

    @Test
    void refusalsNameTheDistanceAndThePlace() {
        assertEquals("[Road] You're too far from a road - get within 48 blocks of one.", plain(NavigationMessages.playerTooFar(48)));
        assertEquals("[Road] Kardenna Mill is too far from any road.", plain(NavigationMessages.destinationTooFar("Kardenna Mill")));
        assertEquals("[Road] No road connects you to Cinix.", plain(NavigationMessages.noRoadConnects("Cinix")));
        assertEquals("[Road] You are already in Kardenna.", plain(NavigationMessages.alreadyThere("Kardenna")));
    }

    @Test
    void partialRoutesExplainTheGateOrTheDomain() {
        NavigationTestNetwork network = new NavigationTestNetwork();
        AccessPolicy gateClosed = edge -> edge.gateDoorIds().contains(NavigationTestNetwork.GATE_DOOR)
            ? EdgeVerdict.blocked("the West Gate is closed", EdgeVerdict.Cause.gate(NavigationTestNetwork.GATE_DOOR, "the West Gate"))
            : edge.id() == NavigationTestNetwork.E_BD ? EdgeVerdict.blocked("the road is closed", EdgeVerdict.Cause.flag("Closed", "closed")) : EdgeVerdict.open();
        RouteResult result = new AStarRouter(network.snapshot).routeOrExplain(RouteRequest.of(
            SnapPoint.atNode(network.snapshot, NavigationTestNetwork.A), SnapPoint.atNode(network.snapshot, NavigationTestNetwork.C),
            gateClosed, RouterParameters.defaults()));
        BlockedExplainer.Explanation why = result.explanation();

        assertEquals("[Road] No open route to Cinix Keep - the West Gate is closed. Guiding you to the gate.",
            plain(NavigationMessages.partialRoute("Cinix Keep", why)));
        assertEquals("[Road] The West Gate is closed - recalculating.", plain(NavigationMessages.elementBlocked(why.verdict())));

        EdgeVerdict domain = EdgeVerdict.blocked("you may not enter Kardenna Castle", EdgeVerdict.Cause.domain(42, "Kardenna Castle"));
        BlockedExplainer.Explanation domainWhy = new BlockedExplainer.Explanation(domain, why.blockedEdge(), why.partialRoute(), why.fullRoute());
        assertEquals("[Road] You may not enter Kardenna Castle. Guiding you to its edge.",
            plain(NavigationMessages.partialRoute("Kardenna Castle", domainWhy)));
    }

    @Test
    void everyEndReasonHasAMessage() {
        for (EndReason reason : EndReason.values()) {
            assertTrue(plain(NavigationMessages.ended(reason, "Cinix")).startsWith("[Road] "), reason.name());
        }
        assertEquals("[Road] You have arrived at Kardenna Market.", plain(NavigationMessages.arrived("Kardenna Market")));
    }

    @Test
    void choicesAndSuggestionsAreClickable() {
        Component ambiguous = NavigationMessages.ambiguous("Market", List.of("town:Market", "district:Market"));
        assertTrue(plain(ambiguous).contains("town:Market, district:Market"));
        Component hint = NavigationMessages.didYouMean(List.of("Kardenna", "Kardenna Mill"));
        assertTrue(plain(hint).contains("Did you mean: Kardenna, Kardenna Mill"));
        assertEquals("[Road] In 12 m: Turn left onto Main Street", plain(NavigationMessages.maneuver(12, "Turn left onto Main Street")));
    }
}
