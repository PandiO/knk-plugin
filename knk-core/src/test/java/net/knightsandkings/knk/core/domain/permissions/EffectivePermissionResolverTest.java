package net.knightsandkings.knk.core.domain.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * KNG-107: the plugin resolves nodes from the effective set; each case mirrors knk-web-api's
 * {@code PermissionResolutionService.CheckAsync} (PermissionResolutionServiceTests).
 */
class EffectivePermissionResolverTest {

    private static final PermissionHolder USER = new PermissionHolder(1, "User", "alice");
    private static final PermissionHolder VIP = new PermissionHolder(2, "PermissionGroup", "vip");
    private static final PermissionHolder DEFAULT = new PermissionHolder(3, "PermissionGroup", "default");

    private static EffectivePermission entry(String node, boolean value, PermissionHolder source, int rank) {
        return new EffectivePermission(node, value, source, rank);
    }

    private static EffectivePermissionResolver resolver(EffectivePermission... entries) {
        return EffectivePermissionResolver.of(new EffectivePermissionSet(1, List.of(entries)));
    }

    @Test
    void noMatch_isUndeclared() {
        PermissionCheckResult result = resolver(entry("knk.fly", true, USER, 0)).resolve("knk.heal").orElseThrow();

        assertEquals(PermissionResolution.UNDECLARED, result.result());
        assertEquals(null, result.source());
    }

    @Test
    void anEmptySet_answersUndeclared() {
        assertEquals(PermissionResolution.UNDECLARED, resolver().resolve("knk.fly").orElseThrow().result());
    }

    @Test
    void anExactGrant_isGranted_withItsSource() {
        PermissionCheckResult result = resolver(entry("knk.fly", true, VIP, 1)).resolve("knk.fly").orElseThrow();

        assertEquals(PermissionResolution.GRANTED, result.result());
        assertEquals(VIP, result.source());
        assertEquals("knk.fly", result.matchedNode());
    }

    @Test
    void theFirstHolderWithAnyMatchDecides_evenWithALessSpecificNode() {
        EffectivePermissionResolver resolver = resolver(
            entry("knk.*", true, USER, 0),
            entry("knk.fly", false, VIP, 1));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.fly").orElseThrow().result());
    }

    @Test
    void aCloserGroupBeatsItsParent() {
        EffectivePermissionResolver resolver = resolver(
            entry("knk.gate.open", false, VIP, 1),
            entry("knk.gate.*", true, DEFAULT, 2));

        assertEquals(PermissionResolution.DENIED, resolver.resolve("knk.gate.open").orElseThrow().result());
        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.gate.close").orElseThrow().result());
    }

    @Test
    void withinOneHolder_theMostSpecificMatchWins() {
        EffectivePermissionResolver resolver = resolver(
            entry("knk.*", false, USER, 0),
            entry("knk.gate.*", true, USER, 0));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.gate.open").orElseThrow().result());
        assertEquals(PermissionResolution.DENIED, resolver.resolve("knk.fly").orElseThrow().result());
    }

    @Test
    void withinOneHolder_aDenyBeatsAGrantOfTheSameSpecificity() {
        EffectivePermissionResolver tie = resolver(
            entry("knk.a.*", true, USER, 0),
            entry("knk.a.*", false, USER, 0));

        assertEquals(PermissionResolution.DENIED, tie.resolve("knk.a.b").orElseThrow().result());
    }

    @Test
    void aWildcard_alsoMatchesTheNodeWithoutItsTrailingDotStar() {
        EffectivePermissionResolver resolver = resolver(entry("knk.gate.*", true, USER, 0));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.gate").orElseThrow().result());
        assertEquals(PermissionResolution.UNDECLARED, resolver.resolve("knk.gates").orElseThrow().result());
    }

    @Test
    void aBareStar_matchesEverything_leastSpecifically() {
        EffectivePermissionResolver resolver = resolver(
            entry("*", true, USER, 0),
            entry("knk.fly", false, USER, 0));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("anything.at.all").orElseThrow().result());
        assertEquals(PermissionResolution.DENIED, resolver.resolve("knk.fly").orElseThrow().result());
    }

    @Test
    void nodesCompareCaseSensitively() {
        assertEquals(PermissionResolution.UNDECLARED, resolver(entry("knk.Fly", true, USER, 0)).resolve("knk.fly").orElseThrow().result());
    }

    // ===== Without ranks (an API older than KNG-107): exact whenever the chain order can't matter =====

    private static EffectivePermissionResolver unranked(EffectivePermission... entries) {
        EffectivePermissionResolver resolver = EffectivePermissionResolver.of(new EffectivePermissionSet(1, List.of(entries)));
        assertFalse(resolver.ranked());
        return resolver;
    }

    private static EffectivePermission plain(String node, boolean value, PermissionHolder source) {
        return new EffectivePermission(node, value, source);
    }

    @Test
    void unranked_theUsersOwnGrantsAlwaysComeFirst() {
        EffectivePermissionResolver resolver = unranked(
            plain("knk.fly", false, VIP),
            plain("knk.*", true, USER));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.fly").orElseThrow().result());
    }

    @Test
    void unranked_aSingleMatchingGroupDecides_withItsOwnSpecificityRules() {
        EffectivePermissionResolver resolver = unranked(
            plain("knk.*", false, VIP),
            plain("knk.gate.*", true, VIP),
            plain("knk.account.use", true, DEFAULT));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.gate.open").orElseThrow().result());
        assertEquals(PermissionResolution.DENIED, resolver.resolve("knk.fly").orElseThrow().result());
        assertEquals(PermissionResolution.UNDECLARED, resolver.resolve("other.node").orElseThrow().result());
    }

    @Test
    void unranked_groupsThatAgree_decide() {
        EffectivePermissionResolver resolver = unranked(
            plain("knk.*", true, VIP),
            plain("knk.fly", true, DEFAULT));

        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.fly").orElseThrow().result());
    }

    @Test
    void unranked_groupsThatDisagree_areLeftToTheApi() {
        EffectivePermissionResolver resolver = unranked(
            plain("knk.*", true, VIP),
            plain("knk.fly", false, DEFAULT));

        assertTrue(resolver.resolve("knk.fly").isEmpty());
        assertEquals(PermissionResolution.GRANTED, resolver.resolve("knk.heal").orElseThrow().result());
    }

    @Test
    void specificity_followsTheApi() {
        assertEquals(Integer.MAX_VALUE, EffectivePermissionResolver.specificity("knk.fly", "knk.fly"));
        assertEquals(0, EffectivePermissionResolver.specificity("*", "knk.fly"));
        assertEquals("knk.".length(), EffectivePermissionResolver.specificity("knk.*", "knk.fly"));
        assertEquals(-1, EffectivePermissionResolver.specificity("knk.heal", "knk.fly"));
    }
}
