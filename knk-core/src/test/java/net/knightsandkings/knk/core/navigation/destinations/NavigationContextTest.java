package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext.Siege;

/** The player snapshot the scopes read (DESIGN §3). */
class NavigationContextTest {

    @Test
    void aPlainPlayerHasNoModeNoFlagsNoPermissionsAndNoSiege() {
        NavigationContext ctx = NavigationContext.of(Fixtures.PLAYER, "world");
        assertEquals(ActiveMode.NONE, ctx.mode());
        assertFalse(ctx.loading() || ctx.frozen());
        assertEquals(Set.of(), ctx.permissions());
        assertFalse(ctx.hasPermission(InitialScopes.NAVIGATE_NODE));
        assertEquals(Siege.NONE, ctx.siege());
        assertEquals(0, ctx.modelVersion());
        assertThrows(NullPointerException.class, () -> NavigationContext.of(null, "world"));
        assertThrows(NullPointerException.class, () -> NavigationContext.of(Fixtures.PLAYER, null));
    }

    @Test
    void nullsNormaliseToTheDefaults() {
        NavigationContext ctx = new NavigationContext(Fixtures.PLAYER, "world", null, false, false, null, null, 3);
        assertEquals(ActiveMode.NONE, ctx.mode());
        assertEquals(Set.of(), ctx.permissions());
        assertEquals(Siege.NONE, ctx.siege());
    }

    @Test
    void withersAddWithoutChangingTheRest() {
        NavigationContext ctx = NavigationContext.of(Fixtures.PLAYER, "world")
            .withPermissions(InitialScopes.NAVIGATE_NODE)
            .withPermissions(InitialScopes.BYPASS_NODE)
            .withMode(ActiveMode.STAFF).withFrozen(true).withLoading(true)
            .withSiege(Siege.lobby()).withWorld("nether").withModelVersion(7);
        assertTrue(ctx.hasPermission(InitialScopes.NAVIGATE_NODE) && ctx.hasPermission(InitialScopes.BYPASS_NODE));
        assertEquals(ActiveMode.STAFF, ctx.mode());
        assertTrue(ctx.frozen() && ctx.loading());
        assertTrue(ctx.siege().lobbyMember());
        assertEquals("nether", ctx.world());
        assertEquals(7, ctx.modelVersion());
        assertEquals(Fixtures.PLAYER, ctx.player());
    }

    @Test
    void aRunningMatchImpliesLobbyMembership() {
        Siege match = Siege.match(2, 5);
        assertTrue(match.lobbyMember() && match.matchRunning());
        assertEquals(2, match.team());
        assertEquals(5, match.scenario());
        assertTrue(match.compassEnabled());
        assertFalse(match.withCompassEnabled(false).compassEnabled());
        assertTrue(new Siege(false, true, null, null, true).lobbyMember());
        Siege lobby = Siege.lobby();
        assertTrue(lobby.lobbyMember());
        assertFalse(lobby.matchRunning());
        assertNull(lobby.team());
    }
}
