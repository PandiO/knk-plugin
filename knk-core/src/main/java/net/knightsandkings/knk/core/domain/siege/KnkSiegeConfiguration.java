package net.knightsandkings.knk.core.domain.siege;

import java.util.List;

/**
 * The global siege tunables (DESIGN §3.8; API {@code SiegeConfigurationDto}), frozen per match.
 * List values are immutable copies; null lists become empty.
 *
 * @param captureAttackBase                   A1: points the first attacker in the radius removes per second
 * @param captureAttackPerExtra               A2: per additional attacker (non-instant-victory objective)
 * @param captureAttackPerExtraInstantVictory A2 on instant-victory objectives
 * @param captureDefendBase                   D1: points the first defender restores per second
 * @param captureDefendPerExtra               D2: per additional defender (non-instant-victory objective)
 * @param captureDefendPerExtraInstantVictory D2 on instant-victory objectives
 * @param sideCaptureReduction                share of each instant-victory objective's points that side captures remove (§7.4)
 * @param voteCloseSecondsBeforeStart         matchmaking timeline in seconds before match start (§6.2–6.4): voting closes
 * @param drawSecondsBeforeStart              the scenario is drawn
 * @param hubSecondsBeforeStart               members go to the hub and joining closes
 * @param teamSplitSecondsBeforeStart         members are split into teams
 * @param matchmakingAnnouncementMarks        seconds-remaining marks announced to online players
 * @param killAnnouncementThresholds          kill counts announced to the killer's team
 * @param killStreakAnnounceAbove             streaks strictly above this are announced
 * @param spawnPickerDelayTicks               delay before the spawn picker opens after a respawn
 * @param enchantDropChancePerMille           chance per second of an enchant-book drop, in per mille
 */
public record KnkSiegeConfiguration(
        int captureAttackBase,
        int captureAttackPerExtra,
        int captureAttackPerExtraInstantVictory,
        int captureDefendBase,
        int captureDefendPerExtra,
        int captureDefendPerExtraInstantVictory,
        double sideCaptureReduction,
        int voteCloseSecondsBeforeStart,
        int drawSecondsBeforeStart,
        int hubSecondsBeforeStart,
        int teamSplitSecondsBeforeStart,
        List<Integer> matchmakingAnnouncementMarks,
        List<Integer> killAnnouncementThresholds,
        int killStreakAnnounceAbove,
        double headshotMultiplier,
        List<String> allowedCommands,
        int spawnPickerDelayTicks,
        int enchantDropChancePerMille,
        List<String> allowedEnchantmentKeys,
        int enchantLevelMin,
        int enchantLevelMax,
        int maxBooksAlive,
        SiegeNonMemberGateView nonMemberGateView
) {
    public KnkSiegeConfiguration {
        matchmakingAnnouncementMarks = copy(matchmakingAnnouncementMarks);
        killAnnouncementThresholds = copy(killAnnouncementThresholds);
        allowedCommands = copy(allowedCommands);
        allowedEnchantmentKeys = copy(allowedEnchantmentKeys);
        if (nonMemberGateView == null) nonMemberGateView = SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW;
    }

    /**
     * The legacy values the API seeds {@code SiegeConfiguration} with (Phase 2 decision 9), for
     * tests and as a documented reference. The allowed-enchantment list is left empty here; the
     * seeded list lives server-side.
     */
    public static KnkSiegeConfiguration legacyDefaults() {
        return new KnkSiegeConfiguration(
                5, 2, 5,
                6, 3, 6,
                0.4,
                30, 25, 15, 10,
                List.of(290, 60, 30, 15),
                List.of(5, 10, 15),
                3,
                1.5,
                List.of("/siege", "/msg", "/r", "/staffchat", "/menu"),
                20,
                30,
                List.of(),
                1, 2,
                10,
                SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW
        );
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
