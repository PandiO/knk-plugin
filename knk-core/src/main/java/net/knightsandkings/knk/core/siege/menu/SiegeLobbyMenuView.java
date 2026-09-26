package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.menu.MenuRowKey;
import net.knightsandkings.knk.core.siege.SiegePhase;

import java.util.ArrayList;
import java.util.List;

/**
 * Siege Phase 8b (DESIGN §10.3, MENU_TEMPLATES.md C.2/C.3): one lobby - root {@code siege} in
 * {@code siege.information} and {@code row} in {@code siege.overview}'s {@code siege.lobbies} rows.
 * Public zero-argument getters only (the menu engine's {@code $…$} chains). Viewer-dependent lines
 * ({@link #getJoinHintLines()}) use the viewer facts it was built with.
 */
public final class SiegeLobbyMenuView implements MenuRowKey {

    private final SiegeMenuSnapshot lobby;
    private final boolean viewerMember;
    private final String viewerJoinDenial;

    /**
     * @param viewerMember     the viewer is a member of this lobby
     * @param viewerJoinDenial why the viewer can't join right now (null = can join, or not joinable at all)
     */
    public SiegeLobbyMenuView(SiegeMenuSnapshot lobby, boolean viewerMember, String viewerJoinDenial) {
        this.lobby = lobby;
        this.viewerMember = viewerMember;
        this.viewerJoinDenial = viewerJoinDenial;
    }

    @Override
    public Object menuRowKey() {
        return "siege-lobby:" + lobby.lobbyId();
    }

    public SiegeMenuSnapshot snapshot() {
        return lobby;
    }

    public int getLobbyId() {
        return lobby.lobbyId();
    }

    public String getName() {
        return lobby.name();
    }

    public String getPhase() {
        return lobby.phase().name();
    }

    public String getPhaseLabel() {
        return SiegeMenuFormat.phaseLabel(lobby.phase());
    }

    public String getTimerLabel() {
        return switch (lobby.phase()) {
            case MATCHMAKING, HUB -> "Starts in:";
            case IN_PROGRESS -> "Ends in:";
            case COOLDOWN -> "Next round in:";
            case ENDING -> "The match is ending";
            case DISABLED -> "Not running";
        };
    }

    /** Formatted countdown, or "-" when the phase has none. */
    public String getTimeRemaining() {
        return switch (lobby.phase()) {
            case MATCHMAKING, HUB, IN_PROGRESS, COOLDOWN -> SiegeMenuFormat.duration(lobby.secondsRemaining());
            case ENDING, DISABLED -> "-";
        };
    }

    /** The drawn scenario, else the vote candidates, else "-". */
    public String getScenarioLabel() {
        if (lobby.scenario() != null) return name(lobby.scenario());
        if (!lobby.candidates().isEmpty()) {
            return "to be voted (" + String.join(" / ", lobby.candidates().stream().map(SiegeLobbyMenuView::name).toList()) + ")";
        }
        return "-";
    }

    public int getMemberCount() {
        return lobby.memberCount();
    }

    /** Stack size: the member count, at least 1 (an empty stack can't render). */
    public int getMemberCountOrOne() {
        return Math.max(1, lobby.memberCount());
    }

    public String getMemberCountLabel() {
        return "&a" + lobby.memberCount() + (lobby.capacity() > 0 ? "&7/&a" + lobby.capacity() : "");
    }

    /** Null until a scenario is drawn. */
    public Integer getPlayersMin() {
        return lobby.scenario() == null ? null : lobby.scenario().playersMin();
    }

    public Integer getPlayersMax() {
        return lobby.scenario() == null ? null : lobby.scenario().playersMax();
    }

    /** "&7Min. players: &a2", or null until a scenario is drawn (line omitted, E8). */
    public String getPlayersMinLine() {
        return lobby.scenario() == null ? null : "&7Min. players: &a" + lobby.scenario().playersMin();
    }

    public String getPlayersMaxLine() {
        return lobby.scenario() == null ? null : "&7Max. players: &a" + lobby.scenario().playersMax();
    }

    public int getObjectiveCount() {
        return lobby.scenario() == null ? 0 : lobby.scenario().objectives().size();
    }

    public int getGateObjectiveCount() {
        return lobby.scenario() == null ? 0 : (int) lobby.scenario().objectives().stream().filter(KnkSiegeObjective::hasGate).count();
    }

    /** Phase colour: green matchmaking, red preparing/in progress, orange ending, white otherwise. */
    public String getBannerMaterial() {
        return switch (lobby.phase()) {
            case MATCHMAKING -> "GREEN_BANNER";
            case HUB, IN_PROGRESS -> "RED_BANNER";
            case ENDING -> "ORANGE_BANNER";
            case COOLDOWN, DISABLED -> "WHITE_BANNER";
        };
    }

    /** "&7Entry: &eSquire" when the next scenario has a title gate, else null (line omitted, E8). */
    public String getEntryRequirementLine() {
        return lobby.entryRequirement() == null ? null : "&7Entry: &e" + lobby.entryRequirement();
    }

    /** The click hint for this viewer (E8 list). */
    public List<String> getJoinHintLines() {
        if (viewerMember) return List.of("&aYou are in this siege - click to open it");
        if (lobby.joinable()) {
            return viewerJoinDenial == null ? List.of("&aClick to view and join!")
                    : List.of("&cCan't join: " + viewerJoinDenial, "&7Click to view");
        }
        return List.of("&cCan't join match!", "&cWait for matchmaking to start", "&7Click to view");
    }

    /** One line per team once teams exist: "&cRaiders&7: &a3 &7players, &a1 &7objectives held" (E8 list). */
    public List<String> getTeamSummaryLines() {
        List<String> lines = new ArrayList<>();
        for (SiegeMenuSnapshot.Team team : lobby.teams()) {
            lines.add(SiegeMenuFormat.color(team.chatColor()) + team.name() + "&7: &a" + team.memberCount()
                    + " &7players, &a" + team.objectivesHeld() + " &7objectives held");
        }
        return lines;
    }

    public String getRecaptureLine() {
        return lobby.scenario() != null && lobby.scenario().allowRecapture() ? "&7Captured objectives can be retaken." : null;
    }

    /** True while voting is open (render conditions via value-equals). */
    public boolean getVotingOpen() {
        return lobby.votingOpen();
    }

    public boolean getScenarioKnown() {
        return lobby.scenario() != null;
    }

    public boolean getInProgress() {
        return lobby.phase() == SiegePhase.IN_PROGRESS;
    }

    static String name(KnkSiegeScenario scenario) {
        return scenario.name() == null || scenario.name().isBlank() ? "Scenario " + scenario.id() : scenario.name().trim();
    }
}
