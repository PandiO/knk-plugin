package net.knightsandkings.knk.paper.regions;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Block breaking in WorldGuard's global region (QOL hotfix, 2026-10-25): where no region but {@code __global__}
 * applies - outside every domain, arena and other region - a default player may not break blocks. Holders of
 * {@code knk.region.bypass} and of WorldGuard's region bypass (ops) may. Inside a region nothing changes: the
 * domain's own rules and WorldGuard's flags decide there, as before.
 * <p>
 * This is a plugin rule, not a {@code block-break deny} flag on {@code __global__}: WorldGuard's flag would also
 * bind the members of every region (membership only lifts {@code build}), pistons and TNT, and would have to be
 * written into each world's region file. Switch: {@code regions.global.deny-block-break} (default on).
 * <p>
 * The refused player sees a short action-bar notice, at most once per {@value #NOTICE_COOLDOWN_MILLIS} ms, so
 * holding the mouse button does not flood them.
 */
public final class GlobalRegionBlockBreakListener implements Listener {

    /** {@code config.yml} switch; absent means on. */
    public static final String CONFIG_PATH = "regions.global.deny-block-break";
    static final long NOTICE_COOLDOWN_MILLIS = 2000L;
    static final String NOTICE = "You can't break blocks here.";

    private final BooleanSupplier enabled;
    private final Function<Location, Set<String>> regionsAt;
    private final Predicate<Player> bypass;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastNoticeAt = new ConcurrentHashMap<>();

    /**
     * @param enabled   the config switch, read on every break so a config reload takes effect
     * @param regionsAt the WorldGuard region ids at a location without {@code __global__} ({@link RegionIds#at})
     * @param bypass    who may break blocks anyway ({@code knk.region.bypass}, WorldGuard's region bypass)
     */
    public GlobalRegionBlockBreakListener(BooleanSupplier enabled, Function<Location, Set<String>> regionsAt,
                                          Predicate<Player> bypass) {
        this(enabled, regionsAt, bypass, System::currentTimeMillis);
    }

    GlobalRegionBlockBreakListener(BooleanSupplier enabled, Function<Location, Set<String>> regionsAt,
                                   Predicate<Player> bypass, LongSupplier clock) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.regionsAt = Objects.requireNonNull(regionsAt, "regionsAt");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * HIGH like the hub's building rules: after the LOWEST join-loading guard, before the gate listener at
     * HIGHEST (gate doors sit in their structure's region, so this never reaches them).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        Player player = event.getPlayer();
        Set<String> regions = regionsAt.apply(event.getBlock().getLocation());
        if (regions != null && !regions.isEmpty()) {
            return; // a domain or other region: its own rules apply
        }
        if (bypass.test(player)) {
            return;
        }
        event.setCancelled(true);
        notify(player);
    }

    private void notify(Player player) {
        long now = clock.getAsLong();
        Long last = lastNoticeAt.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        lastNoticeAt.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text(NOTICE, NamedTextColor.RED));
    }
}
