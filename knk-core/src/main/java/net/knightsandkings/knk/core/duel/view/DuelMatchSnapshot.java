package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeItem;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One running match as the announcer, the duel sidebar/boss bar and the duel menus see it (arena duels DESIGN §9,
 * plan Phase 6): a read-only copy the Paper runtime takes of its {@code DuelMatch} on the main thread, so the views
 * here stay pure.
 *
 * @param token              the match's public token
 * @param arenaId            the arena it locked in with
 * @param arenaName          the arena's display name
 * @param battlegroundName   the allocated battleground, or null before the countdown
 * @param phase              the current phase
 * @param gearMode           how the duelists are geared
 * @param challenger         the inviting side
 * @param opponent           the invited side
 * @param secondsRemaining   seconds left in the current phase (0 when the phase has no end)
 * @param phaseSeconds       the current phase's full length, for the boss bar's progress (0 = no end)
 * @param waitingForBattleground warmup is over and the match waits for a free battleground
 * @param queuePosition      the 1-based place in that wait, or 0
 * @param friendly           no stakes and no inventory drop (DESIGN §6.2)
 * @param feePercent         the arena fee on the coin pot of a decisive result (D15)
 * @param fightSeconds       how long the fight has lasted (0 before it started)
 * @param endReason          the result's reason, or null while the match runs
 * @param winner             the winning side of a decisive result, else null
 * @param held               the API held the decisive result for review (DESIGN §10.3)
 */
public record DuelMatchSnapshot(
        UUID token,
        int arenaId,
        String arenaName,
        String battlegroundName,
        DuelPhase phase,
        GearMode gearMode,
        Side challenger,
        Side opponent,
        int secondsRemaining,
        int phaseSeconds,
        boolean waitingForBattleground,
        int queuePosition,
        boolean friendly,
        int feePercent,
        int fightSeconds,
        DuelEndReason endReason,
        DuelSide winner,
        boolean held
) {
    /**
     * One duelist and what they put in.
     *
     * @param kitName the kit they fight with (kit modes), or null
     * @param coins   their coin stake
     * @param items   their stake items (material and amount)
     */
    public record Side(UUID playerId, String name, int userId, String kitName, long coins, List<StakeItem> items) {
        public Side {
            Objects.requireNonNull(playerId, "playerId");
            name = name == null ? "?" : name;
            items = items == null ? List.of() : List.copyOf(items);
        }

        public int itemCount() {
            return items.size();
        }
    }

    public DuelMatchSnapshot {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(gearMode, "gearMode");
        Objects.requireNonNull(challenger, "challenger");
        Objects.requireNonNull(opponent, "opponent");
        arenaName = arenaName == null || arenaName.isBlank() ? "the Arena" : arenaName;
        secondsRemaining = Math.max(0, secondsRemaining);
        phaseSeconds = Math.max(0, phaseSeconds);
        fightSeconds = Math.max(0, fightSeconds);
    }

    public Side side(DuelSide side) {
        return side == DuelSide.CHALLENGER ? challenger : opponent;
    }

    /** Whether {@code playerId} is one of the two duelists. */
    public boolean involves(UUID playerId) {
        return challenger.playerId().equals(playerId) || opponent.playerId().equals(playerId);
    }

    /** The menus list a match from the warmup until it is gone (DESIGN §9.2: WARMUP → ENDING). */
    public boolean isListed() {
        return phase == DuelPhase.WARMUP || phase == DuelPhase.BATTLE_COUNTDOWN || phase == DuelPhase.FIGHT
                || phase == DuelPhase.ENDING;
    }

    public long potCoins() {
        return challenger.coins() + opponent.coins();
    }

    public int potItems() {
        return challenger.itemCount() + opponent.itemCount();
    }

    /** What a decisive result pays the winner in coins: the pot less the fee, rounded down (D15). */
    public long winnings() {
        long pot = potCoins();
        return pot - (pot <= 0 ? 0 : pot * feePercent / 100);
    }

    /** "Pandi vs Notch". */
    public String title() {
        return challenger.name() + " vs " + opponent.name();
    }
}
