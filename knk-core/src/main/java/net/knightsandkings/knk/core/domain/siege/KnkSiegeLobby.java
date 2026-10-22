package net.knightsandkings.knk.core.domain.siege;

import java.util.List;
import java.util.Optional;

/**
 * An enabled lobby with its rotation (DESIGN §3.8, D1). The rotation holds only the scenarios the
 * API found structurally ready; the others are in {@link #skippedScenarios()} with their errors
 * (Phase 2 decision 2: runtime-config runs no spatial checks).
 *
 * @param voteCandidateCount scenarios offered in the vote (1–3)
 * @param navigationItem     this lobby's choice on giving the navigation compass at the hub (KNG-234); wins over the scenario's
 */
public record KnkSiegeLobby(
        int id,
        String name,
        String key,
        SiegeLobbyMode mode,
        int matchmakingSeconds,
        int cooldownSeconds,
        int voteCandidateCount,
        boolean allowRandomVote,
        List<KnkSiegeRotationEntry> rotation,
        List<KnkSiegeSkippedScenario> skippedScenarios,
        SiegeNavigationItemMode navigationItem
) {
    public KnkSiegeLobby {
        if (mode == null) mode = SiegeLobbyMode.CONTINUOUS;
        rotation = rotation == null ? List.of() : List.copyOf(rotation);
        skippedScenarios = skippedScenarios == null ? List.of() : List.copyOf(skippedScenarios);
        if (navigationItem == null) navigationItem = SiegeNavigationItemMode.INHERIT;
    }

    /** A lobby that leaves the navigation compass to its scenario and the global setting (everything before KNG-234). */
    public KnkSiegeLobby(int id, String name, String key, SiegeLobbyMode mode, int matchmakingSeconds, int cooldownSeconds,
                         int voteCandidateCount, boolean allowRandomVote, List<KnkSiegeRotationEntry> rotation,
                         List<KnkSiegeSkippedScenario> skippedScenarios) {
        this(id, name, key, mode, matchmakingSeconds, cooldownSeconds, voteCandidateCount, allowRandomVote, rotation,
                skippedScenarios, SiegeNavigationItemMode.INHERIT);
    }

    public boolean hasReadyScenario() {
        return !rotation.isEmpty();
    }

    public Optional<KnkSiegeScenario> scenario(int scenarioId) {
        return rotation.stream().map(KnkSiegeRotationEntry::scenario)
                .filter(s -> s.id() == scenarioId).findFirst();
    }
}
