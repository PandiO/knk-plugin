package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Siege Phase 8b (MENU_TEMPLATES.md C.3 {@code Body}): one row of the phase-aware {@code siege.body}
 * source - a member before the match (matchmaking/hub) or an objective during it. Every getter
 * switches on the row kind, so one row template renders both (sections can't carry conditions).
 */
public final class SiegeBodyRowView implements MenuRowKey {

    private final SiegeMenuSnapshot.Member member;
    private final boolean viewer;
    private final SiegeMenuSnapshot.Objective objective;
    private final SiegeMenuSnapshot.Team team;
    private final boolean allowRecapture;

    private SiegeBodyRowView(SiegeMenuSnapshot.Member member, boolean viewer, SiegeMenuSnapshot.Objective objective,
                             SiegeMenuSnapshot.Team team, boolean allowRecapture) {
        this.member = member;
        this.viewer = viewer;
        this.objective = objective;
        this.team = team;
        this.allowRecapture = allowRecapture;
    }

    /** @param team the member's team once split, else null */
    public static SiegeBodyRowView member(SiegeMenuSnapshot.Member member, boolean viewer, SiegeMenuSnapshot.Team team) {
        return new SiegeBodyRowView(member, viewer, null, team, false);
    }

    /** @param holder the objective's current holder team */
    public static SiegeBodyRowView objective(SiegeMenuSnapshot.Objective objective, SiegeMenuSnapshot.Team holder,
                                             boolean allowRecapture) {
        return new SiegeBodyRowView(null, false, objective, holder, allowRecapture);
    }

    @Override
    public Object menuRowKey() {
        return member != null ? "siege-member:" + member.id() : "siege-objective:" + objective.objectiveId();
    }

    public String getKind() {
        return member != null ? "member" : "objective";
    }

    public String getMaterial() {
        return member != null ? "PLAYER_HEAD" : "WHITE_BANNER";
    }

    public String getSkullOwner() {
        return member != null ? member.name() : null;
    }

    public String getBannerPatterns() {
        return objective != null ? objective.bannerPatterns() : null;
    }

    /** Members: HIGHLIGHT for the viewer (v1 glow). Objectives: HIGHLIGHT while contested. */
    public String getDisplayMode() {
        boolean highlight = member != null ? viewer : objective.contested();
        return highlight ? "HIGHLIGHT" : "NORMAL";
    }

    public String getTitle() {
        if (member != null) return "&a" + member.name();
        return "&7" + objective.name() + (objective.instantVictory() ? " &7(&aWin&7)" : "");
    }

    /** The lore (E8 list). */
    public List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (member != null) {
            lines.add("&bTitle: &a" + orDash(member.titleName()));
            lines.add("&bRank: &a" + orDash(member.rankName()));
            if (team != null) lines.add("&7Team: " + SiegeMenuFormat.color(team.chatColor()) + team.name());
            return lines;
        }
        lines.add("&7Held by: " + (team == null ? "&7-" : SiegeMenuFormat.color(team.chatColor()) + team.name()));
        lines.add("");
        lines.add("&7Captured: &a" + Math.max(0, Math.min(100, objective.capturePercent())) + "%");
        if (objective.lastCapturerName() != null) lines.add("&7Captured by: &a" + objective.lastCapturerName());
        if (allowRecapture && objective.captureCount() > 0) lines.add("&7Captures: &a" + objective.captureCount());
        if (objective.gateName() != null) {
            lines.add("&7Gate: &a" + objective.gateName() + (objective.gateStatus() == null ? "" : " &7(" + objective.gateStatus() + ")"));
        }
        if (objective.contested()) lines.add("&cBeing captured!");
        return lines;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
