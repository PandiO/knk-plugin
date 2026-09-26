package net.knightsandkings.knk.core.domain.siege;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything the plugin needs to run sieges, in one payload (API
 * {@code GET /api/siege-lobbies/runtime-config}, DESIGN §11.2): the global configuration and every
 * <b>enabled</b> lobby with its rotation of fully resolved, structurally ready scenarios.
 *
 * @param generatedAt server time the payload was built, or null if the API didn't send it
 */
public record KnkSiegeRuntimeConfig(
        Instant generatedAt,
        KnkSiegeConfiguration configuration,
        List<KnkSiegeLobby> lobbies
) {
    public KnkSiegeRuntimeConfig {
        lobbies = lobbies == null ? List.of() : List.copyOf(lobbies);
    }

    public Optional<KnkSiegeLobby> lobbyById(int lobbyId) {
        return lobbies.stream().filter(l -> l.id() == lobbyId).findFirst();
    }

    /** Case-insensitive, like {@code /siege join <key>} (keys are stored lowercase). */
    public Optional<KnkSiegeLobby> lobbyByKey(String key) {
        if (key == null) return Optional.empty();
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        return lobbies.stream()
                .filter(l -> l.key() != null && l.key().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }
}
