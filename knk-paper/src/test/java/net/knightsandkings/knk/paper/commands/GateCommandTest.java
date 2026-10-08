package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.user.UserManager;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GateCommand - the structure layer of the gate commands (/gate, /knk gate; KNG-77): every
 * subcommand acts on a gate structure and all of its doors.
 */
class GateCommandTest {
    private GateCommand gateCommand;
    private GateManager gateManager;
    private GateStructuresApi structuresApi;
    private GateDoorsApi doorsApi;
    private CommandSender sender;
    /** Permission checks only apply to players (the console always passes, see CommandPermissions). */
    private org.bukkit.entity.Player player;
    private List<String> messages;
    private Set<String> granted;

    private CachedGateStructure north;
    private CachedGateDoor left;
    private CachedGateDoor right;

    @BeforeEach
    void setUp() {
        gateManager = mock(GateManager.class);
        structuresApi = mock(GateStructuresApi.class);
        doorsApi = mock(GateDoorsApi.class);
        gateCommand = new GateCommand(gateManager, structuresApi, doorsApi, mock(UserManager.class),
            mock(UsersCommandApi.class), mock(DistrictGateLoader.class), null);

        sender = mock(CommandSender.class);
        messages = new ArrayList<>();
        doAnswer(inv -> messages.add(inv.getArgument(0))).when(sender).sendMessage(anyString());
        granted = null; // null = everything granted
        when(sender.hasPermission(anyString())).thenAnswer(inv -> granted == null || granted.contains((String) inv.getArgument(0)));
        player = mock(org.bukkit.entity.Player.class);
        doAnswer(inv -> messages.add(inv.getArgument(0))).when(player).sendMessage(anyString());
        when(player.hasPermission(anyString())).thenAnswer(inv -> granted == null || granted.contains((String) inv.getArgument(0)));

        when(doorsApi.updateHealth(anyInt(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
        when(doorsApi.updateState(anyInt(), anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));
        when(structuresApi.updateOverrides(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(null));

        north = new CachedGateStructure(3, "North Gate");
        left = door(16, north, "Left", AnimationState.CLOSED);
        right = door(17, north, "Right", AnimationState.CLOSED);
        when(gateManager.getStructure(3)).thenReturn(north);
        when(gateManager.getStructureByName("North Gate")).thenReturn(north);
        when(gateManager.getDoorsForStructure(3)).thenReturn(List.of(right, left));
        when(gateManager.getGate(16)).thenReturn(left);
        when(gateManager.getGate(17)).thenReturn(right);
        when(gateManager.getAllStructures()).thenReturn(Map.of(3, north));
        when(gateManager.getAllGates()).thenReturn(Map.of(16, left, 17, right));
    }

    @Test
    void openOpensEveryDoorOfTheStructure() {
        when(gateManager.openGate(anyInt())).thenReturn(true);

        gateCommand.onCommand(sender, null, "gate", new String[]{"open", "North", "Gate"});

        verify(gateManager).openGate(16);
        verify(gateManager).openGate(17);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Opening gate 'North Gate' (#3): 2 doors moving")), messages::toString);
    }

    @Test
    void openSkipsDestroyedAndInactiveDoorsAndSaysSo() {
        right.setIsDestroyed(true);
        when(gateManager.openGate(16)).thenReturn(true);

        gateCommand.executeOpen(sender, new String[]{"3"});

        verify(gateManager).openGate(16);
        verify(gateManager, never()).openGate(17);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Skipped: Right (destroyed)")), messages::toString);
    }

    @Test
    void closeClosesEveryDoor() {
        left.setCurrentState(AnimationState.OPEN);
        right.setCurrentState(AnimationState.OPEN);
        when(gateManager.closeGate(anyInt())).thenReturn(true);

        gateCommand.executeClose(sender, new String[]{"3"});

        verify(gateManager).closeGate(16);
        verify(gateManager).closeGate(17);
    }

    @Test
    void toggleWithMixedStatesClosesAllDoors() {
        left.setCurrentState(AnimationState.OPEN);
        when(gateManager.closeGate(anyInt())).thenReturn(true);

        gateCommand.executeToggle(sender, new String[]{"3"});

        verify(gateManager).closeGate(16);
        verify(gateManager).closeGate(17);
        verify(gateManager, never()).openGate(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Toggling: closing gate 'North Gate'")), messages::toString);
    }

    @Test
    void toggleWithEveryDoorClosedOpensAll() {
        when(gateManager.openGate(anyInt())).thenReturn(true);

        gateCommand.executeToggle(sender, new String[]{"3"});

        verify(gateManager).openGate(16);
        verify(gateManager).openGate(17);
        verify(gateManager, never()).closeGate(anyInt());
    }

    @Test
    void toggleIgnoresAnInactiveOpenDoorWhenDeciding() {
        right.setCurrentState(AnimationState.OPEN);
        right.setIsActive(false);
        when(gateManager.openGate(anyInt())).thenReturn(true);

        gateCommand.executeToggle(sender, new String[]{"3"});

        verify(gateManager).openGate(16);
        verify(gateManager, never()).closeGate(anyInt());
    }

    @Test
    void openNeedsTheStructureNodeNotADoorNode() {
        granted = Set.of("knk.gatedoor.open.*", "knk.gate.open.4");

        gateCommand.executeOpen(player, new String[]{"3"});

        verify(gateManager, never()).openGate(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("permission to open gate 'North Gate'")), messages::toString);

        granted = Set.of("knk.gate.open.3");
        messages.clear();
        when(gateManager.openGate(anyInt())).thenReturn(true);
        gateCommand.executeOpen(player, new String[]{"3"});
        verify(gateManager).openGate(16);
    }

    @Test
    void gateAdminMayOpenWithoutTheOpenNodes() {
        granted = Set.of("knk.gate.admin");
        when(gateManager.openGate(anyInt())).thenReturn(true);

        gateCommand.executeOpen(player, new String[]{"3"});
        gateCommand.doorCommand().executeOpen(player, new String[]{"17"});

        verify(gateManager).openGate(16);
        verify(gateManager, times(2)).openGate(17);
    }

    @Test
    void doorAdminMayOpenADoorButNotTheWholeGate() {
        granted = Set.of("knk.gatedoor.admin");
        when(gateManager.openGate(anyInt())).thenReturn(true);

        gateCommand.doorCommand().executeOpen(player, new String[]{"16"});
        gateCommand.executeOpen(player, new String[]{"3"});

        verify(gateManager).openGate(16);
        verify(gateManager, never()).openGate(17);
    }

    @Test
    void aNumberThatUsedToBeADoorIdGetsANote() {
        CachedGateDoor otherGatesDoor = door(3, new CachedGateStructure(9, "South Gate"), "Main", AnimationState.CLOSED);
        when(gateManager.getGate(3)).thenReturn(otherGatesDoor);
        when(gateManager.openGate(anyInt())).thenReturn(true);

        gateCommand.executeOpen(sender, new String[]{"3"});

        verify(gateManager).openGate(16);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Note: /gate takes a gate structure id")
            && m.contains("/gatedoor open 3")), messages::toString);
    }

    @Test
    void aGateUnloadedBeforeThePermissionAnswerIsReported() {
        when(gateManager.getStructure(3)).thenReturn(north, (CachedGateStructure) null);

        gateCommand.executeOpen(sender, new String[]{"3"});

        verify(gateManager, never()).openGate(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("no longer loaded")), messages::toString);
    }

    @Test
    void hintsOnlyNameCommandsThatExist() {
        gateCommand.onCommand(sender, null, "gate", new String[]{"override", "16", "active", "true"});

        assertTrue(messages.stream().noneMatch(m -> m.contains("/gatedoor override")), messages::toString);
        assertTrue(messages.stream().anyMatch(m -> m.contains("/gate override 3")), messages::toString);
    }

    @Test
    void anIdThatIsADoorNotAStructurePointsToGatedoor() {
        gateCommand.executeOpen(sender, new String[]{"16"});

        verify(gateManager, never()).openGate(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Gate structure '16' not found")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("/gatedoor open 16")), messages::toString);
    }

    @Test
    void omittedTargetFromTheConsoleShowsUsage() {
        gateCommand.executeOpen(sender, new String[0]);

        assertTrue(messages.stream().anyMatch(m -> m.contains("Usage: /gate open")));
        verify(gateManager, never()).openGate(anyInt());
    }

    @Test
    void hereFromTheConsoleIsRefused() {
        gateCommand.executeOpen(sender, new String[]{"here"});

        assertTrue(messages.stream().anyMatch(m -> m.contains("Only players can use 'here'")));
    }

    @Test
    void repairRepairsEveryDoor() {
        left.setHealthCurrent(10);
        left.setIsDestroyed(true);
        right.setHealthCurrent(20);

        gateCommand.executeRepair(sender, new String[]{"North Gate"});

        assertEquals(500.0, left.getHealthCurrent());
        assertFalse(left.isDestroyed());
        assertEquals(500.0, right.getHealthCurrent());
        verify(gateManager).fireStateChanged(16);
        verify(gateManager).fireStateChanged(17);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Repaired gate 'North Gate' (#3): 2 doors")), messages::toString);
    }

    @Test
    void repairMentionsAStillActiveDestroyedOverride() {
        north.setIsDestroyedOverride(true);

        gateCommand.executeRepair(sender, new String[]{"3"});

        assertTrue(messages.stream().anyMatch(m -> m.contains("override destroyed=true still applies")));
    }

    @Test
    void repairNeedsGateAdmin() {
        granted = Set.of("knk.gatedoor.admin");
        left.setHealthCurrent(10);

        gateCommand.executeRepair(player, new String[]{"3"});

        assertEquals(10.0, left.getHealthCurrent());
        verify(gateManager, never()).fireStateChanged(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("don't have permission")));
    }

    @Test
    void infoListsTheDoors() {
        gateCommand.executeInfo(sender, new String[]{"3"});

        assertTrue(messages.stream().anyMatch(m -> m.contains("Gate Info: North Gate")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("#16 Left")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("#17 Right")));
    }

    @Test
    void listListsStructures() {
        gateCommand.executeList(sender, new String[0]);

        assertTrue(messages.stream().anyMatch(m -> m.contains("#3 North Gate") && m.contains("2 doors")), messages::toString);
    }

    @Test
    void reloadReloadsEveryGate() {
        when(gateManager.reloadGates()).thenReturn(CompletableFuture.completedFuture(null));

        gateCommand.onCommand(sender, null, "gate", new String[]{"reload"});

        verify(gateManager).reloadGates();
    }

    @Test
    void overrideStillWorksAndAcceptsMultiWordNames() {
        gateCommand.onCommand(sender, null, "gate", new String[]{"override", "North", "Gate", "destroyed", "true"});

        assertEquals(Boolean.TRUE, north.getIsDestroyedOverride());
        verify(structuresApi).updateOverrides(eq(3), any());
    }

    @Test
    void deprecatedAdminRepairStillRepairsOneDoorAndSaysWhatToUse() {
        left.setHealthCurrent(10);
        right.setHealthCurrent(10);

        gateCommand.onCommand(sender, null, "gate", new String[]{"admin", "repair", "16"});

        assertEquals(500.0, left.getHealthCurrent());
        assertEquals(10.0, right.getHealthCurrent());
        assertTrue(messages.stream().anyMatch(m -> m.contains("deprecated") && m.contains("/gatedoor repair")), messages::toString);
    }

    @Test
    void deprecatedAdminReloadRunsTheStructureReload() {
        when(gateManager.reloadGates()).thenReturn(CompletableFuture.completedFuture(null));

        gateCommand.onCommand(sender, null, "gate", new String[]{"admin", "reload"});

        verify(gateManager).reloadGates();
        assertTrue(messages.stream().anyMatch(m -> m.contains("use /gate reload")));
    }

    @Test
    void completionOffersSubcommandsHereAndStructures() {
        assertTrue(gateCommand.complete(sender, new String[]{"to"}).contains("toggle"));
        List<String> targets = gateCommand.complete(sender, new String[]{"toggle", ""});
        assertEquals("here", targets.get(0));
        assertTrue(targets.contains("3"));
        assertFalse(targets.contains("16"), "door ids belong to /gatedoor");
        assertEquals(List.of("destroyed"), gateCommand.complete(sender, new String[]{"override", "3", "des"}));
    }

    @Test
    void doorCompletionOffersHereAndDoors() {
        List<String> targets = gateCommand.doorCommand().complete(sender, new String[]{"repair", ""});
        assertEquals("here", targets.get(0));
        assertTrue(targets.containsAll(List.of("16", "Left", "17", "Right")));
        assertEquals(List.of("opened"), gateCommand.doorCommand().complete(sender, new String[]{"capture", "16", "op"}));
    }

    private static CachedGateDoor door(int id, CachedGateStructure structure, String name, AnimationState state) {
        CachedGateDoor door = new CachedGateDoor(id, structure.getId(), name, "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 3, 1, 500.0, 500.0, true, false, true, 90, "north");
        door.setStructure(structure);
        door.setCurrentState(state);
        return door;
    }
}
