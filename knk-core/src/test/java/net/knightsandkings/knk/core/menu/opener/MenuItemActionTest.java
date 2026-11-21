package net.knightsandkings.knk.core.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Siege shop DESIGN § 4.3.2, P26 (PandiO/knk-workspace#871): the three action types. */
class MenuItemActionTest {

    @Test
    void theApiNamesParse() {
        assertEquals(MenuItemAction.Type.OPEN_MENU, MenuItemAction.Type.parse("OpenMenu"));
        assertEquals(MenuItemAction.Type.RUN_COMMAND, MenuItemAction.Type.parse("run_command"));
        assertEquals(MenuItemAction.Type.NONE, MenuItemAction.Type.parse(" none "));
        assertNull(MenuItemAction.Type.parse("Teleport"));
        assertNull(MenuItemAction.Type.parse(null));
    }

    @Test
    void aCommandLosesItsSlash_AndAnActionWithoutItsTargetDoesNothing() {
        assertEquals("siege shop", MenuItemAction.runCommand(" /siege shop ").target());
        assertTrue(MenuItemAction.openMenu(" ").isNone());
        assertTrue(MenuItemAction.runCommand("/").isNone());
        assertNull(new MenuItemAction(MenuItemAction.Type.NONE, "main").target());
        assertEquals(MenuItemAction.none(), new MenuItemAction(MenuItemAction.Type.OPEN_MENU, null));
    }
}
