package net.knightsandkings.knk.paper.navigation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.model.ModelFreshness;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.paper.navigation.engine.PlayerNavigationEngine;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeService;

/**
 * A fake destination engine for the phase 3 command and compass tests (PandiO/knk-workspace#69, DESIGN § 12): the
 * real {@code DestinationEngine} with the initial scope table over a fixed world catalogue and a switchable siege
 * match, and a player whose mode, nodes, freeze and siege state the test sets. No server: the player and the world
 * are mocks and no {@code Location} is built.
 */
final class EngineFixture {

    static final String WORLD = "world";
    static final int TEAM = 2;

    final UUID id = UUID.randomUUID();
    final World world = mock(World.class);
    final Player player = mock(Player.class);
    final SiegeService siege = mock(SiegeService.class);
    final Set<String> nodes = new HashSet<>(Set.of(InitialScopes.NAVIGATE_NODE));
    final AtomicReference<ActiveMode> mode = new AtomicReference<>(ActiveMode.NONE);
    final AtomicReference<Boolean> frozen = new AtomicReference<>(false);
    final AtomicReference<CompassSettings> compass = new AtomicReference<>(
        new CompassSettings(CompassItemRule.defaults(), true, true, List.of("Main Street", "Kardenna spawn", "Nowhere")));
    final AtomicReference<ModelFreshness> freshness = new AtomicReference<>(null);
    final List<Candidate> world_ = new ArrayList<>(List.of(
        domain(1, "Kardenna", "town", new Place.Point(WORLD, 10, 64, 10), "kardenna"),
        domain(2, "Market", "district", null, "market"),
        domain(3, "Market", "town", new Place.Point(WORLD, 30, 64, 30), null),
        domain(4, "Farland", "town", new Place.Point("other_world", 1, 64, 1), "farland"),
        domain(5, "Empty Lot", "structure", null, null),
        new Candidate("street:5", "Main Street", "street", List.of(), 5, null, 2, "catalogue", new Place.Street(WORLD, 5))));
    final List<Candidate> match = new ArrayList<>(List.of(
        new Candidate("objective:7", "Keep Gate", "objective", List.of(), 7, "capture", 0, "siege-match",
            new Place.Point(WORLD, 100, 64, 100)),
        new Candidate("objective:8", "Outer Wall", "objective", List.of(), 8, "capture", 1, "siege-match",
            new Place.Point(WORLD, 120, 64, 100)),
        new Candidate("spawn:20", "Raiders spawn", "spawn", List.of(), 20, "your spawn", 2, "siege-match",
            new Place.Point(WORLD, 200, 64, 200))));
    private boolean inMatch;
    private boolean inLobby;
    final SiegeMatch runningMatch = mock(SiegeMatch.class);
    final PlayerNavigationEngine engine;

    EngineFixture() {
        when(world.getName()).thenReturn(WORLD);
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Pandi");
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(NavigateCommand.NODE)).thenAnswer(inv -> nodes.contains(NavigateCommand.NODE));
        KnkSiegeScenario scenario = mock(KnkSiegeScenario.class);
        when(scenario.id()).thenReturn(1);
        when(runningMatch.scenario()).thenReturn(scenario);
        when(runningMatch.roster()).thenReturn(new SiegeMatchRoster(Map.of(TEAM, List.of(id))));
        when(runningMatch.matchToken()).thenReturn("m1");
        when(siege.runningMatchOf(id)).thenAnswer(inv -> inMatch ? Optional.of(runningMatch) : Optional.empty());
        when(siege.lobbyOf(id)).thenAnswer(inv -> inMatch || inLobby ? Optional.of(mock(SiegeLobbyRuntime.class)) : Optional.empty());
        engine = PlayerNavigationEngine.create(List.of(Provider.of("catalogue", ctx -> List.copyOf(world_))),
            Provider.of("siege-match", ctx -> inMatch ? List.copyOf(match) : List.of()),
            new PlayerNavigationEngine.Sources(uuid -> false, uuid -> frozen.get(), p -> mode.get(),
                (p, node) -> nodes.contains(node), () -> siege, compass::get, () -> 3L, freshness::get));
    }

    static Candidate domain(int id, String name, String type, Place.Point spawn, String region) {
        String capitalized = Character.toUpperCase(type.charAt(0)) + type.substring(1);
        return new Candidate("domain:" + capitalized + ":" + id, name, type, List.of(), id, null, 0, "catalogue",
            new Place.Domain(spawn, region, Mode.SPAWN));
    }

    EngineFixture inMatch() {
        inMatch = true;
        return this;
    }

    EngineFixture inLobby() {
        inLobby = true;
        return this;
    }

    EngineFixture outOfSiege() {
        inMatch = false;
        inLobby = false;
        return this;
    }
}
