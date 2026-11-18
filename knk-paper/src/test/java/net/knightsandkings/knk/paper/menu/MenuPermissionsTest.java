package net.knightsandkings.knk.paper.menu;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#158: menu item permissions honour the check the plugin sets (Bukkit's or KnK's). */
class MenuPermissionsTest {

    @AfterEach
    void restoreBukkitCheck() {
        MenuPermissions.setCheck(null);
    }

    @Test
    void bukkitByDefault() {
        Player player = mock(Player.class);
        when(player.hasPermission("knk.admin.user.manage")).thenReturn(true);

        assertTrue(MenuPermissions.has(player, "knk.admin.user.manage"));
        assertFalse(MenuPermissions.has(player, "knk.freeze"));
        assertFalse(MenuPermissions.has(null, "knk.freeze"));
    }

    @Test
    void anInHouseGrantCountsOnceThePluginSetsTheCheck() {
        Player player = mock(Player.class);
        MenuPermissions.setCheck((p, node) -> p.hasPermission(node) || "knk.admin.user.manage".equals(node));

        assertTrue(MenuPermissions.of(player).test("knk.admin.user.manage"));
        assertFalse(MenuPermissions.of(player).test("knk.freeze"));
    }
}
