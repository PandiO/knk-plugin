package net.knightsandkings.knk.core.siege.weapons;

import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi.Team;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W44: the spot task names its team by id, name or role. */
class SpotTeamsTest {

    private static final Team RED = new Team(11, "Red Banners", null, "Attacker");
    private static final Team CLAN = new Team(12, null, "Iron Wardens", "Defender");
    private static final List<Team> TEAMS = List.of(RED, CLAN);

    @Test
    void byIdNameClanNameOrRole() {
        assertEquals(RED, SpotTeams.resolve(TEAMS, "11").team());
        assertEquals(RED, SpotTeams.resolve(TEAMS, "red banners").team());
        assertEquals(CLAN, SpotTeams.resolve(TEAMS, "Iron Wardens").team());
        assertEquals(RED, SpotTeams.resolve(TEAMS, "attackers").team());
        assertEquals(CLAN, SpotTeams.resolve(TEAMS, "Defender").team());
    }

    @Test
    void unknownOrAmbiguous_SaysWhyAndListsTheTeams() {
        SpotTeams.Resolution unknown = SpotTeams.resolve(TEAMS, "99");
        assertFalse(unknown.found());
        assertTrue(unknown.error().contains("11 Red Banners (attacker), 12 Iron Wardens (defender)"), unknown.error());

        List<Team> twoAttackers = List.of(RED, new Team(13, "Blue", null, "Attacker"));
        assertTrue(SpotTeams.resolve(twoAttackers, "attackers").error().startsWith("More than one attacker team"));
        assertTrue(SpotTeams.resolve(List.of(), "attackers").error().contains("no teams"));
        assertTrue(SpotTeams.resolve(TEAMS, "green").error().startsWith("No team green"));
    }
}
