package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.core.survival.result.SurvivalResultRules;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultInput.PlayerResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code POST api/siege-survival/matches/{id}/result} body from a {@link SurvivalResultInput} exactly as the
 * API recomputes it (link S8a contract; DESIGN §9 v1, S32, S62): the settled integers are the floor of the exact
 * accrual, once, for players present at the end of a settling match; leavers and aborts settle 0; the totals are the
 * sums; the ledger key is {@code siege-survival:{matchId}} only when the result settles. {@code equipmentMode} is left
 * null (the scenario setting; flag S8b). Bukkit-free.
 */
public final class SurvivalResultBuilder {

    private SurvivalResultBuilder() {
    }

    public static MatchResultDto build(long matchId, SurvivalResultInput in) {
        List<PlayerResultDto> players = new ArrayList<>(in.players().size());
        long totalCoins = 0;
        long totalXp = 0;
        for (PlayerResult p : in.players()) {
            long coins = SurvivalResultRules.settled(p.coinsAccrued(), in.reason(), p.presentAtEnd());
            long xp = SurvivalResultRules.settled(p.xpAccrued(), in.reason(), p.presentAtEnd());
            totalCoins += coins;
            totalXp += xp;
            players.add(new PlayerResultDto(
                p.userId(),
                p.presentAtEnd(),
                p.presentAtEnd() ? null : p.leftAt(),
                p.kitId().isPresent() ? p.kitId().getAsInt() : null,
                p.kills(),
                Math.min(p.bonusKills(), p.kills()),
                p.deaths(),
                p.repairWork(),
                p.matchCurrencyEarned(),
                p.matchCurrencySpent(),
                p.finalMatchBalance(),
                p.coinsAccrued(),
                p.xpAccrued(),
                (int) coins,
                (int) xp,
                null, null, null));
        }
        boolean settles = SurvivalResultRules.settlesRewards(in.reason());
        SettlementDto settlement = new SettlementDto(settles, (int) totalCoins, (int) totalXp,
            SurvivalResultRules.ledgerKey(matchId, in.reason()), null, null, null);
        return new MatchResultDto(
            (int) matchId,
            in.lobbyId(),
            in.scenarioId(),
            in.difficultyProfileId().isPresent() ? in.difficultyProfileId().getAsInt() : null,
            in.configurationGeneratedAt(),
            in.startedAt(),
            in.endedAt(),
            SurvivalResultRules.endReasonCode(in.reason()),
            in.waves().reached(),
            in.waves().completed(),
            in.earnedKills(),
            in.systemKills(),
            players,
            settlement,
            null, null, null, null);
    }

    /** The coins the API will settle for {@code player} under this result (what the screen shows before the answer). */
    public static long settledCoins(SurvivalResultInput in, PlayerResult player) {
        return SurvivalResultRules.settled(player.coinsAccrued(), in.reason(), player.presentAtEnd());
    }

    public static long settledXp(SurvivalResultInput in, PlayerResult player) {
        return SurvivalResultRules.settled(player.xpAccrued(), in.reason(), player.presentAtEnd());
    }
}
