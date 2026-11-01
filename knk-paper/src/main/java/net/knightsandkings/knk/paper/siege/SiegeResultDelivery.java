package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.paper.siege.SiegeResultMessage.MemberResult;
import net.knightsandkings.knk.paper.siege.SiegeResultMessage.RewardState;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * One ended match's results on their way to its members (alpha run 1, PandiO/knk-workspace#164, #198). The result is
 * shown once: with the rewards when the server's {@code complete} answer comes first, or after a short wait without
 * them (then the rewards follow in a second line when the answer arrives). A spooled or unrecorded match says why
 * there are no rewards yet. Main thread.
 */
final class SiegeResultDelivery {

    private final String lobbyName;
    private final String endReason;
    private final Map<UUID, MemberResult> members;
    private final Map<Integer, UUID> playerByUserId;
    private final String url;
    private final BiConsumer<UUID, Component> tell;

    private RewardState shown;
    private boolean rewardsShown;

    SiegeResultDelivery(String lobbyName, String endReason, Map<UUID, MemberResult> members, Map<Integer, UUID> playerByUserId,
                        String url, BiConsumer<UUID, Component> tell) {
        this.lobbyName = lobbyName;
        this.endReason = endReason;
        this.members = Map.copyOf(members);
        this.playerByUserId = Map.copyOf(playerByUserId);
        this.url = url;
        this.tell = tell;
    }

    /**
     * @param state   what is known about the rewards
     * @param summary the server's answer (state GRANTED), else null
     */
    void deliver(RewardState state, RewardSummary summary) {
        Map<UUID, ParticipantReward> rewards = rewardsByPlayer(summary);
        if (shown == null) {
            shown = state;
            rewardsShown = state != RewardState.PENDING;
            members.forEach((id, member) -> tell.accept(id,
                    SiegeResultMessage.summary(lobbyName, endReason, member, state, rewards.get(id), url)));
            return;
        }
        if (rewardsShown || state == RewardState.PENDING) return;
        // The result went out while the answer was still on its way: the rewards (or why there are none) follow.
        rewardsShown = true;
        members.forEach((id, member) -> tell.accept(id, state == RewardState.GRANTED
                ? SiegeResultMessage.rewardsFollowUp(member, rewards.get(id), url)
                : SiegeMessages.bad("Your rewards couldn't be confirmed yet: the server couldn't reach the API. "
                        + "The result is kept and recorded automatically later.")));
    }

    private Map<UUID, ParticipantReward> rewardsByPlayer(RewardSummary summary) {
        Map<UUID, ParticipantReward> out = new HashMap<>();
        if (summary == null) return out;
        for (ParticipantReward r : summary.rewards()) {
            UUID id = playerByUserId.get(r.userId());
            if (id != null && r.presentAtEnd()) out.put(id, r);
        }
        return out;
    }
}
