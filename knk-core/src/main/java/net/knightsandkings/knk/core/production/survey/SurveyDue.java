package net.knightsandkings.knk.core.production.survey;

import java.util.List;

/**
 * One item of the API's survey queue ({@code GET api/ProductionAreas/survey-due}, knk-web-api {@code SurveyDueAreaDto}):
 * the area, its kind's survey profile and version, why it is due, and the other structures' shared areas whose
 * bounding box overlaps it (G7).
 *
 * @param trigger {@code Creation}, {@code Admin}, {@code Scheduled} or {@code NotableChange}
 */
public record SurveyDue(AreaSpec area, Integer kindId, String kindName, String profileJson, int profileVersion,
                        String trigger, List<AreaSpec> overlaps) {

    public SurveyDue {
        overlaps = overlaps == null ? List.of() : List.copyOf(overlaps);
        trigger = trigger == null || trigger.isBlank() ? "Scheduled" : trigger;
    }

    /** Scheduled surveys wait for loaded chunks; requested ones may load them (P-D2: "when the chunks are loaded"). */
    public boolean scheduled() {
        return "Scheduled".equalsIgnoreCase(trigger);
    }
}
