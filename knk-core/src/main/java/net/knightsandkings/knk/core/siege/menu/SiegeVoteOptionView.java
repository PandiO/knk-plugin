package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.menu.MenuRowKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Siege Phase 8b (MENU_TEMPLATES.md C.3 {@code Votes}): one vote option - a candidate scenario or the
 * synthetic Random option (fixes N2). Row of {@code siege.vote-candidates}.
 */
public final class SiegeVoteOptionView implements MenuRowKey {

    private final KnkSiegeScenario scenario;
    private final int votes;
    private final boolean viewerVoted;
    private final boolean viewerMember;
    private final String entryRequirement;

    /**
     * @param scenario         the candidate, or null for Random
     * @param entryRequirement the candidate's title gate label, or null
     */
    public SiegeVoteOptionView(KnkSiegeScenario scenario, int votes, boolean viewerVoted, boolean viewerMember,
                               String entryRequirement) {
        this.scenario = scenario;
        this.votes = votes;
        this.viewerVoted = viewerVoted;
        this.viewerMember = viewerMember;
        this.entryRequirement = entryRequirement;
    }

    @Override
    public Object menuRowKey() {
        return "siege-vote:" + getChoiceKey();
    }

    public boolean getIsRandom() {
        return scenario == null;
    }

    /** The {@code siege.vote} param: the scenario id, or "random". */
    public String getChoiceKey() {
        return scenario == null ? SiegeMenuIds.VOTE_RANDOM : String.valueOf(scenario.id());
    }

    public String getMaterial() {
        return "MAP";
    }

    public int getVoteCount() {
        return votes;
    }

    public int getVoteCountOrOne() {
        return Math.max(1, votes);
    }

    /** HIGHLIGHT when the viewer voted for it (replaces v2's commented-out blink). */
    public String getDisplayMode() {
        return viewerVoted ? "HIGHLIGHT" : "NORMAL";
    }

    public String getTitle() {
        return scenario == null ? "&5Random" : "&a" + SiegeLobbyMenuView.name(scenario);
    }

    /** The whole lore (E8 list): the scenario's facts or the Random text, then votes and the click hint. */
    public List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (scenario == null) {
            lines.add("&7A random scenario will");
            lines.add("&7be chosen");
        } else {
            lines.add("&7Town: &a" + (scenario.townName() == null ? "-" : scenario.townName()));
            List<String> districts = scenario.districts().stream().map(KnkSiegeDistrict::name)
                    .filter(n -> n != null && !n.isBlank()).map(String::trim).toList();
            lines.add("&7Districts: &a" + (districts.isEmpty() ? "the whole town" : String.join(", ", districts)));
            lines.add("");
            lines.add("&7Players: &a" + scenario.playersMin() + "&7-&a" + scenario.playersMax());
            if (entryRequirement != null) lines.add("&7Entry: &e" + entryRequirement);
            lines.add("");
            lines.add("&7Objectives: &a" + scenario.objectives().size());
            lines.add("&7Objectives with a gate: &a" + scenario.objectives().stream().filter(KnkSiegeObjective::hasGate).count());
        }
        lines.add("");
        lines.add("&7Votes: &a" + votes);
        lines.add(viewerVoted ? "&cVoted - click to remove" : viewerMember ? "&aClick to vote" : "&cJoin the Siege to vote");
        return lines;
    }
}
