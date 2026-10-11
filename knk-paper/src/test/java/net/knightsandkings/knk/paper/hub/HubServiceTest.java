package net.knightsandkings.knk.paper.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;

/**
 * Hub DESIGN §16 Phase 5: joining players go to the hub spawn, an unusable hub refuses joins, and the hub's rules hold
 * inside its region only.
 */
class HubServiceTest {

    // Location only holds its World through a WeakReference: keep the mocks strongly reachable.
    private final World hubWorld = mock(World.class);
    private final World gameplay = mock(World.class);
    private final Set<String> hubRegions = new HashSet<>(Set.of("town_4"));
    private KnkGameSettings settings;
    private HubService hub;

    @BeforeEach
    void setUp() {
        when(hubWorld.getName()).thenReturn("hub");
        when(gameplay.getName()).thenReturn("world");
        settings = withHub(new KnkHubSettings(4, "Hubtown", "Town", "hub", "town_4", spawn("hub"),
            KnkHubSettings.OutageAction.SEND_TO_HUB, 0, KnkHubRules.defaults()));
        Map<String, World> worlds = Map.of("hub", hubWorld, "world", gameplay);
        hub = new HubService(() -> settings, name -> worlds.get(name.toLowerCase()),
            (world, region) -> world == hubWorld && hubRegions.contains(region),
            // The region: x < 100, from y 0 up (a void hub's floor)
            location -> location.getX() < 100 && location.getY() >= 0 ? Set.of("TOWN_4", "inn_1") : Set.of());
    }

    private static KnkSpawnReference spawn(String world) {
        return new KnkSpawnReference(KnkSpawnReference.SourceType.LOCATION, 9, "Hub square",
            new KnkLocation(9, "Hub square", 2.5, 65.0, 2.5, 90f, 0f, world));
    }

    private static KnkGameSettings withHub(KnkHubSettings hub) {
        return new KnkGameSettings("WorldSpawn", null, null, null, null, List.of(), null, null, List.of(), hub);
    }

    @Test
    void aJoiningPlayerIsPlacedAtTheHubSpawn() {
        Location spawn = hub.admissionSpawn().orElseThrow();
        assertEquals(hubWorld, spawn.getWorld());
        assertEquals(2.5, spawn.getX());
        assertEquals(90f, spawn.getYaw());
        assertTrue(hub.unusable().isEmpty());
    }

    @Test
    void withoutAHubNothingChanges() {
        settings = withHub(KnkHubSettings.none());
        assertTrue(hub.admissionSpawn().isEmpty());
        assertTrue(hub.unusable().isEmpty());
        assertFalse(hub.inHub(new Location(hubWorld, 5, 65, 5)));
        settings = null;
        assertFalse(hub.configured());
    }

    @Test
    void aHubWithoutItsRegionOrWorldRefusesJoinsExceptForOperators() {
        hubRegions.clear();
        assertEquals("the hub region 'town_4' does not exist in world 'hub'", hub.unusable().orElseThrow());
        assertTrue(hub.admissionSpawn().isEmpty(), "never into an unprotected hub");
        assertTrue(HubAdmissionListener.refusal(hub.unusable(), false).contains("hub is not available"));
        assertNull(HubAdmissionListener.refusal(hub.unusable(), true));

        settings = withHub(new KnkHubSettings(4, "Hubtown", "Town", "lobby", "town_4", spawn("lobby"),
            KnkHubSettings.OutageAction.SEND_TO_HUB, 0, KnkHubRules.defaults()));
        assertEquals("the hub world 'lobby' is not loaded", hub.unusable().orElseThrow());
        assertNull(HubAdmissionListener.refusal(Optional.empty(), false));
    }

    @Test
    void aSpawnOutsideTheHubRegionRefusesJoins() {
        // In the hub's world, but past the region's edge (x >= 100): a join there would be unprotected.
        settings = withHub(new KnkHubSettings(4, "Hubtown", "Town", "hub", "town_4",
            new KnkSpawnReference(KnkSpawnReference.SourceType.LOCATION, 9, "Far field",
                new KnkLocation(9, "Far field", 150.0, 65.0, 2.5, 0f, 0f, "hub")),
            KnkHubSettings.OutageAction.SEND_TO_HUB, 0, KnkHubRules.defaults()));

        assertEquals("the hub spawn is outside the hub region 'town_4'", hub.unusable().orElseThrow());
        assertTrue(hub.admissionSpawn().isEmpty());
    }

