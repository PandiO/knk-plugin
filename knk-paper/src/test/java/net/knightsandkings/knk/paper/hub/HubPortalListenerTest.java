package net.knightsandkings.knk.paper.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.hub.HubOutageStore;
import net.knightsandkings.knk.core.hub.HubOutageStore.ReturnRecord;

/** Hub DESIGN §11 and the decision of 2026-10-12: a WorldGuard region in the hub takes players into the game. */
class HubPortalListenerTest {

    // Location only holds its World through a WeakReference: keep the mocks strongly reachable.
    private final World hubWorld = mock(World.class);
    private final World gameplay = mock(World.class);
    private final Set<UUID> loading = new HashSet<>();
    private final List<Location> moved = new ArrayList<>();
    private final HubOutageStore store = new HubOutageStore(null);
    private Location destination;
    private Optional<String> refusal = Optional.empty();
    private long now = 10_000L;
    private HubService hub;
    private HubOutageService outage;
    private HubPortalListener portals;

    @BeforeEach
    void setUp() {
        when(hubWorld.getName()).thenReturn("hub");
        when(gameplay.getName()).thenReturn("world");
        destination = new Location(gameplay, 100, 64, 100);
        KnkSpawnReference spawn = new KnkSpawnReference(KnkSpawnReference.SourceType.LOCATION, 9, "Hub square",
            new KnkLocation(9, "Hub square", 2.5, 65.0, 2.5, 0f, 0f, "hub"));
        KnkGameSettings settings = new KnkGameSettings("WorldSpawn", null, null, null, null, List.of(), null, null, List.of(),
            new KnkHubSettings(4, "Hubtown", "Town", "hub", "town_4", spawn, KnkHubSettings.OutageAction.SEND_TO_HUB, 0,
                KnkHubRules.defaults(), List.of("Portal_North")));
        Map<String, World> worlds = Map.of("hub", hubWorld, "world", gameplay);
        // The hub: x < 100. The portal: 10 <= x < 20 inside it.
        hub = new HubService(() -> settings, name -> worlds.get(name.toLowerCase()), (world, region) -> world == hubWorld,
            location -> {
                if (location.getWorld() != hubWorld || location.getX() >= 100) {
                    return Set.of();
                }
                return location.getX() >= 10 && location.getX() < 20 ? Set.of("town_4", "portal_north") : Set.of("town_4");
            });
        outage = new HubOutageService(hub, () -> settings, uuid -> List.of(), player -> ActiveMode.NONE, store, () -> true,
            new net.knightsandkings.knk.core.minigame.MinigameRegistry(), player -> { }, (player, to) -> true, Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC));
        outage.useForTests(List::of, worlds::get, (world, at) -> Optional.of(at));
        portals = new HubPortalListener(hub, outage, player -> !loading.contains(player.getUniqueId()), player -> destination,
            (player, at) -> refusal, (player, to) -> moved.add(to), player -> { }, () -> now);
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alice");
        return player;
    }

    @Test
    void walkingIntoThePortalTakesThePlayerToTheirJoinSpawnAndEndsTheReturnOffer() {
        Player alice = player();
        store.saveIfAbsent(new ReturnRecord(alice.getUniqueId(), "world", 1, 64, 1, 0f, 0f, Instant.EPOCH));

        portals.onMove(new PlayerMoveEvent(alice, new Location(hubWorld, 9.5, 65, 5), new Location(hubWorld, 10.5, 65, 5)));

        assertEquals(List.of(destination), moved);
        assertTrue(store.returnRecord(alice.getUniqueId()).isEmpty());
    }

    @Test
    void onlyEnteringThePortalCounts() {
        Player alice = player();
        portals.onMove(new PlayerMoveEvent(alice, new Location(hubWorld, 10.5, 65, 5), new Location(hubWorld, 11.5, 65, 5)));
        portals.onMove(new PlayerMoveEvent(alice, new Location(hubWorld, 30.5, 65, 5), new Location(hubWorld, 31.5, 65, 5)));
        assertTrue(moved.isEmpty());
    }

    @Test
    void theApiAndTheAccountMustBeReadyAndTheDestinationOpen() {
        Player alice = player();
        outage.connectivityChanged(ApiConnectivityState.DOWN);
        assertEquals(HubPortalListener.Outcome.SERVICES_DOWN, portals.use(alice, "portal_north"));
        assertEquals(HubPortalListener.Outcome.COOLDOWN, portals.use(alice, "portal_north"));

        outage.connectivityChanged(ApiConnectivityState.UP);
        now += HubPortalListener.COOLDOWN_MILLIS;
        loading.add(alice.getUniqueId());
        assertEquals(HubPortalListener.Outcome.ACCOUNT_LOADING, portals.use(alice, "portal_north"));

        loading.clear();
        now += HubPortalListener.COOLDOWN_MILLIS;
        refusal = Optional.of("Oakhaven is closed to visitors.");
        assertEquals(HubPortalListener.Outcome.REFUSED, portals.use(alice, "portal_north"));

        refusal = Optional.empty();
        now += HubPortalListener.COOLDOWN_MILLIS;
        destination = new Location(hubWorld, 5, 65, 5);
        assertEquals(HubPortalListener.Outcome.NO_DESTINATION, portals.use(alice, "portal_north"), "a join spawn inside the hub");
        assertTrue(moved.isEmpty());
    }
}
