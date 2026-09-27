package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * KNG-28: the safe-zone lookup behind {@code SiegeService.combatantOf} - every team is safe around
 * each of its own spawnpoints and nowhere else, defenders and attackers alike.
 */
class SiegeSafeZoneTest {

    private static final String WORLD = "siege";
    private static final int DEFENDERS = 1;
    private static final int ATTACKERS = 2;

    // Defenders spawn at x=0; attackers have two spawnpoints, at x=100 and x=200. Radius 4 each.
    private static final double[] DEFENDER_SPAWN = {0, 64, 0};
    private static final double[] ATTACKER_SPAWN = {100, 64, 0};
    private static final double[] ATTACKER_SECOND_SPAWN = {200, 64, 0};
    private static final double[] FIELD = {50, 64, 0};

    // Held strongly: Location keeps its world only through a WeakReference.
    private World world;
    private MockedStatic<Bukkit> bukkit;
    private SiegeMatch match;
    private final UUID defenderId = UUID.randomUUID();
    private final UUID attackerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld(WORLD)).thenReturn(world);

        KnkSiegeTeam defenders = new KnkSiegeTeam(DEFENDERS, 0, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "BLUE",
                null, null, List.of(spawn(11, 0, DEFENDER_SPAWN)));
        KnkSiegeTeam attackers = new KnkSiegeTeam(ATTACKERS, 1, SiegeTeamRole.ATTACKER, 2, null, "Attackers", "RED",
                null, null, List.of(spawn(21, 0, ATTACKER_SPAWN), spawn(22, 1, ATTACKER_SECOND_SPAWN)));
        KnkSiegeScenario scenario = new KnkSiegeScenario(1, "Scenario", null, 1, "Town", null, List.of(), null,
                2, 20, null, null, KnkSiegeMatchLength.DEFAULT, null, false, false, false,
                List.of(defenders, attackers), List.of(), List.of(), List.of());
        match = new SiegeMatch(scenario, KnkSiegeConfiguration.legacyDefaults(), "token",
                Map.of(DEFENDERS, List.of(defenderId), ATTACKERS, List.of(attackerId)));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private static KnkSiegeSpawnpoint spawn(int id, int sortOrder, double[] at) {
        return new KnkSiegeSpawnpoint(id, sortOrder, "Spawn " + id,
                new KnkLocation(id, null, at[0], at[1], at[2], 0f, 0f, WORLD), 4.0);
    }

    private boolean safe(UUID playerId, double[] at, double dx) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(new Location(world, at[0] + dx, at[1], at[2]));
        return SiegeService.isInOwnSafeZone(player, match);
    }

    @Test
    void defendersAreSafeOnlyAroundTheirOwnSpawn() {
        assertTrue(safe(defenderId, DEFENDER_SPAWN, 0));
        assertTrue(safe(defenderId, DEFENDER_SPAWN, 3.5));
        assertFalse(safe(defenderId, DEFENDER_SPAWN, 4.5), "outside the radius");
        assertFalse(safe(defenderId, ATTACKER_SPAWN, 0), "the attackers' safe zone doesn't protect defenders");
        assertFalse(safe(defenderId, ATTACKER_SECOND_SPAWN, 0));
        assertFalse(safe(defenderId, FIELD, 0));
    }

    @Test
    void attackersAreSafeAroundEachOfTheirSpawnsOnly() {
        assertTrue(safe(attackerId, ATTACKER_SPAWN, 0));
        assertTrue(safe(attackerId, ATTACKER_SPAWN, 3.5));
        assertTrue(safe(attackerId, ATTACKER_SECOND_SPAWN, 0), "any of the team's spawnpoints, not just the default");
        assertTrue(safe(attackerId, ATTACKER_SECOND_SPAWN, -3.5));
        assertFalse(safe(attackerId, ATTACKER_SPAWN, 4.5), "outside the radius");
        assertFalse(safe(attackerId, DEFENDER_SPAWN, 0), "the defenders' safe zone doesn't protect attackers");
        assertFalse(safe(attackerId, FIELD, 0));
    }

    @Test
    void aPlayerOffTheRosterIsNeverSafe() {
        assertFalse(safe(UUID.randomUUID(), DEFENDER_SPAWN, 0));
        assertFalse(safe(UUID.randomUUID(), ATTACKER_SPAWN, 0));
    }
}
