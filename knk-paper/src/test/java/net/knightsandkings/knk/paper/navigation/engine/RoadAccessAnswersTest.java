package net.knightsandkings.knk.paper.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations;

/**
 * The road-access answers (phase 3, PandiO/knk-workspace#69, E19, A16, A21): today's catalogue with the flag off,
 * the navigation model with it on, the same answers for the same data.
 */
class RoadAccessAnswersTest {

    private final NavigationDestinations catalogue = mock(NavigationDestinations.class);
    private final NavigationModelHolder holder = new NavigationModelHolder();

    private static NavigationModel model(Instant asOf, String millAccess) {
        return new NavigationModel(asOf, List.of(
            new DomainPlace(9, "Mill House", "Structure", null, millAccess, "Mill_House", "world", null, true),
            new DomainPlace(3, "Market", "District", null, "Applies", "district_3", "world", null, true)),
            List.of(), CompassSettings.defaults(), List.of());
    }

    @Test
    void withTheFlagOffTheCatalogueAnswersAndTheModelIsNotAsked() {
        when(catalogue.roadAccessIgnored(9)).thenReturn(true);
        when(catalogue.roadsIgnoreRegion("mill_house")).thenReturn(true);
        holder.replace(model(Instant.EPOCH, "Applies"));
        RoadAccessAnswers answers = new RoadAccessAnswers(false, () -> catalogue, () -> holder);
        assertTrue(answers.roadAccessIgnored(9));
        assertFalse(answers.roadAccessIgnored(3));
        assertTrue(answers.roadsIgnoreRegion("mill_house"));
        Runnable listener = () -> { };
        answers.onRoadAccessChanged(listener);
        verify(catalogue).onRoadAccessChanged(listener);
    }

    @Test
    void withTheFlagOffAndNoCatalogueEveryRuleIsKept() {
        RoadAccessAnswers answers = new RoadAccessAnswers(false, () -> null, () -> holder);
        assertFalse(answers.roadAccessIgnored(9));
        assertFalse(answers.roadsIgnoreRegion("mill_house"));
        answers.onRoadAccessChanged(() -> { });
    }

    @Test
    void withTheFlagOnTheModelAnswersAndTheCatalogueIsNotAsked() {
        holder.replace(model(Instant.EPOCH, "Ignored"));
        RoadAccessAnswers answers = new RoadAccessAnswers(true, () -> catalogue, () -> holder);
        assertTrue(answers.roadAccessIgnored(9));
        assertFalse(answers.roadAccessIgnored(3));
        assertFalse(answers.roadAccessIgnored(999));
        assertTrue(answers.roadsIgnoreRegion("MILL_HOUSE"), "regions ignore case, as today");
        assertFalse(answers.roadsIgnoreRegion("district_3"));
        assertFalse(answers.roadsIgnoreRegion(null));
        answers.onRoadAccessChanged(() -> { });
        verifyNoInteractions(catalogue);
    }

    @Test
    void withTheFlagOnAndNoModelYetEveryRuleIsKept() {
        RoadAccessAnswers answers = new RoadAccessAnswers(true, () -> catalogue, () -> holder);
        assertFalse(answers.roadAccessIgnored(9));
        assertFalse(answers.roadsIgnoreRegion("mill_house"));
        assertFalse(new RoadAccessAnswers(true, () -> catalogue, () -> null).roadAccessIgnored(9));
    }

    @Test
    void withTheFlagOnTheListenerRunsOnlyWhenTheIgnoredRegionsChange() {
        holder.replace(model(Instant.EPOCH, "Applies"));
        RoadAccessAnswers answers = new RoadAccessAnswers(true, () -> catalogue, () -> holder);
        AtomicInteger calls = new AtomicInteger();
        answers.onRoadAccessChanged(calls::incrementAndGet);
        holder.replace(model(Instant.EPOCH.plusSeconds(60), "Ignored"));
        assertEquals(1, calls.get(), "Mill House became Ignored");
        holder.replace(new NavigationModel(Instant.EPOCH.plusSeconds(120), List.of(
            new DomainPlace(9, "Mill House (renamed)", "Structure", null, "Ignored", "Mill_House", "world", null, true)),
            List.of(), CompassSettings.defaults(), List.of()));
        assertEquals(1, calls.get(), "a change elsewhere in the model does not recut the roads");
        holder.replace(model(Instant.EPOCH.plusSeconds(180), "Applies"));
        assertEquals(2, calls.get());
        verify(catalogue, never()).onRoadAccessChanged(org.mockito.ArgumentMatchers.any());
    }
}
