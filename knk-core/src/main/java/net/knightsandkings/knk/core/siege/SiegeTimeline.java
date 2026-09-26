package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;

import java.util.List;

/**
 * The matchmaking timeline from {@code SiegeConfiguration} (DESIGN §6.2–6.4), in seconds before the
 * match starts. The API enforces {@code voteClose ≥ draw ≥ hub ≥ teamSplit ≥ 1}; the plugin checks
 * again so a bad payload fails loudly instead of skipping a step.
 *
 * @param announcementMarks distinct marks, highest first
 */
public record SiegeTimeline(int voteClose, int draw, int hub, int teamSplit, List<Integer> announcementMarks) {

    public SiegeTimeline {
        if (!(voteClose >= draw && draw >= hub && hub >= teamSplit && teamSplit >= 1)) {
            throw new IllegalArgumentException(String.format(
                    "Siege timeline must satisfy voteClose >= draw >= hub >= teamSplit >= 1 (got %d/%d/%d/%d)",
                    voteClose, draw, hub, teamSplit));
        }
        announcementMarks = announcementMarks == null ? List.of() : announcementMarks.stream()
                .filter(m -> m != null && m > 0)
                .distinct()
                .sorted((a, b) -> Integer.compare(b, a))
                .toList();
    }

    public static SiegeTimeline from(KnkSiegeConfiguration config) {
        return new SiegeTimeline(
                config.voteCloseSecondsBeforeStart(),
                config.drawSecondsBeforeStart(),
                config.hubSecondsBeforeStart(),
                config.teamSplitSecondsBeforeStart(),
                config.matchmakingAnnouncementMarks());
    }

    /** A lobby's matchmaking must be longer than the vote-close offset, or voting never opens. */
    public void requireFits(int matchmakingSeconds) {
        if (matchmakingSeconds <= voteClose) {
            throw new IllegalArgumentException("Matchmaking (" + matchmakingSeconds
                    + " s) must be longer than the vote-close offset (" + voteClose + " s)");
        }
    }
}
