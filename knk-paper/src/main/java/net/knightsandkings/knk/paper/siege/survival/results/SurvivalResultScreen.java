package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatch;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultInput.PlayerResult;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultPoster.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * What players see when a survival match ends (DESIGN §11 "result screen distinguishes match currency from permanent
 * coins/gems/XP", §9 "show the total next to, not mixed with, the final match-currency balance"; flag S5-5, link S8b):
 * the lobby-wide announcement, each member's lines (waves, end reason, kills and bonus kills, the permanent payout
 * separate from the match balance, "leavers forfeit" where it applies) and the follow-up once the API answered.
 * Nothing from the PvP win/capture/holding reward lines (the API pays none for survival). Adventure only, no Bukkit.
 */
public final class SurvivalResultScreen {

    private SurvivalResultScreen() {
    }

    /** The one-line announcement for everyone online (the PvP "X won the siege" replacement). */
    public static Component announcement(SurvivalResultInput in) {
        String waves = " Waves reached " + in.waves().reached() + ", completed " + in.waves().completed() + ".";
        Component body;
        if (in.reason().isAbort()) {
            body = Component.text("Survival ", SiegeMessages.INFO)
                .append(Component.text(in.lobbyName(), SiegeMessages.HIGHLIGHT))
                .append(Component.text(" ended: " + reasonWords(in.reason()) + "." + waves
                    + (in.reason() == SurvivalEndReason.NO_PLAYERS_LEFT ? "" : " No permanent rewards (aborted)."), SiegeMessages.INFO));
        } else {
            body = Component.text("Survival ", SiegeMessages.INFO)
                .append(Component.text(in.lobbyName(), SiegeMessages.HIGHLIGHT))
                .append(Component.text(" is over: " + reasonWords(in.reason()) + "." + waves + " " + in.earnedKills() + " enemies slain.", SiegeMessages.INFO));
        }
        return SiegeMessages.prefixed(body);
    }

    /** A member's result, shown right away (the permanent payout as the plugin computed it, pending the API's answer). */
    public static List<Component> memberLines(SurvivalResultInput in, PlayerResult me) {
        List<Component> lines = new ArrayList<>();
        lines.add(SiegeMessages.prefixed(Component.text("Survival over", in.reason().settlesRewards() ? SiegeMessages.BAD : SiegeMessages.HIGHLIGHT, TextDecoration.BOLD)
            .append(Component.text(" - " + SurvivalMatch.endMessage(in.reason()) + " Waves reached " + in.waves().reached()
                + ", completed " + in.waves().completed() + ".", SiegeMessages.INFO).decoration(TextDecoration.BOLD, false))));
        lines.add(SiegeMessages.prefixed(Component.text("You: ", SiegeMessages.INFO)
            .append(Component.text(count(me.kills(), "kill") + (me.bonusKills() > 0 ? " (" + me.bonusKills() + " headshot/critical)" : "")
                + " · " + count(me.deaths(), "death"), NamedTextColor.WHITE))
            .append(Component.text(" · team " + count(in.earnedKills(), "kill") + ".", SiegeMessages.INFO))));
        lines.add(SiegeMessages.prefixed(Component.text("Match coins: ", SiegeMessages.INFO)
            .append(Component.text(me.finalMatchBalance() + " left", NamedTextColor.WHITE))
            .append(Component.text(" (earned " + me.matchCurrencyEarned() + ", spent " + me.matchCurrencySpent()
                + ") - match currency, gone with the match.", SiegeMessages.INFO))));
        lines.add(SiegeMessages.prefixed(payoutLine(in, me)));
        return lines;
    }

    /** "Permanent reward: +30 coins, +3 XP (accrued 30.0 / 3.3; settling…)" or why there is none. */
    static Component payoutLine(SurvivalResultInput in, PlayerResult me) {
        Component label = Component.text("Permanent reward: ", SiegeMessages.INFO);
        if (!me.presentAtEnd()) {
            return label.append(Component.text("none - you left before the end (leavers forfeit).", SiegeMessages.BAD));
        }
        if (!in.reason().settlesRewards()) {
            return label.append(Component.text("none - " + (in.reason() == SurvivalEndReason.NO_PLAYERS_LEFT ? "nothing to settle"
                : "the match was aborted (" + reasonWords(in.reason()) + ")") + ".", SiegeMessages.BAD));
        }
        long coins = SurvivalResultBuilder.settledCoins(in, me);
        long xp = SurvivalResultBuilder.settledXp(in, me);
        return label.append(Component.text("+" + coins + " coins, +" + xp + " XP", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text(" (per-kill accrual " + me.coinsAccrued().stripTrailingZeros().toPlainString() + " / "
                + me.xpAccrued().stripTrailingZeros().toPlainString() + ", rounded down once; being recorded…)", SiegeMessages.INFO)
                .decoration(TextDecoration.BOLD, false));
    }

    /** The line that follows once the API answered the result post. */
    public static Component followUp(SurvivalResultInput in, PlayerResult me, Outcome outcome) {
        if (outcome.posted()) {
            MatchResultDto stored = outcome.stored();
            boolean settled = stored != null && stored.settlement() != null && Boolean.TRUE.equals(stored.settlement().settlesRewards());
            if (!in.reason().settlesRewards() || !me.presentAtEnd()) {
                return SiegeMessages.prefixed(Component.text("Match result recorded" + (outcome.fromStore() ? " (already stored)" : "") + ".", SiegeMessages.INFO));
            }
            if (!settled) {
                return SiegeMessages.prefixed(Component.text("Match result recorded, but the server settled no rewards ("
                    + (stored == null ? "unknown" : stored.endReason()) + ").", SiegeMessages.BAD));
            }
            long coins = SurvivalResultBuilder.settledCoins(in, me);
            long xp = SurvivalResultBuilder.settledXp(in, me);
            return SiegeMessages.prefixed(Component.text("Rewards credited: ", SiegeMessages.GOOD)
                .append(Component.text("+" + coins + " coins, +" + xp + " XP", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" are on your account.", SiegeMessages.GOOD).decoration(TextDecoration.BOLD, false)));
        }
        return SiegeMessages.prefixed(Component.text("Your rewards could not be recorded yet (" + outcome.code() + "); "
            + (SurvivalResultPoster.CODE_TRANSPORT.equals(outcome.code()) ? "the server keeps the result and retries at the next start."
            : "an admin has the details in the server log."), SiegeMessages.BAD));
    }

    /** The end reason in words (the announcement and the history line). */
    public static String reasonWords(SurvivalEndReason reason) {
        return switch (reason) {
            case TEAM_WIPE -> "the defenders were wiped out";
            case INSTA_LOSE_OBJECTIVE_CAPTURED -> "a decisive objective fell";
            case ALL_OBJECTIVES_CAPTURED -> "every objective fell";
            case NO_PLAYERS_LEFT -> "no players left";
            case ADMIN_ABORT -> "stopped by an admin";
            case SERVER_RESTART -> "server restart";
            case TECHNICAL_LIMIT -> "technical limit";
        };
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
