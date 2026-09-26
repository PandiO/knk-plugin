package net.knightsandkings.knk.core.siege.menu;

/**
 * Siege Phase 8b (DESIGN §10.3): the viewer's side of a lobby - root {@code siegeViewer}
 * (MENU_TEMPLATES.md C.3 header/actions, C.4 header).
 */
public final class SiegeViewerMenuView {

    private final boolean member;
    private final String joinDenial;
    private final SiegeMenuSnapshot.Team team;
    private final String currentSpawnName;

    /**
     * @param member           the viewer is a member of the lobby
     * @param joinDenial       why they can't join (null = they can, or they are a member)
     * @param team             their team once split, else null
     * @param currentSpawnName their current respawn choice's name, or null for the default
     */
    public SiegeViewerMenuView(boolean member, String joinDenial, SiegeMenuSnapshot.Team team, String currentSpawnName) {
        this.member = member;
        this.joinDenial = joinDenial;
        this.team = team;
        this.currentSpawnName = currentSpawnName;
    }

    public static SiegeViewerMenuView outsider(String joinDenial) {
        return new SiegeViewerMenuView(false, joinDenial, null, null);
    }

    public boolean getIsMember() {
        return member;
    }

    public String getTeamLine() {
        return team == null ? null : "&7Your team: " + SiegeMenuFormat.color(team.chatColor()) + team.name();
    }

    /** The viewer's team banner (E6 BannerPatterns), or null. */
    public String getTeamBannerPatterns() {
        return team == null ? null : team.bannerPatterns();
    }

    public String getJoinLeaveMaterial() {
        return member ? "SPRUCE_DOOR" : "GREEN_CONCRETE";
    }

    public String getJoinLeaveName() {
        return member ? "&cClick to leave" : "&aClick to join";
    }

    public String getParticipationLine() {
        return member ? "&7You are in this siege." : "&7You are not in this siege.";
    }

    public String getJoinDenialLine() {
        return member || joinDenial == null ? null : "&c" + joinDenial;
    }

    public String getCurrentSpawnName() {
        return currentSpawnName == null || currentSpawnName.isBlank() ? "Default" : currentSpawnName;
    }
}
