package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.CaptureCalculator;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One running match (from {@code StartMatchEffect} to {@code EndMatchEffect}): the Phase 4 core
 * objects (alliances, objective board, win resolver, roster) plus the few runtime-only facts the
 * listeners and presenters need. Read by commands and, in Phase 8b, menu views. Main thread only.
 */
public final class SiegeMatch {

    private final KnkSiegeScenario scenario;
    private final KnkSiegeConfiguration configuration;
    private final String matchToken;
    private final AllianceResolver alliances;
    private final SiegeObjectiveBoard board;
    private final WinResolver winResolver;
    private final SiegeMatchRoster roster;
    private final Instant startedAt;
    /** Members who may teleport by picking a spawn (match start / after respawn): deadline in nanoTime. */
    private final Map<UUID, Long> spawnPickDeadline = new HashMap<>();
    /** When each capture happened, keyed by {@link #captureKey}. */
    private final Map<String, Instant> captureTimes = new HashMap<>();
    /** The attacking team shown on each objective's banner gradient (last leading attacker). */
    private final Map<Integer, Integer> leadingAttackerByObjective = new HashMap<>();

    SiegeMatch(KnkSiegeScenario scenario, KnkSiegeConfiguration configuration, String matchToken,
               Map<Integer, List<UUID>> teams) {
        this.scenario = scenario;
        this.configuration = configuration;
        this.matchToken = matchToken;
        this.alliances = AllianceResolver.of(scenario);
        this.board = new SiegeObjectiveBoard(scenario, new CaptureCalculator(configuration), alliances);
        this.winResolver = new WinResolver(alliances);
        this.roster = new SiegeMatchRoster(teams);
        this.startedAt = Instant.now();
    }

    public KnkSiegeScenario scenario() {
        return scenario;
    }

    public KnkSiegeConfiguration configuration() {
        return configuration;
    }

    /** Runtime id of this match (PDC tags, vault files); independent of the Phase 6 match row id. */
    public String matchToken() {
        return matchToken;
    }

    public AllianceResolver alliances() {
        return alliances;
    }

    public SiegeObjectiveBoard board() {
        return board;
    }

    public WinResolver winResolver() {
        return winResolver;
    }

    public SiegeMatchRoster roster() {
        return roster;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Optional<KnkSiegeTeam> teamOf(UUID playerId) {
        var teamId = roster.teamOf(playerId);
        return teamId.isPresent() ? scenario.team(teamId.getAsInt()) : Optional.empty();
    }

    /** Opens the "pick = teleport now" window for a member (match start, after a respawn). */
    void openSpawnPick(UUID playerId, long windowNanos) {
        spawnPickDeadline.put(playerId, System.nanoTime() + windowNanos);
    }

    /** Consumes the window: true when the member may teleport with this pick. */
    boolean consumeSpawnPick(UUID playerId) {
        Long deadline = spawnPickDeadline.remove(playerId);
        return deadline != null && System.nanoTime() - deadline <= 0;
    }

    void closeSpawnPick(UUID playerId) {
        spawnPickDeadline.remove(playerId);
    }

    void recordCaptureTime(int objectiveId, int captureNumber, Instant at) {
        captureTimes.put(captureKey(objectiveId, captureNumber), at);
    }

    Instant captureTime(int objectiveId, int captureNumber) {
        return captureTimes.get(captureKey(objectiveId, captureNumber));
    }

    private static String captureKey(int objectiveId, int captureNumber) {
        return objectiveId + "#" + captureNumber;
    }

    Map<Integer, Integer> leadingAttackerByObjective() {
        return leadingAttackerByObjective;
    }
}
