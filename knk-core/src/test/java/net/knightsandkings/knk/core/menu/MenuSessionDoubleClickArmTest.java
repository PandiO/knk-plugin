package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-Phase-8 QOL follow-up: the "click again within N ticks to confirm"
 * mechanism ({@link MenuSession#armDoubleClick}/{@link MenuSession#isDoubleClickArmed}/
 * {@link MenuSession#clearDoubleClickArm}) that replaced the separate
 * Confirm/Cancel button pair for {@code menu.confirm.doubleclick}.
 */
class MenuSessionDoubleClickArmTest {

    private static MenuSession session() {
        return new MenuSessionRegistry().open(UUID.randomUUID());
    }

    @Test
    void notArmedByDefault() {
        MenuSession session = session();

        assertTrue(session.getDoubleClickArm().isEmpty());
        assertFalse(session.isDoubleClickArmed(1, 1000L, 60));
    }

    @Test
    void secondClickWithinWindowIsArmed() {
        MenuSession session = session();
        session.armDoubleClick(42, 1000L);

        assertTrue(session.isDoubleClickArmed(42, 1059L, 60));
    }

    @Test
    void secondClickAtOrAfterWindowExpiryIsNotArmed() {
        MenuSession session = session();
        session.armDoubleClick(42, 1000L);

        assertFalse(session.isDoubleClickArmed(42, 1060L, 60));
        assertFalse(session.isDoubleClickArmed(42, 2000L, 60));
    }

    @Test
    void armingADifferentItemDoesNotConfirmTheFirst() {
        MenuSession session = session();
        session.armDoubleClick(1, 1000L);

        assertFalse(session.isDoubleClickArmed(2, 1010L, 60));
    }

    @Test
    void clearDoubleClickArmRemovesIt() {
        MenuSession session = session();
        session.armDoubleClick(42, 1000L);

        session.clearDoubleClickArm();

        assertTrue(session.getDoubleClickArm().isEmpty());
        assertFalse(session.isDoubleClickArmed(42, 1010L, 60));
    }

    @Test
    void armingAgainOverwritesThePreviousArm() {
        MenuSession session = session();
        session.armDoubleClick(1, 1000L);

        session.armDoubleClick(2, 1010L);

        assertFalse(session.isDoubleClickArmed(1, 1020L, 60));
        assertTrue(session.isDoubleClickArmed(2, 1020L, 60));
    }
}
