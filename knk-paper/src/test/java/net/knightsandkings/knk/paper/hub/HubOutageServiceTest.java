package net.knightsandkings.knk.paper.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings.OutageAction;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;
import net.knightsandkings.knk.core.hub.HubOutageStore;

/** Hub DESIGN §12, §13 and decisions 2026-10-12: the outage actions, exemptions, Siege release and the return. */
class HubOutageServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");
    private static final PermissionGroupRef STAFF = new PermissionGroupRef(3, "Staff");
    private static final PermissionGroupRef NOBLE = new PermissionGroupRef(2, "Noble");

    // Location only holds its World through a WeakReference: keep the mocks strongly reachable.
    private final World hubWorld = mock(World.class);
    private final World gameplay = mock(World.class);
    private final Map<UUID, List<PermissionGroupRef>> groups = new java.util.HashMap<>();
    private final Map<UUID, ActiveMode> modes = new java.util.HashMap<>();
    private final Set<UUID> inMatchEnding = new HashSet<>();
    private final List<Player> online = new ArrayList<>();
    private final List<Player> settled = new ArrayList<>();
    private final List<Location> transfers = new ArrayList<>();
    private final HubOutageStore store = new HubOutageStore(null);
    private Instant now = T0;
    private HubService hub;
    private HubOutageService outage;

    @BeforeEach
    void setUp() {
        when(hubWorld.getName()).thenReturn("hub");
        when(gameplay.getName()).thenReturn("world");
        when(gameplay.getSpawnLocation()).thenReturn(new Location(gameplay, 0, 70, 0));
        KnkSpawnReference spawn = new KnkSpawnReference(KnkSpawnReference.SourceType.LOCATION, 9, "Hub square",
            new KnkLocation(9, "Hub square", 2.5, 65.0, 2.5, 0f, 0f, "hub"));
        KnkGameSettings settings = new KnkGameSettings("WorldSpawn", null, null, null, null, List.of(), null, null,
            List.of(
                new KnkGroupOverride(3, "Staff", 1, null, null, null, null, false, null, null),
                new KnkGroupOverride(2, "Noble", 2, null, null, null, null, false, OutageAction.KICK, 0)),
            new KnkHubSettings(4, "Hubtown", "Town", "hub", "town_4", spawn, OutageAction.SEND_TO_HUB, 60, KnkHubRules.defaults()));
        Map<String, World> worlds = Map.of("hub", hubWorld, "world", gameplay);
        hub = new HubService(() -> settings, name -> worlds.get(name.toLowerCase()), (world, region) -> world == hubWorld,
            location -> location.getWorld() == hubWorld && location.getX() < 100 ? Set.of("town_4") : Set.of());
        outage = new HubOutageService(hub, () -> settings, uuid -> groups.getOrDefault(uuid, List.of()),
            player -> modes.getOrDefault(player.getUniqueId(), ActiveMode.NONE), store, () -> true,
            uuid -> !inMatchEnding.contains(uuid), settled::add, (player, to) -> transfers.add(to) && player.teleport(to),
            Clock.fixed(T0, ZoneOffset.UTC));
        outage.useForTests(() -> online, name -> worlds.get(name), (world, at) -> at.getY() > 0 ? Optional.of(at) : Optional.empty());
    }

    private Player player(Location at, PermissionGroupRef... groupRefs) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("p" + id.toString().substring(0, 4));
        when(player.getLocation()).thenReturn(at);
        when(player.getWorld()).thenReturn(at.getWorld());
        when(player.teleport(any(Location.class))).thenReturn(true);
        groups.put(id, List.of(groupRefs));
        online.add(player);
        return player;
    }

    @Test
    void afterTheDelayAPlayerIsSentToTheHubAndTheirSpotIsSaved() {
        Player alice = player(new Location(gameplay, 500, 64, 500));
        outage.connectivityChanged(ApiConnectivityState.DOWN);

        outage.applyIfDue(alice, T0.plusSeconds(59));
        verify(alice, never()).teleport(any(Location.class));

        outage.applyIfDue(alice, T0.plusSeconds(60));
        verify(alice).teleport(hub.admissionSpawn().orElseThrow());
        assertEquals(List.of(hub.admissionSpawn().orElseThrow()), transfers, "through the plugin's transfer");
        assertEquals(List.of(alice), settled);
        assertEquals(500, store.returnRecord(alice.getUniqueId()).orElseThrow().x());
    }

    @Test
    void aGroupsActionAndDelayWinAndKickAlsoSavesTheSpot() {
        Player noble = player(new Location(gameplay, 10, 64, 10), NOBLE);

        outage.connectivityChanged(ApiConnectivityState.DOWN); // delay 0: at once

        verify(noble).kick(any());
        assertTrue(store.returnRecord(noble.getUniqueId()).isPresent());
    }

    @Test
    void anActiveModeIsExemptUntilTheyLeaveIt() {
        // Decision 2026-10-12: the mode alone exempts; Default has no ticked box. (Its delay is the hub's 60 s.)
        Player staff = player(new Location(gameplay, 10, 64, 10));
        modes.put(staff.getUniqueId(), ActiveMode.STAFF);
        outage.connectivityChanged(ApiConnectivityState.DOWN);

        outage.applyIfDue(staff, T0.plusSeconds(120));
        verify(staff, never()).teleport(any(Location.class));

        modes.put(staff.getUniqueId(), ActiveMode.NONE);
        outage.applyIfDue(staff, T0.plusSeconds(121));
        verify(staff).teleport(any(Location.class));
    }

    @Test
    void playersInTheHubAndAMatchThatIsEndingAreLeftForNow() {
        Player inHub = player(new Location(hubWorld, 5, 65, 5));
        Player ending = player(new Location(gameplay, 10, 64, 10));
        inMatchEnding.add(ending.getUniqueId());
        outage.connectivityChanged(ApiConnectivityState.DOWN);

        outage.applyIfDue(inHub, T0.plusSeconds(120));
        outage.applyIfDue(ending, T0.plusSeconds(120));

        verify(inHub, never()).teleport(any(Location.class));
        verify(ending, never()).teleport(any(Location.class));
        assertTrue(store.returnRecord(ending.getUniqueId()).isEmpty(), "saved only when they are moved");
    }

    @Test
    void theReturnNeedsTheServicesTheCurrentTokenAndASafeSpot() {
        Player alice = player(new Location(gameplay, 500, 64, 500));
        outage.connectivityChanged(ApiConnectivityState.DOWN);
        outage.applyIfDue(alice, T0.plusSeconds(60));
        long token = store.returnRecord(alice.getUniqueId()).orElseThrow().savedAt().toEpochMilli();
        HubOutageService.Access open = (player, at) -> Optional.empty();
        HubOutageService.Blocker free = player -> Optional.empty();
        transfers.clear();
        settled.clear();

        assertEquals(HubOutageService.ReturnOutcome.SERVICES_DOWN, outage.returnTo(alice, token, open, free));
        outage.connectivityChanged(ApiConnectivityState.UP);
        assertEquals(HubOutageService.ReturnOutcome.STALE, outage.returnTo(alice, token - 1, open, free));
        assertEquals(HubOutageService.ReturnOutcome.BLOCKED, outage.returnTo(alice, token, open, player -> Optional.of("You are frozen.")));
        assertEquals(HubOutageService.ReturnOutcome.RETURNED, outage.returnTo(alice, token, open, free));
        assertEquals(500, transfers.get(0).getX());
        assertEquals(List.of(alice), settled, "the destination world's game mode");
        assertEquals(HubOutageService.ReturnOutcome.NO_RECORD, outage.returnTo(alice, token, open, free), "no second return");
    }

    @Test
    void aSpotThePlayerMayNoLongerEnterIsRemovedNotReplaced() {
        Player alice = player(new Location(gameplay, 500, 64, 500));
        outage.connectivityChanged(ApiConnectivityState.DOWN);
        outage.applyIfDue(alice, T0.plusSeconds(60));
        long token = store.returnRecord(alice.getUniqueId()).orElseThrow().savedAt().toEpochMilli();
        outage.connectivityChanged(ApiConnectivityState.UP);

        assertEquals(HubOutageService.ReturnOutcome.INVALIDATED,
            outage.returnTo(alice, token, (player, at) -> Optional.of("Oakhaven is closed"), player -> Optional.empty()));
        assertTrue(store.returnRecord(alice.getUniqueId()).isEmpty());
    }

    @Test
    void anUnsafeSpotStaysSavedForLater() {
        Player alice = player(new Location(gameplay, 500, -10, 500));
        outage.connectivityChanged(ApiConnectivityState.DOWN);
        outage.applyIfDue(alice, T0.plusSeconds(60));
        long token = store.returnRecord(alice.getUniqueId()).orElseThrow().savedAt().toEpochMilli();
        outage.connectivityChanged(ApiConnectivityState.UP);

        assertEquals(HubOutageService.ReturnOutcome.UNSAFE,
            outage.returnTo(alice, token, (player, at) -> Optional.empty(), player -> Optional.empty()));
        assertTrue(store.returnRecord(alice.getUniqueId()).isPresent());
    }

    @Test
    void leavingTheHubAfterTheOutageEndsTheOfferButNotDuringIt() {
        Player alice = player(new Location(gameplay, 500, 64, 500));
        outage.connectivityChanged(ApiConnectivityState.DOWN);
        outage.applyIfDue(alice, T0.plusSeconds(60));
        Location inHub = new Location(hubWorld, 5, 65, 5);
        Location outside = new Location(hubWorld, 150, 65, 5);

        outage.leftHub(alice, inHub, outside);
        assertTrue(store.returnRecord(alice.getUniqueId()).isPresent(), "during the outage the action brings them back");

        outage.connectivityChanged(ApiConnectivityState.UP);
        outage.leftHub(alice, inHub, outside);
        assertTrue(store.returnRecord(alice.getUniqueId()).isEmpty());
    }

    @Test
    void aSpotInsideTheHubIsNeverSaved() {
        Player queued = player(new Location(hubWorld, 5, 65, 5));
        outage.saveReturn(queued, T0);
        assertTrue(store.returnRecord(queued.getUniqueId()).isEmpty(), "never a spot in the hub");
    }
}
