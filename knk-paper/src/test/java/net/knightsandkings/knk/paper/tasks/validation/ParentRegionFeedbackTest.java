package net.knightsandkings.knk.paper.tasks.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.knightsandkings.knk.core.domain.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-173: a rule's failures follow its isBlocking and name what the region or location must be inside. */
class ParentRegionFeedbackTest {
    /** The GateStructure seed's Region rule from the 2026-10-12 smoke test (isBlocking set per test). */
    private static JsonObject gateRegionRule(Boolean isBlocking) {
        JsonObject rule = JsonParser.parseString("""
                {"validationType":"RegionContainment","dependencyPath":"District.WgRegionId",
                 "configJson":"{\\"parentRegionPath\\": \\"WgRegionId\\", \\"requireFullContainment\\": true}",
                 "errorMessage":"The gate's region is not completely inside the district's region.",
                 "dependsOnField":{"id":550,"fieldName":"DistrictId","label":"District"},
                 "dependencyFieldValue":{"id":27,"name":"kng-162 step d2.5","wgRegionId":"domain_27",
                   "worldName":"knk_hub","townId":19,"townName":"Kardenna"}}
                """).getAsJsonObject();
        if (isBlocking != null) {
            rule.addProperty("isBlocking", isBlocking);
        }
        return rule;
    }

    private static ParentRegionFeedback district(Boolean isBlocking) {
        JsonObject rule = gateRegionRule(isBlocking);
        return ParentRegionFeedback.of(rule, rule.get("dependencyFieldValue"), "domain_27");
    }

    @Test
    void notInsideIsAWarningUnlessTheRuleBlocks() {
        ValidationResult warning = district(false).notInside("Outside.", null);
        ValidationResult missingFlag = district(null).notInside("Outside.", null);
        ValidationResult block = district(true).notInside("Outside.", null);

        assertFalse(warning.isValid());
        assertFalse(warning.isBlocking());
        assertFalse(missingFlag.isBlocking());
        assertFalse(block.isValid());
        assertTrue(block.isBlocking());
    }

    @Test
    void aMissingParentRegionIsAWarningUnlessTheRuleBlocks() {
        assertFalse(district(false).parentRegionMissing("knk_hub").isBlocking());
        assertFalse(district(null).parentRegionMissing("knk_hub").isBlocking());
        assertTrue(district(true).parentRegionMissing("knk_hub").isBlocking());
        assertFalse(district(false).parentRegionMissing("knk_hub").isValid());
    }

    @Test
    void theWrongWorldAndMissingRegionDataFollowTheRuleToo() {
        assertFalse(district(false).wrongWorld("Your selection", "world", "knk_hub").isBlocking());
        assertTrue(district(true).wrongWorld("Your selection", "world", "knk_hub").isBlocking());
        assertFalse(district(false).noRegionData("knk_hub").isBlocking());
        assertTrue(district(true).noRegionData("knk_hub").isBlocking());
    }

    @Test
    void notInsideKeepsTheRulesMessageAndNamesTheEntityItsRegionAndTheCornersOutside() {
        ValidationResult result = district(false).notInside(
                "The gate's region is not completely inside the district's region.",
                "Corners outside it: (1, 64, 2)");

        assertEquals("""
                The gate's region is not completely inside the district's region.
                Must be inside District "kng-162 step d2.5" (region domain_27).
                Corners outside it: (1, 64, 2)""", result.getMessage());
    }

    @Test
    void aMissingParentRegionGetsItsOwnMessageNamingEntityRegionAndWorld() {
        assertEquals("District \"kng-162 step d2.5\" has region domain_27, but that region doesn't exist in world "
                        + "knk_hub, so the check couldn't run.",
                district(false).parentRegionMissing("knk_hub").getMessage());
    }

    @Test
    void aMissingParentRegionSaysWhenItsOwnWorldWasNotLoaded() {
        assertEquals("District \"kng-162 step d2.5\" has region domain_27, but that region doesn't exist in world "
                        + "world (its own world knk_hub isn't loaded), so the check couldn't run.",
                district(false).parentRegionMissing("world").getMessage());
    }

    @Test
    void theWrongWorldNamesBothWorlds() {
        assertEquals("Your selection is in world world_nether, but District \"kng-162 step d2.5\" (region domain_27) "
                        + "is in world knk_hub.",
                district(true).wrongWorld("Your selection", "world_nether", "knk_hub").getMessage());
    }

    @Test
    void theParentsWorldComesFromTheDependency() {
        assertEquals("knk_hub", district(false).worldName());

        JsonObject pascal = JsonParser.parseString("{\"Name\":\"Docks\",\"WorldName\":\"knk_city\"}").getAsJsonObject();
        assertEquals("knk_city", ParentRegionFeedback.of(new JsonObject(), pascal, "domain_3").worldName());
        assertEquals(null, ParentRegionFeedback.of(new JsonObject(), new JsonPrimitive("domain_3"), "domain_3")
                .worldName());
    }

