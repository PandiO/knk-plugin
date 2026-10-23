package net.knightsandkings.knk.core.navigation.destinations;

import java.util.Objects;
import java.util.UUID;

/**
 * The engine-issued handle (destination engine DESIGN §3, §6): proof that a destination came out of the player's
 * list. {@code NavigationService.navigate} accepts only this (E3), never a raw destination, so no command, menu
 * or future feature can navigate somewhere the list does not contain. Only {@link DestinationEngine#resolve} mints
 * one: the constructor is package-private and {@code ArchitectureGuardTest} fails the build if anything outside this
 * package constructs it. Carries the scope id, candidate key and model version that {@link DestinationEngine#revalidate}
 * checks (§8). The routable destination is built from {@link #candidate()}'s place and {@link #mode()}.
 */
public final class AllowedDestination {

    private final UUID player;
    private final String scopeId;
    private final Candidate candidate;
    private final Candidate.Mode mode;
    private final long modelVersion;

    AllowedDestination(UUID player, String scopeId, Candidate candidate, Candidate.Mode mode, long modelVersion) {
        this.player = Objects.requireNonNull(player, "player");
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.mode = mode == null ? Candidate.Mode.DEFAULT : mode;
        this.modelVersion = modelVersion;
    }

    /** The player the list was built for. */
    public UUID player() {
        return player;
    }

    /** The scope that listed the candidate. */
    public String scopeId() {
        return scopeId;
    }

    /** The candidate as listed when the handle was minted (re-validation looks its key up again). */
    public Candidate candidate() {
        return candidate;
    }

    /** The stable key the running session is re-validated by (§8). */
    public String candidateKey() {
        return candidate.key();
    }

    /** What the player asked for after the name: spawn, region, or the domain's default. */
    public Candidate.Mode mode() {
        return mode;
    }

    /** The model version the list was built from. */
    public long modelVersion() {
        return modelVersion;
    }

    @Override
    public String toString() {
        return "AllowedDestination " + candidate.key() + " (" + mode + ") for " + player + " via " + scopeId
            + " @" + modelVersion;
    }
}