    @Test
    void theEntityDamageRuleHoldsForBuildersToo() {
        Player builder = player(new Location(hubWorld, 5, 65, 5));
        org.bukkit.entity.Villager npc = mock(org.bukkit.entity.Villager.class);
        when(npc.getLocation()).thenReturn(new Location(hubWorld, 6, 65, 5));
        org.bukkit.event.entity.EntityDamageByEntityEvent hit = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(npc);
        when(hit.getDamager()).thenReturn(builder);
        when(hit.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        new HubRulesListener(hub, p -> p == builder).onDamage(hit);

        verify(hit).setCancelled(true);
    }

    @Test
    void onlyTheHubsRegionInTheHubsWorldIsTheHub() {
        assertTrue(hub.inHub(new Location(hubWorld, 5, 65, 5)), "region ids are case-insensitive");
        assertFalse(hub.inHub(new Location(hubWorld, 500, 65, 5)), "outside the region");
        assertFalse(hub.inHub(new Location(gameplay, 5, 65, 5)), "the same region id in another world");
    }

    @Test
    void inTheHubBuildingDroppingAndDamageFollowTheRules() {
        Player visitor = player(new Location(hubWorld, 5, 65, 5));
        Player builder = player(new Location(hubWorld, 5, 65, 5));
        HubRulesListener rules = new HubRulesListener(hub, p -> p == builder);

        BlockBreakEvent visitorBreak = new BlockBreakEvent(block(new Location(hubWorld, 6, 64, 5)), visitor);
        rules.onBreak(visitorBreak);
        assertTrue(visitorBreak.isCancelled(), "building is off by default");
        BlockBreakEvent builderBreak = new BlockBreakEvent(block(new Location(hubWorld, 6, 64, 5)), builder);
        rules.onBreak(builderBreak);
        assertFalse(builderBreak.isCancelled(), "knk.hub.bypass builds the hub");
        BlockBreakEvent outside = new BlockBreakEvent(block(new Location(hubWorld, 600, 64, 5)), visitor);
        rules.onBreak(outside);
        assertFalse(outside.isCancelled(), "outside the hub region nothing changes");

        PlayerDropItemEvent drop = new PlayerDropItemEvent(visitor, mock(org.bukkit.entity.Item.class));
        rules.onDrop(drop);
        assertFalse(drop.isCancelled(), "dropping is allowed by default");

        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getEntity()).thenReturn(visitor);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        rules.onDamage(fall);
        verify(fall).setCancelled(true);
    }

    @Test
    void fallingIntoTheVoidOfTheHubReturnsToItsSpawn() {
        Player visitor = player(new Location(hubWorld, 5, -70, 5));
        EntityDamageEvent voidDamage = mock(EntityDamageEvent.class);
        when(voidDamage.getEntity()).thenReturn(visitor);
        when(voidDamage.getCause()).thenReturn(EntityDamageEvent.DamageCause.VOID);

        new HubRulesListener(hub, p -> false).onDamage(voidDamage);

        verify(voidDamage).setCancelled(true);
        verify(visitor).teleport(hub.admissionSpawn().orElseThrow());
    }

    @Test
    void aBarrelIsStorageNotADoor() {
        // A barrel's block data is Openable (Paper 1.21.10), like a door's.
        Block barrel = mock(Block.class);
        when(barrel.getState()).thenReturn(mock(org.bukkit.block.Barrel.class));
        when(barrel.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Barrel.class));
        Block door = mock(Block.class);
        when(door.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));
        when(door.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Door.class));

        assertEquals(HubRulesListener.BlockUse.STORAGE,
            HubRulesListener.classify(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, barrel));
        assertEquals(HubRulesListener.BlockUse.DOOR,
            HubRulesListener.classify(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, door));
    }

    private static Player player(Location at) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(at);
        return player;
    }

    private static Block block(Location at) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(at);
        return block;
    }
}
