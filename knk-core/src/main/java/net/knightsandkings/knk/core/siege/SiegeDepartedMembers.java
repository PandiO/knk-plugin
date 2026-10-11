package net.knightsandkings.knk.core.siege;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;

/**
 * The members who left a running match before its end, with the stats they had when they left
 * (KNG-34 leaver fix, DESIGN.md §F.6): without them the completion payload only carried the members
 * present at the end, so a leaver's kills, deaths, streak and captures were lost. They are reported
 * with their {@code leftAt}; the API keeps them out of the rewards (present-at-end only) and counts the
 * match as a loss for them.
 * <p>
 * A member who left several times is merged into one result (kills, deaths and captures summed, the
 * highest streak, the first {@code leftAt}). A member who left and rejoined is present at the end: their
 * earlier stats are merged into the present result, which carries no {@code leftAt} (the API keeps the
 * left marker it already has). Main thread only.
 */
public final class SiegeDepartedMembers {

    private final Map<Integer, ParticipantResult> byUser = new LinkedHashMap<>();

    /** A member with {@code userId} left at {@code leftAt} with the stats in {@code view}. */
    public void departed(int userId, MemberView view, Instant leftAt) {
        if (view == null) {
            return;
        }
        ParticipantResult result = new ParticipantResult(userId, view.teamId(), view.kills(), view.deaths(),
                view.highestKillStreak(), view.captures(), leftAt);
        byUser.merge(userId, result, (earlier, later) -> new ParticipantResult(userId, later.siegeTeamId(),
                earlier.kills() + later.kills(), earlier.deaths() + later.deaths(),
                Math.max(earlier.highestKillStreak(), later.highestKillStreak()),
                earlier.captures() + later.captures(),
                earlier.leftAt() != null ? earlier.leftAt() : later.leftAt()));
    }

    /**
     * The completion's participants: {@code present} (the members at the end, without {@code leftAt})
     * with the departed members' stats merged into a rejoined member's result, followed by every
     * departed member who did not come back.
     */
    public List<ParticipantResult> appendTo(List<ParticipantResult> present) {
        List<ParticipantResult> participants = new ArrayList<>(present == null ? List.of() : present);
        if (byUser.isEmpty()) {
            return participants;
        }
        Map<Integer, ParticipantResult> remaining = new LinkedHashMap<>(byUser);
        for (int i = 0; i < participants.size(); i++) {
            ParticipantResult now = participants.get(i);
            ParticipantResult earlier = remaining.remove(now.userId());
            if (earlier != null) {
                participants.set(i, new ParticipantResult(now.userId(), now.siegeTeamId(),
                        now.kills() + earlier.kills(), now.deaths() + earlier.deaths(),
                        Math.max(now.highestKillStreak(), earlier.highestKillStreak()),
                        now.captures() + earlier.captures(), now.leftAt()));
            }
        }
        participants.addAll(remaining.values());
        return participants;
    }

    public boolean isEmpty() {
        return byUser.isEmpty();
    }

    public int size() {
        return byUser.size();
    }
}
