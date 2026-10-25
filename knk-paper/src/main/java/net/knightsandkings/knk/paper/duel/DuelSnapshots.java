package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelResult;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.DuelTicks;
import net.knightsandkings.knk.core.duel.view.DuelHudView;
import net.knightsandkings.knk.core.duel.view.DuelMatchSnapshot;
import net.knightsandkings.knk.core.duel.view.ArenaMenuView;

import java.util.List;

/**
 * Read-only copies of the runtime's matches for the announcer, the duel sidebar/boss bar and the duel menus (P6):
 * {@link DuelMatchSnapshot}s, taken on the main thread.
 */
public final class DuelSnapshots {

    private DuelSnapshots() {
    }

    public static DuelMatchSnapshot of(DuelService service, DuelMatch match) {
        long now = service.now();
        DuelResult result = match.result();
        int fightSeconds = match.fightStartTick() < 0 ? 0
                : (int) Math.max(0, (now - match.fightStartTick()) / DuelTicks.PER_SECOND);
        return new DuelMatchSnapshot(
                match.token(),
                match.arena().id(),
                ArenaMenuView.displayName(match.arena()),
                match.battleground() != null ? match.battleground().displayName() : null,
                match.phase(),
                match.gearMode(),
                side(match, DuelSide.CHALLENGER),
                side(match, DuelSide.OPPONENT),
                match.secondsRemaining(now),
                phaseSeconds(match),
                match.isWaitingForBattleground(),
                service.queuePosition(match),
                match.contract().isFriendly(),
                match.settings().feePercent(),
                fightSeconds,
                result != null ? result.reason() : null,
                result != null ? result.winner() : null,
                match.settlement() == Settlement.HELD);
    }

    /** Every running match, oldest first. */
    public static List<DuelMatchSnapshot> all(DuelService service) {
        return service.matches().stream().map(m -> of(service, m)).toList();
    }

    /** The arena's queue and battleground use ("2/3 waiting", "5/8 busy"). */
    public static DuelHudView.QueueInfo queueOf(DuelService service, int arenaId, int maxQueuedFallback) {
        return service.queueOf(arenaId)
                .map(q -> new DuelHudView.QueueInfo(q.queuedCount(), q.maxQueuedMatches(),
                        q.allocator().busyCount(), q.allocator().totalCount()))
                .orElse(new DuelHudView.QueueInfo(0, maxQueuedFallback, 0, 0));
    }

    private static DuelMatchSnapshot.Side side(DuelMatch match, DuelSide side) {
        var stake = match.contract().stakeOf(side);
        return new DuelMatchSnapshot.Side(match.playerOf(side), match.nameOf(side), match.userIdOf(side),
                kitName(match, match.kitOf(side)), stake.coins(), stake.items());
    }

    private static String kitName(DuelMatch match, Integer kitId) {
        if (kitId == null) return null;
        for (KnkArenaKit kit : match.arena().eligibleKits()) {
            if (kit.kitId() == kitId) return kit.kitName();
        }
        return "Kit #" + kitId;
    }

    /** The current phase's full length, for the boss bar's fill; 0 = no end. */
    static int phaseSeconds(DuelMatch match) {
        var s = match.settings();
        return match.phase() == DuelPhase.WARMUP ? (match.isWaitingForBattleground() ? 0 : s.warmupSeconds())
                : match.phase() == DuelPhase.BATTLE_COUNTDOWN ? s.countdownSeconds()
                : match.phase() == DuelPhase.FIGHT ? s.fightTimeLimitSeconds()
                : match.phase() == DuelPhase.ENDING ? s.winnerLingerSeconds()
                : 0;
    }
}
