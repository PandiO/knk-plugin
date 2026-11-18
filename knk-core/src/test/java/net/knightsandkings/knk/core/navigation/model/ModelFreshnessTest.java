package net.knightsandkings.knk.core.navigation.model;

import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.T0;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import org.junit.jupiter.api.Test;

/** Destination engine DESIGN § 7.3, § 7.4 (A13, A15): limited mode and the staleness note. */
class ModelFreshnessTest {

    private static final Duration FOURTEEN_DAYS = Duration.ofDays(14);

    @Test
    void withoutAnyModelTheListIsLimitedWithTheDesignsText() {
        ModelFreshness freshness = ModelFreshness.of(null, T0, FOURTEEN_DAYS);

        assertEquals(ModelFreshness.State.LIMITED, freshness.state());
        assertTrue(freshness.degraded());
        assertEquals("Some places aren't available while the web API is down.", freshness.text());
        assertEquals("limited (no snapshot)", freshness.describe());
    }

    @Test
    void aModelWithinTheLimitIsFresh() {
        ModelFreshness freshness = ModelFreshness.of(model(T0), T0.plus(Duration.ofDays(14)), FOURTEEN_DAYS);

        assertEquals(ModelFreshness.State.FRESH, freshness.state());
        assertFalse(freshness.degraded());
        assertNull(freshness.text());
        assertEquals("fresh (14 days old)", freshness.describe());
    }

    @Test
    void aModelOlderThanTheLimitGetsTheMayBeOutOfDateNote() {
        ModelFreshness freshness = ModelFreshness.of(model(T0), T0.plus(Duration.ofDays(15)), FOURTEEN_DAYS);

        assertEquals(ModelFreshness.State.STALE, freshness.state());
        assertTrue(freshness.degraded());
        assertEquals("The list of places may be out of date (last updated 15 days ago).", freshness.text());
        assertTrue(freshness.text().contains("may be out of date"));
    }

    @Test
    void theLimitIsConfigurableButAtLeastADay() {
        assertEquals(ModelFreshness.State.STALE,
            ModelFreshness.of(model(T0), T0.plus(Duration.ofDays(3)), Duration.ofDays(2)).state());
        assertEquals(ModelFreshness.State.FRESH,
            ModelFreshness.of(model(T0), T0.plus(Duration.ofHours(20)), Duration.ZERO).state(), "0 counts as one day");
        assertEquals(ModelFreshness.State.STALE,
            ModelFreshness.of(model(T0), T0.plus(Duration.ofHours(25)), Duration.ZERO).state());
        assertEquals("The list of places may be out of date (last updated 1 day ago).",
            ModelFreshness.staleText(Duration.ofHours(25)));
    }

    @Test
    void aClockBehindTheModelCountsAsNoAge() {
        ModelFreshness freshness = ModelFreshness.of(model(T0), T0.minusSeconds(30), FOURTEEN_DAYS);

        assertEquals(Duration.ZERO, freshness.age());
        assertEquals("fresh (0 s old)", freshness.describe());
    }

    @Test
    void theNoteGoesOnTheEnginesListOnlyWhenDegraded() {
        DestinationList list = new DestinationList("world", "the world", List.of(), List.of(), null, false, null, 3);

        assertSame(list, ModelFreshness.of(model(T0), T0, FOURTEEN_DAYS).applyTo(list));
        DestinationList limited = ModelFreshness.of(null, T0, FOURTEEN_DAYS).applyTo(list);
        assertTrue(limited.degraded());
        assertEquals(ModelFreshness.LIMITED_TEXT, limited.degradedText());
        DestinationList stale = ModelFreshness.of(model(T0), T0.plus(Duration.ofDays(20)), FOURTEEN_DAYS).applyTo(list);
        assertTrue(stale.degraded());
        assertTrue(stale.degradedText().contains("may be out of date"));
    }
}
