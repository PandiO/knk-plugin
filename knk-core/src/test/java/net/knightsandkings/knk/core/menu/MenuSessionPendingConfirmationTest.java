package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IMPLEMENTATION_PLAN.md Phase 7 / DESIGN_REVIEW.md §2.5: the pending-
 * confirmation mechanism a ConfirmDialog section's Confirm/Cancel items act
 * on ({@link MenuSession#getPendingConfirmation}/{@link MenuSession#setPendingConfirmation}/
 * {@link MenuSession#clearPendingConfirmation}).
 */
class MenuSessionPendingConfirmationTest {

    private static MenuSession session() {
        return new MenuSessionRegistry().open(UUID.randomUUID());
    }

    @Test
    void noPendingConfirmationByDefault() {
        MenuSession session = session();

        assertTrue(session.getPendingConfirmation().isEmpty());
    }

    @Test
    void setPendingConfirmationIsRetrievable() {
        MenuSession session = session();
        MenuSession.PendingConfirmation pending =
                new MenuSession.PendingConfirmation("menu.close", Map.of(), "Are you sure?");

        session.setPendingConfirmation(pending);

        assertEquals(pending, session.getPendingConfirmation().orElseThrow());
    }

    @Test
    void clearPendingConfirmationRemovesIt() {
        MenuSession session = session();
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("menu.close", Map.of(), "Are you sure?"));

        session.clearPendingConfirmation();

        assertTrue(session.getPendingConfirmation().isEmpty());
    }

    @Test
    void requestingANewConfirmationOverwritesTheOld() {
        MenuSession session = session();
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("menu.close", Map.of(), "First?"));

        session.setPendingConfirmation(new MenuSession.PendingConfirmation("menu.open", Map.of("key", "example"), "Second?"));

        MenuSession.PendingConfirmation current = session.getPendingConfirmation().orElseThrow();
        assertEquals("menu.open", current.actionTypeId());
        assertEquals("Second?", current.promptMessage());
    }

    @Test
    void nullActionParamsNormalizesToEmptyMap() {
        MenuSession.PendingConfirmation pending = new MenuSession.PendingConfirmation("menu.close", null, "Sure?");

        assertFalse(pending.actionParams() == null);
        assertTrue(pending.actionParams().isEmpty());
    }
}
