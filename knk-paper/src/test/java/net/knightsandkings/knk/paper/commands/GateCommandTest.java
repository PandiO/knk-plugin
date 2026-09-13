package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GateCommand player and admin commands.
 */
class GateCommandTest {
    private GateCommand gateCommand;
    private GateManager mockGateManager;
    private GateStructuresApi mockGateStructuresApi;
    private GateDoorsApi mockGateDoorsApi;
    private CommandSender mockSender;
    private Player mockPlayer;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        mockGateManager = mock(GateManager.class);
        mockGateStructuresApi = mock(GateStructuresApi.class);
        mockGateDoorsApi = mock(GateDoorsApi.class);
        // GateDoorRegionCaptureHandler references WorldEdit types (compileOnly, unavailable on
        // the test classpath - see WorldGuardIntegrationTest's identical constraint), so it can't
        // be Mockito-mocked here; null is safe since no existing test exercises the door
        // capture/redefine subcommand this handler backs.
        gateCommand = new GateCommand(mockGateManager, mockGateStructuresApi, mockGateDoorsApi, mock(UserManager.class), mock(UsersCommandApi.class), mock(DistrictGateLoader.class), null);
        mockSender = mock(CommandSender.class);
        mockPlayer = mock(Player.class);
        sentMessages = new ArrayList<>();

