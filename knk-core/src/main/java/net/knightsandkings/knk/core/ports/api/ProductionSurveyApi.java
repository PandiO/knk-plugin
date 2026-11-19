package net.knightsandkings.knk.core.ports.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveyUnit;

/**
 * Yield-area surveys, game server side (PandiO/knk-workspace#679; the API contract of #138, knk-web-api
 * {@code ProductionAreasController}). The API answers 409 while {@code Production:Enabled} is off or for a
 * {@code Configured} structure; failures carry the status code ({@code ApiException}).
 */
public interface ProductionSurveyApi {

    /** A survey to report. {@code trigger} null = the area's pending request, else Scheduled. */
    record Report(Instant surveyedAt, int profileVersion, String trigger, String contentHash, String note,
                  List<SurveyUnit> units) {
        public Report {
            units = units == null ? List.of() : List.copyOf(units);
        }
    }

    /** The API's answer: the stored survey and how many production lines its materialization created or updated. */
    record ReportResult(long surveyId, long totalUnits, int linesCreated, int linesUpdated) {
    }

    /** {@code GET api/ProductionAreas/survey-due?limit=}: requested first, then never surveyed, then older than 24 h. */
    CompletableFuture<List<SurveyDue>> surveyDue(int limit);

    /** {@code POST api/ProductionAreas/{id}/surveys}. */
    CompletableFuture<ReportResult> report(int areaId, Report report);

    /** {@code POST api/ProductionStructures/{id}/survey} ("survey now"; {@code areaId} null = every enabled area). */
    CompletableFuture<List<AreaSpec>> requestSurvey(int structureId, Integer areaId);

    /** {@code GET api/ProductionAreas}: every area (for {@code area show} and the notable-change counters). */
    CompletableFuture<List<AreaSpec>> areas();
}