    @Test
    void theTypeFallsBackToTheDependencyPathThenToTheParent() {
        JsonObject rule = gateRegionRule(false);
        rule.remove("dependsOnField");
        assertEquals("Must be inside District \"kng-162 step d2.5\" (region domain_27).",
                ParentRegionFeedback.of(rule, rule.get("dependencyFieldValue"), "domain_27").mustBeInsideLine());

        rule.remove("dependencyPath");
        assertEquals("Must be inside the parent \"kng-162 step d2.5\" (region domain_27).",
                ParentRegionFeedback.of(rule, rule.get("dependencyFieldValue"), "domain_27").mustBeInsideLine());
    }

    @Test
    void aBlankLabelFallsBackToo() {
        JsonObject rule = gateRegionRule(false);
        rule.getAsJsonObject("dependsOnField").addProperty("label", "  ");
        assertEquals("District", ParentRegionFeedback.entityTypeLabel(rule));
    }

    @Test
    void withoutANameTheEntityIsNamedByItsType() {
        JsonObject rule = gateRegionRule(false);
        ParentRegionFeedback justTheRegion = ParentRegionFeedback.of(rule, new JsonPrimitive("domain_27"), "domain_27");

        assertEquals("Must be inside the District (region domain_27).", justTheRegion.mustBeInsideLine());
        assertEquals("The District has region domain_27, but that region doesn't exist in world knk_hub, so the "
                + "check couldn't run.", justTheRegion.parentRegionMissing("knk_hub").getMessage());

        ParentRegionFeedback nothingKnown = ParentRegionFeedback.of(new JsonObject(), new JsonPrimitive("x"), "x");
        assertEquals("Must be inside the parent (region x).", nothingKnown.mustBeInsideLine());
    }

    @Test
    void aRulesOwnMessageCanUseTheParentPlaceholders() {
        String message = district(false).interpolate(
                "Must stay in {parentEntityType} {parentEntityName} ({parentRegionName}); {violationCount} outside.",
                null, violations());

        assertEquals("Must stay in District kng-162 step d2.5 (domain_27); 2 outside.", message);
    }

    @Test
    void computedPlaceholdersWinOverPreResolvedOnes() {
        JsonObject preResolved = new JsonObject();
        preResolved.addProperty("parentEntityName", "stale");
        preResolved.addProperty("townName", "Kardenna");

        assertEquals("kng-162 step d2.5 in Kardenna",
                district(false).interpolate("{parentEntityName} in {townName}", preResolved, null));
    }

    @Test
    void isBlockingReadsStringsAndIgnoresJunk() {
        JsonObject rule = new JsonObject();
        rule.addProperty("isBlocking", "true");
        assertTrue(ParentRegionFeedback.isBlocking(rule));
        rule.addProperty("isBlocking", "nope");
        assertFalse(ParentRegionFeedback.isBlocking(rule));
        rule.add("isBlocking", new JsonObject());
        assertFalse(ParentRegionFeedback.isBlocking(rule));
        JsonObject pascal = new JsonObject();
        pascal.addProperty("IsBlocking", true);
        assertTrue(ParentRegionFeedback.isBlocking(pascal));
    }

    @Test
    void aBlockWinsOverWarningsFromOtherRules() {
        ValidationResult combined = ParentRegionFeedback.combine(List.of(
                ValidationResult.warning("first warns"),
                ValidationResult.success("ok"),
                ValidationResult.blockingFailure("second blocks")));

        assertFalse(combined.isValid());
        assertTrue(combined.isBlocking());
        assertEquals("second blocks", combined.getMessage());
    }

    @Test
    void warningsAreAllShownAndNothingFailedIsASuccess() {
        ValidationResult warnings = ParentRegionFeedback.combine(List.of(
                ValidationResult.warning("one"), ValidationResult.warning("two")));
        assertFalse(warnings.isBlocking());
        assertEquals("one\ntwo", warnings.getMessage());

        assertTrue(ParentRegionFeedback.combine(List.of(ValidationResult.success("ok"))).isValid());
        assertTrue(ParentRegionFeedback.combine(List.of()).isValid());
    }

    @Test
    void aBlockSaysItFailedAndGivesTheAdvice() {
        List<String> lines = ParentRegionFeedback.chatLines(district(true).notInside("Outside.", null),
                List.of("Region not saved."), "saving anyway");

        assertEquals(List.of(
                "§c✗ Validation failed:",
                "§cOutside.",
                "§cMust be inside District \"kng-162 step d2.5\" (region domain_27).",
                "§eRegion not saved."), lines);
    }

    @Test
    void aWarningSaysItIsOnlyAWarning() {
        List<String> lines = ParentRegionFeedback.chatLines(district(false).parentRegionMissing("knk_hub"),
                List.of("Region not saved."), "Warning only - saving the region anyway.");

        assertEquals("§e⚠ Validation warning:", lines.get(0));
        assertTrue(lines.get(1).startsWith("§eDistrict \"kng-162 step d2.5\" has region domain_27"));
        assertEquals("§7Warning only - saving the region anyway.", lines.get(lines.size() - 1));
        assertFalse(lines.stream().anyMatch(line -> line.contains("not saved")));
    }

    @Test
    void aPassedValidationPrintsNothing() {
        assertTrue(ParentRegionFeedback.chatLines(ValidationResult.success("ok"), List.of("x"), "y").isEmpty());
    }

    private static JsonObject violations() {
        JsonObject computed = new JsonObject();
        computed.addProperty("violationCount", "2");
        return computed;
    }
}
