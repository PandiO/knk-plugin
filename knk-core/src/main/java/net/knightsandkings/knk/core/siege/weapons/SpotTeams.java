package net.knightsandkings.knk.core.siege.weapons;

import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi.Team;

import java.util.List;
import java.util.Locale;

/**
 * Which scenario team the admin means in {@code /knk weaponspot <scenario> <team> <name>} (W44: every spot belongs to
 * one team): the team id, its name or clan name (any case), or {@code attackers}/{@code defenders} when exactly one
 * team has that role.
 */
public final class SpotTeams {

    /** Either the team or why none was picked. */
    public record Resolution(Team team, String error) {
        public boolean found() {
            return team != null;
        }
    }

    private SpotTeams() {
    }

    public static Resolution resolve(List<Team> teams, String text) {
        if (teams == null || teams.isEmpty()) return new Resolution(null, "The scenario has no teams yet.");
        String value = text == null ? "" : text.trim();
        if (value.matches("\\d+")) {
            int id = Integer.parseInt(value);
            return teams.stream().filter(t -> t.id() == id).findFirst()
                    .map(t -> new Resolution(t, null))
                    .orElseGet(() -> new Resolution(null, "Team " + id + " is not a team of this scenario. " + list(teams)));
        }
        List<Team> byName = teams.stream().filter(t -> value.equalsIgnoreCase(t.displayName())).toList();
        if (byName.size() == 1) return new Resolution(byName.getFirst(), null);
        if (byName.size() > 1) return new Resolution(null, "More than one team is called " + value + "; use its id. " + list(teams));

        String role = switch (value.toLowerCase(Locale.ROOT)) {
            case "attacker", "attackers" -> "Attacker";
            case "defender", "defenders" -> "Defender";
            default -> null;
        };
        if (role != null) {
            List<Team> byRole = teams.stream().filter(t -> role.equalsIgnoreCase(t.role())).toList();
            if (byRole.size() == 1) return new Resolution(byRole.getFirst(), null);
            return new Resolution(null, (byRole.isEmpty() ? "No " : "More than one ") + role.toLowerCase(Locale.ROOT)
                    + " team in this scenario; use the team's id or name. " + list(teams));
        }
        return new Resolution(null, "No team " + value + " in this scenario. " + list(teams));
    }

    private static String list(List<Team> teams) {
        return "Teams: " + String.join(", ", teams.stream()
                .map(t -> t.id() + " " + t.displayName() + " (" + (t.role() == null ? "?" : t.role().toLowerCase(Locale.ROOT)) + ")")
                .toList()) + ".";
    }
}
