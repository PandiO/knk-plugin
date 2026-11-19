package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import net.knightsandkings.knk.core.siege.weapons.SpotFacing;
import net.knightsandkings.knk.core.siege.weapons.SpotSizeClass;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Siege weapons phase 2 (SIEGE_WEAPONS § 4, PandiO/knk-workspace#783): what the in-game spot task needs from the API.
 * {@code GET api/SiegeScenarios/{id}/teams} and {@code POST api/SiegeScenarios/{id}/weapon-spots} (plugin key).
 */
public interface SiegeWeaponSpotsApi {

    /** A scenario team as the spot task resolves it; {@code role} is {@code Attacker} or {@code Defender}. */
    record Team(int id, String name, String clanName, String role) {
        /** The team's own name, else its clan's, else "team &lt;id&gt;". */
        public String displayName() {
            if (name != null && !name.isBlank()) return name;
            if (clanName != null && !clanName.isBlank()) return clanName;
            return "team " + id;
        }
    }

    /** The spot to create; {@code wgRegionId} null for a WorldEdit selection. */
    record NewSpot(int teamId, String name, SpotBounds bounds, String wgRegionId, SpotFacing facing,
                   SpotSizeClass sizeClass) {
    }

    /** The created spot as the API saved it (the API works the size class out again from bounds and facing). */
    record CreatedSpot(int id, String name, String facing, String sizeClass) {
    }

    /** The scenario's teams; completes with null when the scenario doesn't exist. */
    CompletableFuture<List<Team>> listTeams(int scenarioId);

    /**
     * Creates the spot under its scenario. Fails with an {@code ApiException} carrying the API's message when the API
     * refuses it (400, for example a team of another scenario).
     */
    CompletableFuture<CreatedSpot> createSpot(int scenarioId, NewSpot spot);
}
