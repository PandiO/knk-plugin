package net.knightsandkings.knk.core.duel.view;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaBattleground;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaSpectatorArea;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeItem;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Snapshots and arenas for the duel view tests. */
final class DuelViewFixtures {

    static final UUID PANDI = UUID.nameUUIDFromBytes("Pandi".getBytes(StandardCharsets.UTF_8));
    static final UUID NOTCH = UUID.nameUUIDFromBytes("Notch".getBytes(StandardCharsets.UTF_8));

    private DuelViewFixtures() {
    }

    static DuelMatchSnapshot.Side pandi(String kit, long coins, StakeItem... items) {
        return new DuelMatchSnapshot.Side(PANDI, "Pandi", 1, kit, coins, List.of(items));
    }

    static DuelMatchSnapshot.Side notch(String kit, long coins, StakeItem... items) {
        return new DuelMatchSnapshot.Side(NOTCH, "Notch", 2, kit, coins, List.of(items));
    }

    static StakeItem item(String ref, String material, int amount) {
        return new StakeItem(ref, material, amount);
    }

    /** A staked own-gear match in its warmup at "The Arena". */
    static Builder match() {
        return new Builder();
    }

    static final class Builder {
        UUID token = UUID.nameUUIDFromBytes("match".getBytes(StandardCharsets.UTF_8));
        int arenaId = 7;
        String battleground;
        DuelPhase phase = DuelPhase.WARMUP;
        GearMode gear = GearMode.OWN_GEAR_KEEP;
        DuelMatchSnapshot.Side challenger = pandi(null, 350, item("a", "DIAMOND_SWORD", 1));
        DuelMatchSnapshot.Side opponent = notch(null, 350, item("b", "GOLDEN_APPLE", 3));
        int remaining = 23;
        int phaseSeconds = 30;
        boolean waiting;
        int queuePosition;
        boolean friendly;
        int fee = 5;
        int fightSeconds;
        DuelEndReason reason;
        DuelSide winner;
        boolean held;

        Builder token(String name) {
            token = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Builder arena(int id) {
            arenaId = id;
            return this;
        }

        Builder phase(DuelPhase p, int remaining, int total) {
            phase = p;
            this.remaining = remaining;
            phaseSeconds = total;
            return this;
        }

        Builder waiting(int position) {
            waiting = true;
            queuePosition = position;
            phaseSeconds = 0;
            remaining = 0;
            return this;
        }

        Builder gear(GearMode mode, String challengerKit, String opponentKit) {
            gear = mode;
            challenger = new DuelMatchSnapshot.Side(PANDI, "Pandi", 1, challengerKit, challenger.coins(), challenger.items());
            opponent = new DuelMatchSnapshot.Side(NOTCH, "Notch", 2, opponentKit, opponent.coins(), opponent.items());
            return this;
        }

        Builder friendly() {
            friendly = true;
            challenger = pandi(challenger.kitName(), 0);
            opponent = notch(opponent.kitName(), 0);
            return this;
        }

        Builder sides(DuelMatchSnapshot.Side c, DuelMatchSnapshot.Side o) {
            challenger = c;
            opponent = o;
            return this;
        }

        Builder battleground(String name) {
            battleground = name;
            return this;
        }

        Builder ended(DuelEndReason reason, DuelSide winner, int fightSeconds) {
            phase = DuelPhase.ENDING;
            remaining = 5;
            phaseSeconds = 5;
            this.reason = reason;
            this.winner = winner;
            this.fightSeconds = fightSeconds;
            return this;
        }

        Builder held() {
            held = true;
            return this;
        }

        DuelMatchSnapshot build() {
            return new DuelMatchSnapshot(token, arenaId, "The Arena", battleground, phase, gear, challenger, opponent,
                    remaining, phaseSeconds, waiting, queuePosition, friendly, fee, fightSeconds, reason, winner, held);
        }
    }

    static KnkArena arena(boolean duels, List<KnkArenaSpectatorArea> areas) {
        KnkArenaBattleground bg = new KnkArenaBattleground(1, 7, "bg1", "Pit", "arena_2,a-battleground", "arena", 1,
                true, false, null, null);
        return new KnkArena(7, "arena_2", "The Arena", "arena_2", "arena", 1, null, null, null, duels, 3, 3, 60,
                true, true, 10_000, 9, 50, 5, null, null, 3, 10, 25_000, true, false, true, false,
                KnkArena.DuelGearMode.OWN_GEAR_KEEP, 30, 10, 300, 180, 5, true, true,
                List.of(new KnkArenaKit(11, "Knight", 1)), List.of(bg), areas);
    }

    static KnkArenaSpectatorArea area(int id, String name, boolean warp) {
        return new KnkArenaSpectatorArea(id, 7, name, "arena_2_stands", "arena", warp, null);
    }
}
