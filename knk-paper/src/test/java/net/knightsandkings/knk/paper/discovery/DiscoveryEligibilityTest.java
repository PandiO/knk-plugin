package net.knightsandkings.knk.paper.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.paper.discovery.DiscoveryEligibility.Exclusion;

class DiscoveryEligibilityTest {
    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Set<UUID> loading = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Map<UUID, ActiveMode> modes = new HashMap<>();

    private DiscoveryEligibility eligibility(boolean enabled, boolean excludeSiege) {
        return new DiscoveryEligibility(enabled, loading::contains, p -> modes.getOrDefault(p.getUniqueId(), ActiveMode.NONE),
                frozen::contains, Set.of(GameMode.CREATIVE, GameMode.SPECTATOR), excludeSiege);
    }

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
    }

    @Test
    void aSurvivalPlayerWithNothingSpecialIsEligible() {
        assertTrue(eligibility(true, true).isEligible(player));
    }

    @Test
    void disabledExcludesEveryone() {
        assertEquals(Exclusion.DISABLED, eligibility(false, true).exclusion(player));
    }

    @Test
    void loadingPlayersAreExcluded() {
        loading.add(uuid);
        assertEquals(Exclusion.LOADING, eligibility(true, true).exclusion(player));
    }

    @Test
    void staffAndOwnerModeAreExcluded() {
        modes.put(uuid, ActiveMode.STAFF);
        assertEquals(Exclusion.MODE, eligibility(true, true).exclusion(player));
        modes.put(uuid, ActiveMode.OWNER);
        assertEquals(Exclusion.MODE, eligibility(true, true).exclusion(player));
    }

    @Test
    void frozenPlayersAreExcluded() {
        frozen.add(uuid);
        assertEquals(Exclusion.FROZEN, eligibility(true, true).exclusion(player));
    }

    @Test
    void excludedGameModes() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertEquals(Exclusion.GAME_MODE, eligibility(true, true).exclusion(player));
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertEquals(Exclusion.GAME_MODE, eligibility(true, true).exclusion(player));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        assertTrue(eligibility(true, true).isEligible(player));
    }

    @Test
    void siegeParticipantsAreExcludedOnceTheHookIsSetAndWhenConfigured() {
        DiscoveryEligibility eligibility = eligibility(true, true);
        assertTrue(eligibility.isEligible(player), "nobody is a participant without the siege hook");

        eligibility.setSiegeParticipantCheck(uuid::equals);
        assertEquals(Exclusion.SIEGE, eligibility.exclusion(player));

        DiscoveryEligibility notExcluding = eligibility(true, false);
        notExcluding.setSiegeParticipantCheck(uuid::equals);
        assertFalse(notExcluding.exclusion(player) == Exclusion.SIEGE);
    }
}
