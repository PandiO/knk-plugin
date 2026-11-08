package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.survival.wave.WaveRecord;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Everything the survival result row needs, as plain values read off the match at its end (DESIGN §9, §10, §11;
 * Siege Survival link S8b). {@link SurvivalResults#collect} fills it from the running match, {@link SurvivalResultBuilder}
 * turns it into the API body; keeping the two apart makes every mapping testable without a Bukkit fixture.
 *
 * @param lobbyName       the lobby's display name (screen only)
 * @param scenarioId      the frozen configuration's scenario
 * @param startedAt       the Siege match start
 * @param endedAt         when the survival side ended
 * @param reason          the survival end reason (settles rewards or not, S62)
 * @param waves           reached / completed (a wipe in wave 10 = reached 10, completed 9)
 * @param earnedKills     player kills over the whole match
 * @param systemKills     stuck-enemy system kills over the whole match (not kills, §3c)
 * @param players         one entry per participant: present at the end or a leaver (S62: leavers forfeit)
 */
public record SurvivalResultInput(
        int lobbyId,
        String lobbyName,
        int scenarioId,
        OptionalInt difficultyProfileId,
        OffsetDateTime configurationGeneratedAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        SurvivalEndReason reason,
        WaveRecord waves,
        int earnedKills,
        int systemKills,
        List<PlayerResult> players
) {

    public SurvivalResultInput {
        lobbyName = lobbyName == null ? "" : lobbyName;
        difficultyProfileId = difficultyProfileId == null ? OptionalInt.empty() : difficultyProfileId;
        Objects.requireNonNull(reason, "reason");
        waves = waves == null ? WaveRecord.NONE : waves;
        players = players == null ? List.of() : List.copyOf(players);
    }

    /** The same input with another end (the provisional restart row of the recovery marker, S61). */
    public SurvivalResultInput withEnd(SurvivalEndReason newReason, OffsetDateTime newEndedAt) {
        return new SurvivalResultInput(lobbyId, lobbyName, scenarioId, difficultyProfileId, configurationGeneratedAt, startedAt,
            newEndedAt, newReason, waves, earnedKills, systemKills, players);
    }

    /**
     * One participant.
     *
     * @param presentAtEnd        still in the match when it ended (ghosts included, S20)
     * @param leftAt              when a leaver left (null when present)
     * @param kitId               the kit granted in kits mode (S7b picker), else empty
     * @param coinsAccrued        exact per-kill coins (tenths), zero for a leaver (forfeited at the leave)
     * @param finalMatchBalance   the match-currency balance at the end (zero for a leaver, S44)
     */
    public record PlayerResult(
            UUID playerId,
            String name,
            int userId,
            boolean presentAtEnd,
            OffsetDateTime leftAt,
            OptionalInt kitId,
            int kills,
            int bonusKills,
            int deaths,
            double repairWork,
            long matchCurrencyEarned,
            long matchCurrencySpent,
            long finalMatchBalance,
            BigDecimal coinsAccrued,
            BigDecimal xpAccrued
    ) {
        public PlayerResult {
            name = name == null ? "" : name;
            kitId = kitId == null ? OptionalInt.empty() : kitId;
            coinsAccrued = coinsAccrued == null ? BigDecimal.ZERO : coinsAccrued;
            xpAccrued = xpAccrued == null ? BigDecimal.ZERO : xpAccrued;
            if (bonusKills > kills) bonusKills = kills;
            if (presentAtEnd) leftAt = null;
        }
    }
}
