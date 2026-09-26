package net.knightsandkings.knk.paper.utils;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ScoreboardUtil {
    private static Scoreboard scoreboard;

    public ScoreboardUtil() {}

    public static Scoreboard getScoreboard() {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            scoreboard = setTeams(scoreboard);
        }

        return scoreboard;
    }

    public static Scoreboard setTeams(Scoreboard scoreboard) {
        Team def = scoreboard.registerNewTeam("default");
        def.prefix(Component.text("§7"));
        def.color(NamedTextColor.GRAY);

        Team owner = scoreboard.registerNewTeam("owner");
        owner.prefix(Component.text("§5"));
        owner.color(NamedTextColor.DARK_PURPLE);

        // Premium tier teams ("tier_<groupId>") are registered on demand by teamFor, since the
        // tiers and their NameColor come from the API (KNG-7).
        Team staff = scoreboard.registerNewTeam(TabListTeam.STAFF);
        staff.color(NamedTextColor.BLUE);

        Objective health = scoreboard.registerNewObjective("Health", Criteria.HEALTH.getName(), Component.text("❤").color(ColorOptions.error), RenderType.HEARTS);
        health.setDisplaySlot(DisplaySlot.BELOW_NAME);

        return scoreboard;
    }

    /**
     * Players who keep the scoreboard they have (siege audit 2026-09-26: a siege member's match
     * scoreboard): their tab team and header/footer still update, but {@link Player#setScoreboard}
     * isn't called. Set by the siege runtime; the siege restores the shared board when they leave.
     */
    private static Predicate<Player> keepOwnScoreboard = p -> false;

    public static void setKeepOwnScoreboard(Predicate<Player> predicate) {
        keepOwnScoreboard = predicate == null ? p -> false : predicate;
    }

    public static void setScoreboard(List<Player> players, KnkPermissible knkPermissible) {
        setScoreboard(players, knkPermissible, null);
    }

    /**
     * @param userSummary The joining player's summary, used to render their title/prestige XP
     * (docs/specs/user-features/IMPLEMENTATION_PLAN.md §4) and premium tier (§5) in the tab list
     * footer, and to pick their name-color team (KNG-7, see {@link TabListTeam}). Null (e.g.
     * summary not yet cached) skips both footer lines and puts a non-owner/staff player on the
     * default team. Only meaningful when {@code players} has exactly one player — {@link #getScoreboard()} is one Scoreboard instance shared by every player
     * (each player calls {@link Player#setScoreboard}, but they all get the *same* object), and
     * Bukkit scores on a shared Scoreboard are visible identically to everyone who has it set, so
     * there is no sidebar Objective this method could use to show a different value per viewer.
     * The tab list header/footer, sent to each player individually below, is the one place here
     * that actually is per-player — which is why title/prestige is rendered there instead.
     */
    public static void setScoreboard(List<Player> players, KnkPermissible knkPermissible, UserSummary userSummary) {
        Scoreboard scoreboard = getScoreboard();

        for (Player p : players) {
            Team team = teamFor(scoreboard, TabListTeam.resolve(
                knkPermissible.hasPermission(p, "knk.mode.owner"),
                knkPermissible.hasPermission(p, "knk.mode.staff"),
                userSummary));

            team.addPlayer(p);
            if (!keepOwnScoreboard.test(p)) {
                p.setScoreboard(scoreboard);
            }

            // Set tab layout header/footer
            Component header = Component.text("§7Welcome to §9Knights and Kings");
            Component footer = Component.text("§cOpen Beta\n§cFollow us on instagram @knightsandkings.official")
                .append(titleFooterLine(userSummary))
                .append(premiumTierFooterLine(userSummary));
            p.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    /**
     * Gets (registering it if needed) the team for {@code target} and applies its color, if it
     * has one. The color is re-applied every time, so an admin's NameColor change reaches the
     * shared team on the next join of any of its members.
     */
    private static Team teamFor(Scoreboard scoreboard, TabListTeam target) {
        Team team = scoreboard.getTeam(target.name());
        if (team == null) {
            team = scoreboard.registerNewTeam(target.name());
        }
        if (target.color() != null) {
            team.color(target.color());
        }
        return team;
    }

    // Package-private for ScoreboardUtilTest (setScoreboard itself needs a live Bukkit server).
    static Component titleFooterLine(UserSummary userSummary) {
        if (userSummary == null || userSummary.titleName() == null) {
            return Component.empty();
        }

        Component line = Component.text("\n§b" + userSummary.titleName());
        if (userSummary.prestigeExperience() > 0) {
            line = line.append(Component.text(" §7(+" + userSummary.prestigeExperience() + " prestige XP)"));
        }
        return line;
    }

    static Component premiumTierFooterLine(UserSummary userSummary) {
        if (userSummary == null || userSummary.premiumTierName() == null) {
            return Component.empty();
        }

        Component line = Component.text("\n§6" + userSummary.premiumTierName());
        if (userSummary.premiumTierExpiresAt() != null) {
            // Date only, in UTC — the footer is only refreshed on join, so a to-the-minute
            // countdown would just go stale.
            line = line.append(Component.text(" §7(until "
                + PREMIUM_EXPIRY_FORMAT.format(userSummary.premiumTierExpiresAt()) + ")"));
        }
        return line;
    }

    private static final DateTimeFormatter PREMIUM_EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
}