        // Capture sent messages
        doAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return null;
        }).when(mockSender).sendMessage(anyString());

        doAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return null;
        }).when(mockPlayer).sendMessage(anyString());

        // Default permissions
        when(mockSender.hasPermission(anyString())).thenReturn(true);
        when(mockPlayer.hasPermission(anyString())).thenReturn(true);
        when(mockGateDoorsApi.updateOperationalSettings(anyInt(), anyBoolean(), anyBoolean()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(mockGateDoorsApi.updateHealth(anyInt(), anyDouble()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(mockGateDoorsApi.updateState(anyInt(), anyString(), anyBoolean()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    // ===== Player Commands =====

    @Test
    void testExecuteOpen_Success() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);
        when(mockGateManager.openGate(1)).thenReturn(true);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Opening gate")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("TestGate")));
        verify(mockGateManager).openGate(1);
        verify(mockGateManager).setAnimationCompletionCallback(eq(1), any());
    }

    @Test
    void testExecuteOpen_ResolvesGateById() {
        CachedGateDoor gate = createTestGate(10, "Keep Gate", true, false);
        when(mockGateManager.getGate(10)).thenReturn(gate);
        when(mockGateManager.openGate(10)).thenReturn(true);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"10"});

        assertTrue(result);
        verify(mockGateManager).getGate(10);
        verify(mockGateManager).openGate(10);
    }

    @Test
    void testExecuteOpen_ResolvesMultiWordGateName() {
        CachedGateDoor gate = createTestGate(10, "Keep Gate Test", true, false);
        when(mockGateManager.getGateByName("Keep Gate Test")).thenReturn(gate);
        when(mockGateManager.openGate(10)).thenReturn(true);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"Keep", "Gate", "Test"});

        assertTrue(result);
        verify(mockGateManager).getGateByName("Keep Gate Test");
        verify(mockGateManager).openGate(10);
    }

    @Test
    void testOnCommand_DelegatesOpen() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);
        when(mockGateManager.openGate(1)).thenReturn(true);

        boolean result = gateCommand.onCommand(mockSender, null, "gate", new String[]{"open", "TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Opening gate")));
        verify(mockGateManager).openGate(1);
    }

    @Test
    void testExecuteOpen_GateNotFound() {
        when(mockGateManager.getGateByName("NonExistent")).thenReturn(null);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"NonExistent"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("not found")));
    }

    @Test
    void testExecuteOpen_GateNotActive() {
        CachedGateDoor gate = createTestGate(1, "TestGate", false, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("not active")));
    }

    @Test
    void testExecuteOpen_GateDestroyed() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, true);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("destroyed")));
    }

    @Test
    void testExecuteOpen_AlreadyOpen() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);
        when(mockGateManager.openGate(1)).thenReturn(false);

        boolean result = gateCommand.executeOpen(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("already open")));
    }

    @Test
    void testExecuteClose_Success() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);
        when(mockGateManager.closeGate(1)).thenReturn(true);

        boolean result = gateCommand.executeClose(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Closing gate")));
        verify(mockGateManager).closeGate(1);
        verify(mockGateManager).setAnimationCompletionCallback(eq(1), any());
    }

    @Test
    void testExecuteInfo_Success() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeInfo(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Gate Info")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("TestGate")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("SLIDING")));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("500/500")));
    }

    @Test
    void testExecuteList_NoGates() {
        when(mockGateManager.getAllGates()).thenReturn(java.util.Collections.emptyMap());

        boolean result = gateCommand.executeList(mockSender, new String[0]);

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("No gates")));
    }

    // ===== Admin Commands =====

    @Test
    void testExecuteAdminHealth_Success() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeAdminHealth(mockSender, new String[]{"TestGate", "250"});

        assertTrue(result);
        assertEquals(250.0, gate.getHealthCurrent());
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("health to 250")));
    }

    @Test
    void testExecuteAdminHealth_ResolvesMultiWordGateName() {
        CachedGateDoor gate = createTestGate(10, "Keep Gate Test", true, false);
        when(mockGateManager.getGateByName("Keep Gate Test")).thenReturn(gate);

        boolean result = gateCommand.executeAdminHealth(mockSender, new String[]{"Keep", "Gate", "Test", "250"});

        assertTrue(result);
        assertEquals(250.0, gate.getHealthCurrent());
        verify(mockGateManager).getGateByName("Keep Gate Test");
    }

    @Test
    void testExecuteAdminToggleActive_PersistsChangeById() {
        CachedGateDoor gate = createTestGate(11, "Keep Gate Test", false, false);
        when(mockGateManager.getGate(11)).thenReturn(gate);

        boolean result = gateCommand.executeAdminToggleActive(mockSender, new String[]{"11"});

        assertTrue(result);
        assertTrue(gate.isActive());
        verify(mockGateDoorsApi).updateOperationalSettings(11, true, true);
    }

    @Test
    void testExecuteAdminToggleInvincible_ResolvesMultiWordName() {
        CachedGateDoor gate = createTestGate(11, "Keep Gate Test", true, false);
        when(mockGateManager.getGateByName("Keep Gate Test")).thenReturn(gate);

        boolean result = gateCommand.executeAdminToggleInvincible(mockSender, new String[]{"Keep", "Gate", "Test"});

        assertTrue(result);
        assertFalse(gate.isInvincible());
        verify(mockGateDoorsApi).updateOperationalSettings(11, true, false);
    }

    @Test
    void testExecuteAdminHealth_InvalidValue() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeAdminHealth(mockSender, new String[]{"TestGate", "invalid"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Invalid health")));
    }

    @Test
    void testExecuteAdminRepair_Success() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, true);
        gate.setHealthCurrent(100.0);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeAdminRepair(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertEquals(500.0, gate.getHealthCurrent());
        assertFalse(gate.isDestroyed());
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Repaired gate")));
    }

    @Test
    void testExecuteAdminReload() {
        // Mock the CompletableFuture return from reloadGates()
        when(mockGateManager.reloadGates()).thenReturn(
            java.util.concurrent.CompletableFuture.completedFuture(null)
        );

        boolean result = gateCommand.executeAdminReload(mockSender, new String[0]);

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Reloading")));
        verify(mockGateManager).reloadGates();
    }

    @Test
    void testExecuteAdminTeleport_Success() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);
        when(mockPlayer.getWorld()).thenReturn(mock(org.bukkit.World.class));

        boolean result = gateCommand.executeAdminTeleport(mockPlayer, new String[]{"TestGate"});

        assertTrue(result);
        verify(mockPlayer).teleport(any(org.bukkit.Location.class));
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Teleported")));
    }

    @Test
    void testExecuteAdminTeleport_NotPlayer() {
        CachedGateDoor gate = createTestGate(1, "TestGate", true, false);
        when(mockGateManager.getGateByName("TestGate")).thenReturn(gate);

        boolean result = gateCommand.executeAdminTeleport(mockSender, new String[]{"TestGate"});

        assertTrue(result);
        assertTrue(sentMessages.stream().anyMatch(m -> m.contains("Only players")));
    }

    // ===== Helper Methods =====

    /**
     * Create a test gate with sensible defaults.
     */
    private CachedGateDoor createTestGate(int id, String name, boolean isActive, boolean isDestroyed) {
        CachedGateDoor gate = new CachedGateDoor(
            id,
            id,
            name,
            "SLIDING",
            "VERTICAL",
            "PLANE_GRID",
            60,
            1,
            new Vector(100, 64, 100),
            5,
            3,
            1,
            500.0,
            500.0,
            isActive,
            isDestroyed,
            true,
            90,
            "north"
        );
        gate.setCurrentState(AnimationState.CLOSED);
        return gate;
    }
}
