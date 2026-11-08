package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.survival.economy.MatchEconomy;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultInput.PlayerResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads the {@link SurvivalResultInput} off a running or just-ended {@link SurvivalMatch} (link S8b): waves from the
 * record, kills from the rounds, per player from the {@link MatchEconomy} (kills, bonus kills, earned/spent/final
 * balance, exact accruals), deaths from the Siege roster, the kit from the S7b picker, presence from the roster and
 * the leavers the runtime remembered (S62). Players whose API user id was never known are left out with a note.
 */
public final class SurvivalResults {

    /** A member who left during the match, as the Siege observer reported it. */
    public record Leaver(UUID playerId, String name, int userId, OffsetDateTime leftAt, int deaths) { }

    private SurvivalResults() {
    }

    public static SurvivalResultInput collect(SurvivalMatch match, SurvivalEndReason reason, Collection<Leaver> leavers,
                                              Function<UUID, OptionalInt> userIdOf, Function<UUID, OptionalInt> kitOf,
                                              Function<UUID, String> nameOf, OffsetDateTime now, List<String> notes) {
        SurvivalConfiguration config = match.config();
        MatchEconomy economy = match.economy();
        List<PlayerResult> players = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID id : match.siege().roster().playerIds()) {
            OptionalInt userId = userIdOf.apply(id);
            if (userId.isEmpty()) {
                if (notes != null) notes.add("player " + id + " has no API user id; left out of the result");
                continue;
            }
            seen.add(id);
            int deaths = match.siege().roster().member(id).map(MemberView::deaths).orElse(0);
            players.add(new PlayerResult(id, nameOf.apply(id), userId.getAsInt(), true, null, kitOf.apply(id),
                economy.kills(id), economy.bonusKills(id), deaths, 0.0,
                economy.matchCurrencyEarned(id), economy.matchCurrencySpent(id), economy.balance(id),
                economy.accrual().coins(id), economy.accrual().xp(id)));
        }
        if (leavers != null) {
            for (Leaver leaver : leavers) {
                if (leaver == null || !seen.add(leaver.playerId())) continue;
                UUID id = leaver.playerId();
                players.add(new PlayerResult(id, leaver.name(), leaver.userId(), false, leaver.leftAt(), kitOf.apply(id),
                    economy.kills(id), economy.bonusKills(id), leaver.deaths(), 0.0,
                    economy.matchCurrencyEarned(id), economy.matchCurrencySpent(id), 0L,
                    economy.accrual().coins(id), economy.accrual().xp(id)));
            }
        }
        OffsetDateTime startedAt = startedAt(match, now);
        return new SurvivalResultInput(match.lobby().id(), match.lobby().displayName(), config.scenarioId(),
            config.difficulty() == null ? OptionalInt.empty() : OptionalInt.of(config.difficulty().id()),
            generatedAt(config.generatedAt()), startedAt, now, reason, match.record(), match.earnedKills(), match.systemKills(), players);
    }

    private static OffsetDateTime startedAt(SurvivalMatch match, OffsetDateTime fallback) {
        Instant started = match.siege() == null ? null : match.siege().startedAt();
        if (started != null) return started.atOffset(ZoneOffset.UTC);
        long millis = match.startedAtMillis();
        return millis > 0 ? Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC) : fallback;
    }

    /** The configuration's {@code generatedAt} (an ISO string from the DTO), or null when blank or unparsable. */
    static OffsetDateTime generatedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
