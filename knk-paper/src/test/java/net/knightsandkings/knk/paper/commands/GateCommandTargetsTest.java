package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.gates.DistrictGateLoader;
import net.knightsandkings.knk.paper.gates.GateTargeting;
import net.knightsandkings.knk.paper.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The gate commands' implicit targets: {@code here} (KNG-78) and look-at (KNG-79), at both layers.
 *
 * <p>Layout (one world): gate #3 "North Gate" with door #16 "Left" (blocks x 10..12, y 64..67,
 * z 20) and door #17 "Right" (x 14..16); gate #5 "East Gate" with door #30 (x 40..42).
 */
class GateCommandTargetsTest {
    // Kept in a field: Location holds its world only weakly (see knk-plugin CLAUDE.md).
    private final World world = mock(World.class);
    private GateManager gateManager;
    private GateCommand gateCommand;
    private GateDoorCommand doorCommand;
    private Player player;
    private List<String> messages;
    private List<Component> components;
    private Set<String> granted;
    private CachedGateDoor left;
    private CachedGateDoor right;
    private CachedGateDoor east;

    @BeforeEach
    void setUp() {
        gateManager = mock(GateManager.class);
        GateDoorsApi doorsApi = mock(GateDoorsApi.class);
        when(doorsApi.updateHealth(anyInt(), anyDouble())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(doorsApi.updateState(anyInt(), anyString(), anyBoolean())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        gateCommand = new GateCommand(gateManager, mock(GateStructuresApi.class), doorsApi, mock(UserManager.class),
            mock(UsersCommandApi.class), mock(DistrictGateLoader.class), null);
        doorCommand = gateCommand.doorCommand();

        when(world.getName()).thenReturn("world");
        player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        messages = new ArrayList<>();
        components = new ArrayList<>();
        doAnswer(inv -> messages.add(inv.getArgument(0))).when(player).sendMessage(anyString());
        doAnswer(inv -> components.add(inv.getArgument(0))).when(player).sendMessage(any(Component.class));
        granted = null;
        when(player.hasPermission(anyString())).thenAnswer(inv -> granted == null || granted.contains((String) inv.getArgument(0)));

        CachedGateStructure north = new CachedGateStructure(3, "North Gate");
        CachedGateStructure eastGate = new CachedGateStructure(5, "East Gate");
        left = door(16, north, "Left");
        right = door(17, north, "Right");
        east = door(30, eastGate, "Main");
        Map<Integer, CachedGateDoor> doors = new HashMap<>(Map.of(16, left, 17, right, 30, east));
        when(gateManager.getAllGates()).thenReturn(doors);
        doors.values().forEach(door -> when(gateManager.getGate(door.getId())).thenReturn(door));
        when(gateManager.getStructure(3)).thenReturn(north);
        when(gateManager.getStructure(5)).thenReturn(eastGate);
        when(gateManager.getDoorsForStructure(3)).thenReturn(List.of(left, right));
        when(gateManager.getDoorsForStructure(5)).thenReturn(List.of(east));
        when(gateManager.closedFootprint(16)).thenReturn(wall(10, 12));
        when(gateManager.closedFootprint(17)).thenReturn(wall(14, 16));
        when(gateManager.closedFootprint(30)).thenReturn(wall(40, 42));
        when(gateManager.openGate(anyInt())).thenReturn(true);
        when(gateManager.closeGate(anyInt())).thenReturn(true);
    }

    // === here ===

    @Test
    void hereWithOneDoorInRangeUsesIt() {
        settings(4.2, false, true);
        standAt(11.5, 64, 25); // 4 from Left's face, 4.7 from Right

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open", "here"});

        verify(gateManager).openGate(16);
        verify(gateManager, never()).openGate(17);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Using the nearest door: door 'Left' (#16) of gate 'North Gate'")), messages::toString);
    }

    @Test
    void hereWithSeveralDoorsOffersClickableChoicesSortedByDistance() {
        standAt(11.5, 64, 25);

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"health", "here", "250"});

        verify(gateManager, never()).openGate(anyInt());
        assertEquals(500.0, left.getHealthCurrent());
        assertEquals(List.of("/knk gatedoor health 16 250", "/knk gatedoor health 17 250"), clickCommands());
    }

    @Test
    void hereWithAmbiguityNearestPicksTheClosest() {
        settings(15, true, true);
        standAt(11.5, 64, 25);

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"close", "here"});

