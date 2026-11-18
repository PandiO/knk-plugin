package net.knightsandkings.knk.core.navigation.model;

import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.T0;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.model;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Destination engine DESIGN § 11: the model swapped whole; the version moves only with the content (E18). */
class NavigationModelHolderTest {

    @Test
    void emptyUntilTheFirstModel() {
        NavigationModelHolder holder = new NavigationModelHolder();

        assertNull(holder.current());
        assertEquals(0, holder.version());
        assertFalse(holder.update(m -> m.withAsOf(T0)), "nothing to update");
    }

    @Test
    void theVersionMovesOnlyWhenTheContentChanges() {
        NavigationModelHolder holder = new NavigationModelHolder();
        List<NavigationModel> told = new ArrayList<>();
        holder.onChange(told::add);

        assertTrue(holder.replace(model(T0)));
        assertEquals(1, holder.version());

        assertFalse(holder.replace(model(T0.plusSeconds(60))), "confirmed, not changed");
        assertEquals(1, holder.version());
        assertEquals(T0.plusSeconds(60), holder.current().asOf(), "the newer asOf is taken");

        assertFalse(holder.replace(model(T0)), "an older confirmation is not taken");
        assertEquals(T0.plusSeconds(60), holder.current().asOf());

        assertTrue(holder.update(m -> m.withPlace(3, point(8, "Market spawn", 5, "world"))));
        assertEquals(2, holder.version());
        assertEquals(2, told.size(), "listeners hear content changes only");
    }

    @Test
    void aFailingListenerDoesNotStopTheSwap() {
        NavigationModelHolder holder = new NavigationModelHolder();
        holder.onChange(m -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(holder.replace(model(T0)));
        assertEquals(model(T0), holder.current());
    }
}
