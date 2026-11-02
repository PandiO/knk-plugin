package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.paper.chat.RewardMessageFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The match result a member sees (alpha run 1, PandiO/knk-workspace#164): one compact chat line with the outcome,
 * kills and deaths; hovering it shows every statistic and reward of the match, zeros included. It ends with a
 * link to the match page in the web app (#174) when {@code web.public-url} is set. Pure formatting (no Bukkit
 * calls), so it is unit-tested.
 */
public final class SiegeResultMessage {

    private SiegeResultMessage() {
    }

    /** The member's side of the result. */
    public enum Outcome {
        WIN("Victory!", NamedTextColor.GREEN),
        LOSS("Defeat", NamedTextColor.RED),
        DRAW("Draw", NamedTextColor.YELLOW);

        private final String label;
        private final NamedTextColor color;

        Outcome(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public NamedTextColor color() {
            return color;
        }
    }

    /** What is known about the member's rewards when the result is shown. */
    public enum RewardState {
        /** The server answered {@code complete}: the rewards are in the summary. */
        GRANTED,
        /** The answer hasn't come yet; the rewards follow in a second line when it does. */
        PENDING,
        /** The API couldn't be reached; the result is spooled and recorded later. */
        SPOOLED,
        /** The match row was never created (API down at the draw): no rewards this round. */
        UNRECORDED
    }

    /**
     * One member's match, as the plugin counted it.
     *
     * @param gateDamage    whole HP taken off the match's gates (#165)
     * @param activeSeconds active (not AFK) seconds in the match (#166)
     * @param matchSeconds  their time in the match
     */
    public record MemberResult(
            Outcome outcome,
            KnkSiegeTeam team,
            int kills,
            int deaths,
            int bestStreak,
            int captures,
            int gateDamage,
            long activeSeconds,
            long matchSeconds
    ) {
    }

    /** WIN when the member's alliance won, DRAW on a draw, LOSS otherwise (also for a team the scenario doesn't know). */
    public static Outcome outcomeOf(WinResolver.Result result, AllianceResolver alliances, int teamId) {
        if (result.winningAllianceGroup().isEmpty()) return Outcome.DRAW;
        if (!alliances.knows(teamId)) return Outcome.LOSS;
        return alliances.allianceOf(teamId) == result.winningAllianceGroup().getAsInt() ? Outcome.WIN : Outcome.LOSS;
    }

    /** The title shown when the members arrive back at the hub (#198). */
    public static Title outcomeTitle(Outcome outcome) {
        return Title.title(Component.text(outcome.label(), outcome.color(), TextDecoration.BOLD),
                Component.text("The siege is over", SiegeMessages.INFO),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750)));
    }

    /** {@code <base>/siege/matches/<id>}, or null when there is no public URL. */
    public static String matchPageUrl(String publicWebUrl, long matchId) {
        if (publicWebUrl == null || publicWebUrl.isBlank()) return null;
        String base = publicWebUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/siege/matches/" + matchId;
    }

    /**
     * The compact line: {@code [Siege] Victory! 5 kills · 2 deaths · +120 coins [details] [match page]}. The whole
     * line carries the hover; the rewards appear in it once known.
     *
     * @param reward the member's reward (state GRANTED), else null
     * @param url    the match page, or null
     */
    public static Component summary(String lobbyName, String endReason, MemberResult member, RewardState state,
                                    ParticipantReward reward, String url) {
        Component hover = details(lobbyName, endReason, member, state, reward, url != null);
        Component line = Component.text(member.outcome().label(), member.outcome().color(), TextDecoration.BOLD)
                .append(Component.text(" " + count(member.kills(), "kill") + " · " + count(member.deaths(), "death"),
                        NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
        if (state == RewardState.GRANTED && reward != null) {
            line = line.append(Component.text(" · " + rewardShort(reward), NamedTextColor.GOLD).decoration(TextDecoration.BOLD, false));
        }
        line = line.append(Component.text(" [details]", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .hoverEvent(HoverEvent.showText(hover));
        Component message = SiegeMessages.prefixed(line);
        if (url != null) message = message.append(Component.space()).append(link(url));
        return message;
    }

    /**
     * The rewards on their own, for a result shown before the server answered: one line with the hover breakdown.
     */
    public static Component rewardsFollowUp(MemberResult member, ParticipantReward reward, String url) {
        Component hover = Component.text("Siege rewards", SiegeMessages.HIGHLIGHT, TextDecoration.BOLD);
        for (Component line : rewardSection(member, RewardState.GRANTED, reward)) {
            hover = hover.append(Component.newline()).append(line);
        }
        Component line = Component.text("Your rewards: ", SiegeMessages.INFO)
                .append(Component.text(reward == null || !reward.hasRewards() ? "none" : rewardShort(reward), NamedTextColor.GOLD))
                .append(Component.text(" [details]", NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(hover));
        Component message = SiegeMessages.prefixed(line);
        if (url != null) message = message.append(Component.space()).append(link(url));
        return message;
    }

    /** The hover: every statistic and reward, zeros included. */
    static Component details(String lobbyName, String endReason, MemberResult m, RewardState state, ParticipantReward reward,
                             boolean hasLink) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(lobbyName, SiegeMessages.HIGHLIGHT, TextDecoration.BOLD)
                .append(Component.text(" - ", SiegeMessages.INFO).decoration(TextDecoration.BOLD, false))
                .append(Component.text(m.outcome().label(), m.outcome().color()).decoration(TextDecoration.BOLD, false)));
        lines.add(stat("Ended", endReason));
        if (m.team() != null) {
            lines.add(Component.text("Team: ", SiegeMessages.INFO).append(SiegeBukkit.teamComponent(m.team())));
        }
        lines.add(Component.empty());
        lines.add(stat("Kills", String.valueOf(m.kills())));
        lines.add(stat("Deaths", String.valueOf(m.deaths())));
        lines.add(stat("Best kill streak", String.valueOf(m.bestStreak())));
        lines.add(stat("Objectives captured", String.valueOf(m.captures())));
        int gateDamage = reward != null && state == RewardState.GRANTED ? reward.gateDamage() : m.gateDamage();
        lines.add(stat("Gate damage", gateDamage + " HP"));
        lines.add(stat("Active time", SiegeMessages.clock((int) Math.min(Integer.MAX_VALUE, m.activeSeconds())) + " of "
                + SiegeMessages.clock((int) Math.min(Integer.MAX_VALUE, m.matchSeconds()))));
        lines.add(Component.empty());
        lines.add(Component.text("Rewards", SiegeMessages.HIGHLIGHT, TextDecoration.BOLD));
        lines.addAll(rewardSection(m, state, reward));
        if (hasLink) {
            lines.add(Component.empty());
            lines.add(Component.text("Click [match page] for the whole match in the web app.", NamedTextColor.DARK_GRAY));
        }
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(lines.get(i));
        }
        return out;
    }

    private static List<Component> rewardSection(MemberResult m, RewardState state, ParticipantReward reward) {
        List<Component> lines = new ArrayList<>();
        switch (state) {
            case PENDING -> {
                lines.add(Component.text("Being confirmed by the server; they follow in chat.", SiegeMessages.INFO));
                return lines;
            }
            case SPOOLED -> {
                lines.add(Component.text("Couldn't be confirmed yet: the server couldn't reach the API.", SiegeMessages.BAD));
                lines.add(Component.text("The result is kept and recorded (and paid) automatically later.", SiegeMessages.INFO));
                return lines;
            }
            case UNRECORDED -> {
                lines.add(Component.text("This match couldn't be recorded (the API was down when it was drawn),", SiegeMessages.BAD));
                lines.add(Component.text("so no rewards are granted this round.", SiegeMessages.BAD));
                return lines;
            }
            case GRANTED -> { }
        }
        if (reward == null) {
            lines.add(stat("Coins", "0"));
            lines.add(stat("Gems", "0"));
            lines.add(stat("XP", "0"));
            lines.add(Component.text("No reward entry for you in this match.", SiegeMessages.INFO));
            return lines;
        }
        lines.add(currencyLine(RewardMessageFormat.Currency.COINS, reward.baseCoins(), reward.coinMultipliers(), reward.coins()));
        lines.add(stat("Gems", "+" + reward.gems()));
        lines.add(stat("XP", "+" + reward.experience()));
        lines.add(Component.text("Earned by:", SiegeMessages.INFO));
        lines.add(bullet("Win", reward.won() ? "yes" : "no"));
        lines.add(bullet("Objectives gained by your team", String.valueOf(reward.holdingCount())));
        lines.add(bullet("Objectives you captured", String.valueOf(reward.captureCount())));
        lines.add(bullet("Gate damage", reward.gateDamage() + " HP (" + reward.gateDamageSteps() + " full "
                + (reward.gateDamageSteps() == 1 ? "step" : "steps") + ")"));
        lines.add(bullet("Taking part", reward.participation() ? "yes" : "no (active "
                + SiegeMessages.clock(reward.activeSeconds()) + " of " + SiegeMessages.clock(reward.matchSeconds()) + ")"));
        return lines;
    }

    private static Component currencyLine(RewardMessageFormat.Currency currency, int base,
                                          List<net.knightsandkings.knk.core.domain.users.RewardMultiplier> multipliers, int total) {
        boolean breakdown = base > 0 && multipliers != null && !multipliers.isEmpty();
        if (!breakdown) return stat("Coins", "+" + total);
        return RewardMessageFormat.line("Coins", currency, base, multipliers, total);
    }

    /** "+120 coins, +12 XP" (only what is above zero), or "no rewards". */
    static String rewardShort(ParticipantReward r) {
        List<String> parts = new ArrayList<>();
        if (r.coins() > 0) parts.add("+" + r.coins() + " coins");
        if (r.gems() > 0) parts.add("+" + r.gems() + " gems");
        if (r.experience() > 0) parts.add("+" + r.experience() + " XP");
        return parts.isEmpty() ? "no rewards" : String.join(", ", parts);
    }

    private static Component link(String url) {
        return Component.text("[match page]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Open " + url, SiegeMessages.INFO)));
    }

    private static Component stat(String label, String value) {
        return Component.text(label + ": ", SiegeMessages.INFO).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component bullet(String label, String value) {
        return Component.text("  • " + label + ": ", SiegeMessages.INFO).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
