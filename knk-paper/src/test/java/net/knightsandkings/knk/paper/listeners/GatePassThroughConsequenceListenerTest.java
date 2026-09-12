package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import net.knightsandkings.knk.paper.gates.GatePassThroughService;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Unit tests for GatePassThroughConsequenceListener - the permission check and mode-resolution
 * logic that sits between GateDoorInteractEvent detection and GatePassThroughService's actual
 * mode handlers, per REQUIREMENTS_GATE_ADVANCED_FEATURES.md Feature 1.
 */
class GatePassThroughConsequenceListenerTest {
    private GatePassThroughService passThroughService;
    private UserManager userManager;
    private GatePassThroughConsequenceListener listener;
    private CachedGateDoor gate;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        passThroughService = mock(GatePassThroughService.class);
        userManager = mock(UserManager.class);
        listener = new GatePassThroughConsequenceListener(passThroughService, userManager);

        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, false, 90, "north"
        );

        playerUuid = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
    }

    private PlayerUserData userDataWithMethod(GatePassThroughMethod method) {
        return new PlayerUserData(1, "Tester", playerUuid, null, 0, 0, 0, false, false, null, method);
    }

    @Test
    void skipsAlreadyCancelledEvents() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission("knk.gate.passthrough.use")).thenReturn(true);

        GateDoorInteractEvent event = new GateDoorInteractEvent(gate, player, null);
        event.setCancelled(true);

        listener.onGateDoorInteract(event);

        verifyNoInteractions(passThroughService);
    }

    @Test
    void doesNothingWhenGateDoesNotAllowPassThroughAndPlayerIsNotAdmin() {
        gate.setAllowPassThrough(false);

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verifyNoInteractions(passThroughService);
    }

    @Test
    void doesNothingWhenPlayerLacksBasePermission() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission("knk.gate.passthrough.use")).thenReturn(false);

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verifyNoInteractions(passThroughService);
    }

    @Test
    void dispatchesDefaultModeForAuthorizedPlayerWithNoCachedPreference() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission("knk.gate.passthrough.use")).thenReturn(true);
        when(userManager.getCachedUser(playerUuid)).thenReturn(null);

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verify(passThroughService).dispatch(gate, player, GatePassThroughMethod.DEFAULT);
    }

    @Test
    void dispatchesInstantOpenWhenPlayerHasBothPermissions() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission("knk.gate.passthrough.use")).thenReturn(true);
        when(player.hasPermission("knk.gate.passthrough.instant")).thenReturn(true);
        when(userManager.getCachedUser(playerUuid)).thenReturn(userDataWithMethod(GatePassThroughMethod.INSTANT_OPEN));

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verify(passThroughService).dispatch(gate, player, GatePassThroughMethod.INSTANT_OPEN);
    }

    @Test
    void fallsBackToDefaultWhenPreferredIsInstantOpenButPlayerLacksExtraPermission() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission("knk.gate.passthrough.use")).thenReturn(true);
        when(player.hasPermission("knk.gate.passthrough.instant")).thenReturn(false);
        when(userManager.getCachedUser(playerUuid)).thenReturn(userDataWithMethod(GatePassThroughMethod.INSTANT_OPEN));

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verify(passThroughService).dispatch(gate, player, GatePassThroughMethod.DEFAULT);
    }

    @Test
    void dispatchesTeleportWhenPreferred() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission("knk.gate.passthrough.use")).thenReturn(true);
        when(userManager.getCachedUser(playerUuid)).thenReturn(userDataWithMethod(GatePassThroughMethod.TELEPORT));

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verify(passThroughService).dispatch(gate, player, GatePassThroughMethod.TELEPORT);
    }

    @Test
    void adminBypassesAllowPassThroughToggleAndBasePermission() {
        gate.setAllowPassThrough(false);
        when(player.hasPermission("knk.gate.admin")).thenReturn(true);
        when(userManager.getCachedUser(playerUuid)).thenReturn(null);

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verify(passThroughService).dispatch(gate, player, GatePassThroughMethod.DEFAULT);
    }

    @Test
    void adminWithInstantOpenPreferenceIsNeverDowngraded() {
        gate.setAllowPassThrough(false);
        when(player.hasPermission("knk.gate.admin")).thenReturn(true);
        // Admin does NOT separately have knk.gate.passthrough.instant granted here, but the
        // knk.gate.admin bypass should still grant full InstantOpen access.
        when(userManager.getCachedUser(playerUuid)).thenReturn(userDataWithMethod(GatePassThroughMethod.INSTANT_OPEN));

        listener.onGateDoorInteract(new GateDoorInteractEvent(gate, player, null));

        verify(passThroughService).dispatch(gate, player, GatePassThroughMethod.INSTANT_OPEN);
    }
}
