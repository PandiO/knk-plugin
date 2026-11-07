package net.knightsandkings.knk.core.survival.purchase;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseRulesTest {

    @Test
    void rangeUsesTheConfiguredRadiusOrTheDefault() {
        SurvivalPoint point = new SurvivalPoint(0, 64, 0);
        assertTrue(PurchaseRules.withinRange(point, new SurvivalPoint(3, 64, 0), 3));
        assertFalse(PurchaseRules.withinRange(point, new SurvivalPoint(3.1, 64, 0), 3));
        assertTrue(PurchaseRules.withinRange(point, new SurvivalPoint(4, 64, 0), 0));
        assertFalse(PurchaseRules.withinRange(point, new SurvivalPoint(4.5, 64, 0), -1));
        assertFalse(PurchaseRules.withinRange(null, point, 3));
    }

    @Test
    void chargeIdsAndStacks() {
        assertTrue(PurchaseRules.chargeId(2, 5, UUID.randomUUID()).startsWith("buy-p2-o5-"));
        assertArrayEquals(new int[] {64, 64, 2}, PurchaseRules.stacks(130, 64));
        assertArrayEquals(new int[] {1}, PurchaseRules.stacks(0, 64));
        assertArrayEquals(new int[] {1, 1, 1}, PurchaseRules.stacks(3, 1));
        assertArrayEquals(new int[] {16}, PurchaseRules.stacks(16, 16));
    }
}
