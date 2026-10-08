package net.knightsandkings.knk.paper.navigation;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.roads.route.EtaEstimator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The navigating player's HUD (DESIGN §6.4): an Adventure boss bar with "→ Merchantstreet · 340 m ·
 * ~1 min" and the progress travelled / total, and an action-bar arrow towards the trail point ahead
 * for players with minimal particles. One boss bar per player, shown on the first update and hidden
 * on {@link #hide}. Main thread. The arrow maths is pure ({@link #arrow}) for the tests.
 */
public final class NavigationHud {

    /** Eight arrows, clockwise from "ahead": ⬆ ⬈ ➡ ⬊ ⬇ ⬋ ⬅ ⬉. */
    public static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};

    private final EtaEstimator eta;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public NavigationHud(EtaEstimator eta) {
        this.eta = Objects.requireNonNull(eta, "eta");
    }

    /**
     * Refresh the boss bar: {@code label} is the next maneuver within 20 blocks or the destination,
     * {@code remaining} the walked blocks still to go, {@code progress} 0..1.
     */
    public void update(Player player, String label, double remaining, double progress) {
        Component text = Component.text(NavigationMessages.bossBar(label, remaining, eta), NamedTextColor.GOLD);
        float clamped = (float) Math.max(0, Math.min(1, progress));
        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(text, clamped, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(text);
            bar.progress(clamped);
        }
    }

    /** The action-bar arrow from the player's facing towards {@code (x, z)} (the trail point ahead). */
    public void arrowTowards(Player player, double x, double z) {
        double dx = x - player.getLocation().getX();
        double dz = z - player.getLocation().getZ();
        if (dx * dx + dz * dz < 0.25) {
            return;
        }
        player.sendActionBar(Component.text(arrow(player.getLocation().getYaw(), dx, dz), NamedTextColor.GOLD));
    }

    /** Hide and forget the player's boss bar and clear the action bar. */
    public void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        player.sendActionBar(Component.empty());
    }

    public void forget(UUID playerId) {
        bars.remove(playerId);
    }

    public boolean isShowing(UUID playerId) {
        return bars.containsKey(playerId);
    }

    // ==================== pure ====================

    /**
     * The arrow to show for a player facing {@code yaw} (Bukkit: 0 = south, 90 = west, increasing
     * clockwise seen from above) whose target lies {@code (dx, dz)} away: "⬆" straight ahead, "➡"
     * to the right, and so on in 45° bands.
     */
    public static String arrow(float yaw, double dx, double dz) {
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        return ARROWS[arrowIndex(targetYaw - yaw)];
    }

    /** 0 = ahead, 2 = right, 4 = behind, 6 = left; bands of 45° around each. */
    public static int arrowIndex(double relativeDegrees) {
        double normalized = relativeDegrees % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        return (int) Math.round(normalized / 45) % 8;
    }
}
