package net.knightsandkings.knk.paper.menu;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.bukkit.entity.Player;

/**
 * The node check behind a menu item's {@code visibilityPermission}/{@code actionPermission} and the
 * {@code permission-node} condition. Bukkit's permissions by default; the plugin sets Bukkit's or KnK's (ranks,
 * wildcards; the cached answer, never waiting on the API), the same way {@code GatePassThroughRules} does.
 * PandiO/knk-workspace#158: with Bukkit's only, a rank given e.g. {@code knk.admin.user.manage} never saw the
 * Player manager tile unless the player was op.
 */
public final class MenuPermissions {

    private static volatile BiPredicate<Player, String> check = Player::hasPermission;

    private MenuPermissions() {
    }

    /** The node check; null restores Bukkit's. */
    public static void setCheck(BiPredicate<Player, String> newCheck) {
        check = newCheck == null ? Player::hasPermission : newCheck;
    }

    public static boolean has(Player player, String node) {
        return player != null && check.test(player, node);
    }

    /** {@link #has} for one viewer, as the predicate {@code RuntimeMenuItem} takes. */
    public static Predicate<String> of(Player player) {
        return node -> has(player, node);
    }
}