        verify(gateManager).closeGate(16);
        assertTrue(clickCommands().isEmpty());
    }

    @Test
    void hereWithNothingInRangeNamesTheRadius() {
        standAt(200, 64, 200);

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open", "here"});

        verify(gateManager, never()).openGate(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("No gate door within 15 blocks")), messages::toString);
    }

    @Test
    void standingInsideTheOpeningCountsAsDistanceZero() {
        settings(0.5, false, true);
        standAt(11.5, 64, 20.5);

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open", "here"});

        verify(gateManager).openGate(16);
    }

    @Test
    void structureHereTreatsSeveralDoorsOfOneGateAsOneCandidate() {
        standAt(13.5, 64, 25); // both North Gate doors in range, East Gate far away

        gateCommand.onCommand(player, null, "gate", new String[]{"toggle", "here"});

        verify(gateManager).openGate(16);
        verify(gateManager).openGate(17);
        verify(gateManager, never()).openGate(30);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Using the nearest gate: gate 'North Gate' (#3)")), messages::toString);
    }

    @Test
    void structureHereWithTwoGatesOffersTheGates() {
        settings(40, false, true);
        standAt(20, 64, 25);

        gateCommand.onCommand(player, null, "gate", new String[]{"repair", "here"});

        verify(gateManager, never()).fireStateChanged(anyInt());
        assertEquals(List.of("/knk gate repair 3", "/knk gate repair 5"), clickCommands());
    }

    @Test
    void aClickedChoiceStillChecksPermissionForThatDoor() {
        granted = Set.of("knk.gatedoor.open.16");

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open", "17"});

        verify(gateManager, never()).openGate(anyInt());
        assertTrue(messages.stream().anyMatch(m -> m.contains("permission to open door 'Right'")), messages::toString);
    }

    @Test
    void aStructureGrantCoversItsDoors() {
        granted = Set.of("knk.gate.open.3");

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open", "17"});
        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open", "30"});

        verify(gateManager).openGate(17);
        verify(gateManager, never()).openGate(30);
    }

    // === look-at ===

    @Test
    void omittedDoorUsesTheClosedDoorBeingLookedAt() {
        lookNorthFrom(11.5, 64, 30, 9.0); // the ray hits Left's block 9 away

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open"});

        verify(gateManager).openGate(16);
        verify(gateManager, never()).openGate(17);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Using the door you're looking at: door 'Left' (#16)")), messages::toString);
    }

    @Test
    void omittedDoorFindsAnOpenDoorThroughItsEmptyOpening() {
        left.setCurrentState(AnimationState.OPEN);
        lookNorthFrom(11.5, 64, 30, null); // nothing solid within reach: the opening is empty

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"toggle"});

        verify(gateManager).closeGate(16);
    }

    @Test
    void omittedStructureUsesTheGateBeingLookedAt() {
        lookNorthFrom(15.5, 64, 30, null);

        gateCommand.onCommand(player, null, "gate", new String[]{"close"});

        verify(gateManager).closeGate(16);
        verify(gateManager).closeGate(17);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Using the gate you're looking at: gate 'North Gate' (#3)")));
    }

    @Test
    void aWallInFrontHidesTheDoorAndHereIsUsedInstead() {
        settings(15, false, true);
        lookNorthFrom(11.5, 64, 30, 2.0); // hits a wall 2 blocks away; 9-10 blocks from both doors

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"open"});

        verify(gateManager, never()).openGate(anyInt());
        assertEquals(List.of("/knk gatedoor open 16", "/knk gatedoor open 17"), clickCommands());
    }

    @Test
    void lookAtCanBeSwitchedOffInConfig() {
        settings(15, true, false);
        lookNorthFrom(15.5, 64, 22, null); // looking at Right, but nearest is Left/Right at 1 block

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"info"});

        verify(world, never()).rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Using the nearest door")));
    }

    @Test
    void nothingInSightOrNearShowsTheUsage() {
        lookNorthFrom(200, 64, 200, null);

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"repair"});

        assertTrue(messages.stream().anyMatch(m -> m.contains("Usage: /gatedoor repair")), messages::toString);
        assertTrue(messages.stream().anyMatch(m -> m.contains("No gate door in sight or within 15 blocks")));
    }

    @Test
    void commandsThatChangeSettingsNeverInferATarget() {
        lookNorthFrom(11.5, 64, 30, 9.0);

        doorCommand.onCommand(player, null, "gatedoor", new String[]{"active"});
        doorCommand.onCommand(player, null, "gatedoor", new String[]{"tp"});

        assertTrue(left.isActive());
        verify(player, never()).teleport(any(Location.class));
        verify(world, never()).rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean());
    }

    @Test
    void tabCompletionNeverRayTraces() {
        lookNorthFrom(11.5, 64, 30, 9.0);

        doorCommand.complete(player, new String[]{"open", ""});
        gateCommand.complete(player, new String[]{"open", ""});

        verify(world, never()).rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean());
        verify(gateManager, never()).closedFootprint(anyInt());
    }

    // === helpers ===

    private void settings(double radius, boolean nearest, boolean lookAt) {
        gateCommand.setTargetingSettings(new GateTargeting.Settings(radius, nearest, lookAt, 12));
    }

    private void standAt(double x, double y, double z) {
        when(player.getLocation()).thenReturn(new Location(world, x, y, z));
    }

    /** Standing at (x, y, z) facing north (-z); {@code blockHit} = distance to the first solid block, null = none. */
    private void lookNorthFrom(double x, double y, double z, Double blockHit) {
        standAt(x, y, z);
        Location eye = new Location(world, x, y + 1.62, z, 180f, 0f);
        when(player.getEyeLocation()).thenReturn(eye);
        RayTraceResult hit = blockHit == null ? null : new RayTraceResult(new Vector(x, y + 1.62, z - blockHit));
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true)))
            .thenReturn(hit);
    }

    private List<String> clickCommands() {
        return components.stream()
            .map(Component::clickEvent)
            .filter(java.util.Objects::nonNull)
            .filter(click -> click.action() == ClickEvent.Action.RUN_COMMAND)
            .map(ClickEvent::value)
            .toList();
    }

    /** A door wall's closed blocks: x from..to, y 64..67, z 20. */
    private static List<Vector> wall(int fromX, int toX) {
        List<Vector> blocks = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            for (int y = 64; y <= 67; y++) {
                blocks.add(new Vector(x, y, 20));
            }
        }
        return blocks;
    }

    private static CachedGateDoor door(int id, CachedGateStructure structure, String name) {
        CachedGateDoor door = new CachedGateDoor(id, structure.getId(), name, "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 5, 3, 1, 500.0, 500.0, true, false, true, 90, "north");
        door.setStructure(structure);
        door.setCurrentState(AnimationState.CLOSED);
        door.setWorldName("world");
        return door;
    }
}
