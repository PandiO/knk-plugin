package net.knightsandkings.knk.core.production.survey;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.knightsandkings.knk.core.production.survey.SurveyProfile.UnitType;


/**
 * One item of the API's survey queue ({@code GET api/ProductionAreas/survey-due}, knk-web-api {@code SurveyDueAreaDto}):
 * the area, its kind's survey profile and version, why it is due, and the other structures' shared areas whose
 * bounding box overlaps it (G7).
 *
 * @param trigger          {@code Creation}, {@code Admin}, {@code Scheduled} or {@code NotableChange}
 * @param overlapUnitTypes per overlapping area id, the unit types its structure's kind counts (PandiO/knk-workspace#875);
 *                         an area missing here counts every unit type (the API did not send them)
 */
public record SurveyDue(AreaSpec area, Integer kindId, String kindName, String profileJson, int profileVersion,
                        String trigger, List<AreaSpec> overlaps, Map<Integer, Set<UnitType>> overlapUnitTypes) {

    public SurveyDue {
        overlaps = overlaps == null ? List.of() : List.copyOf(overlaps);
        trigger = trigger == null || trigger.isBlank() ? "Scheduled" : trigger;
        overlapUnitTypes = overlapUnitTypes == null ? Map.of() : Map.copyOf(overlapUnitTypes);
    }

    /** Without unit types per overlap: every overlap shares every unit. */
    public SurveyDue(AreaSpec area, Integer kindId, String kindName, String profileJson, int profileVersion,
                     String trigger, List<AreaSpec> overlaps) {
        this(area, kindId, kindName, profileJson, profileVersion, trigger, overlaps, null);
    }

    /** The unit types the overlapping area {@code areaId} counts, or null when unknown (#875). */
    public Set<UnitType> unitTypesOf(int areaId) {
        return overlapUnitTypes.get(areaId);
    }

    /** Scheduled surveys wait for loaded chunks; requested ones may load them (P-D2: "when the chunks are loaded"). */
    public boolean scheduled() {
        return "Scheduled".equalsIgnoreCase(trigger);
    }
}
