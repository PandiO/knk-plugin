package net.knightsandkings.knk.core.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.DomainInteraction;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MenuStep;

/** KNG-34 link 7: domain interactions (window counts, the day's distinct players) and menu funnel steps. */
class DomainInteractionCounterTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void windowCounts_andDistinctPlayersOfTheDay() {
        DomainInteractionCounter counter = new DomainInteractionCounter();
        counter.region("aldmoor", DomainInteractionCounter.ENTER, ALICE);
        counter.region("aldmoor", DomainInteractionCounter.ENTER, ALICE);
        counter.region("aldmoor", DomainInteractionCounter.ENTER, BOB);
        counter.region("aldmoor", DomainInteractionCounter.LEAVE, ALICE);
        counter.discovered(7, BOB);

        assertEquals(List.of(
            new DomainInteraction(null, "aldmoor", "enter", 3, 2),
            new DomainInteraction(null, "aldmoor", "leave", 1, 1),
            new DomainInteraction(7, null, "discover", 1, 1)), counter.drain());
        assertTrue(counter.isEmpty());
    }

    @Test
    void distinctPlayers_carryAcrossWindowsOfTheSameDay_andResetOnANewDay() {
        DomainInteractionCounter counter = new DomainInteractionCounter();
        counter.region("aldmoor", DomainInteractionCounter.ENTER, ALICE);
        counter.drain();

        counter.region("aldmoor", DomainInteractionCounter.ENTER, ALICE);
        counter.region("aldmoor", DomainInteractionCounter.ENTER, BOB);
        assertEquals(List.of(new DomainInteraction(null, "aldmoor", "enter", 2, 2)), counter.drain());

        counter.newDay();
        counter.region("aldmoor", DomainInteractionCounter.ENTER, BOB);
        assertEquals(List.of(new DomainInteraction(null, "aldmoor", "enter", 1, 1)), counter.drain());
    }

    @Test
    void blankRegionsAndNonPositiveDomains_areIgnored() {
        DomainInteractionCounter counter = new DomainInteractionCounter();
        counter.region(" ", DomainInteractionCounter.ENTER, ALICE);
        counter.region(null, DomainInteractionCounter.ENTER, ALICE);
        counter.discovered(0, ALICE);
        counter.region("x", DomainInteractionCounter.ENTER, null);

        assertEquals(List.of(new DomainInteraction(null, "x", "enter", 1, 0)), counter.drain());
    }

    @Test
    void menuFunnelSteps_areCountedPerMenuStepAndOutcome() {
        MenuFunnelCounter menus = new MenuFunnelCounter();
        menus.opened("profile.main");
        menus.opened("profile.main");
        menus.action("profile.main", "Menu.Open", MenuFunnelCounter.SUCCEEDED);
        menus.action("profile.main", "menu.open", MenuFunnelCounter.DENIED);
        menus.action("profile.main", " ", MenuFunnelCounter.FAILED);
        menus.back("profile.main");
        menus.closed("profile.main");
        menus.closed(null);
        menus.action("p", "x".repeat(200), MenuFunnelCounter.FAILED);

        List<MenuStep> steps = menus.drain();

        assertEquals(List.of(
            new MenuStep("profile.main", "opened", "info", 2),
            new MenuStep("profile.main", "action:menu.open", "succeeded", 1),
            new MenuStep("profile.main", "action:menu.open", "denied", 1),
            new MenuStep("profile.main", "back", "info", 1),
            new MenuStep("profile.main", "closed", "info", 1),
            new MenuStep("p", "action:" + "x".repeat(MenuFunnelCounter.MAX_ACTION_ID), "failed", 1)), steps);
        assertEquals(96, steps.get(5).step().length());
        assertTrue(menus.isEmpty());
    }
}
