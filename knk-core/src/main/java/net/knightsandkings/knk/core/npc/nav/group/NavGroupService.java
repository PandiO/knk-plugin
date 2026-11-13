package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;

/**
 * The groups of NPCs that plan once (implementation plan Phase 4; BEHAVIOR_MODEL NAV-05): creates {@link NavGroup}s
 * over movers that are already under {@link NpcNavigationService}, ticks them and tells them when the rules
 * changed. It sits on top of the navigation service's public surface: a group's leader is an ordinary order there,
 * a follower's own leg back is another, and nothing else of a group is known to the service.
 *
 * <p>Main thread only. {@link #tick} runs once per server tick, before the navigation service's own tick.
 */
public final class NavGroupService {

    /** What groups need from outside; {@code log} gets one line per fold, wait and own leg, with the position. */
    public record Ports(NpcNavigationService navigation, NavWorld world, AccessRules rules, NpcNavigationMetrics metrics,
                        LongSupplier nanoTime, Consumer<String> log) {
        public Ports {
            Objects.requireNonNull(navigation, "navigation");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(metrics, "metrics");
            rules = rules == null ? AccessRules.OPEN : rules;
            nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
            log = log == null ? line -> { } : log;
        }
    }

    private final Ports ports;
    private final Map<Integer, NavGroup> groups = new LinkedHashMap<>();
    private int nextId = 1;
    private long tick;
    private long lastTickNanos;

    public NavGroupService(Ports ports) {
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    /**
     * Makes a group of movers that are registered with the navigation service: {@code leader} gets the group's
     * orders, {@code followers} walk in the formation's slots in list order. The members share one access profile
     * (the one they are registered with); a member's own running order is left alone until the group is sent
     * somewhere.
     */
    public NavGroup create(NpcMover leader, List<NpcMover> followers, NpcAccessProfile profile, GroupSettings settings) {
        Objects.requireNonNull(followers, "followers");
        NavGroup group = new NavGroup(nextId++, ports, leader, List.copyOf(followers), profile, settings);
        groups.put(group.id(), group);
        return group;
    }

    /** Ends a group (its order is cancelled); its members stay under navigation. */
    public void disband(NavGroup group) {
        if (group != null && groups.remove(group.id()) != null) {
            group.disband();
        }
    }

    public int count() {
        return groups.size();
    }

    public List<NavGroup> groups() {
        return List.copyOf(groups.values());
    }

    /**
     * A gate changed or a region's rule may have: followers stopped by a rule or by a failed leg of their own try
     * again at once. The leaders hear of it through the navigation service, one replan per group at most.
     */
    public void onRulesChanged() {
        for (NavGroup group : groups.values()) {
            group.rulesChanged();
        }
    }

    /** Once per server tick. */
    public void tick() {
        long start = ports.nanoTime().getAsLong();
        tick++;
        for (NavGroup group : new ArrayList<>(groups.values())) {
            group.tick(tick);
        }
        lastTickNanos = ports.nanoTime().getAsLong() - start;
    }

    /** Main-thread time the last tick took. */
    public long lastTickNanos() {
        return lastTickNanos;
    }
}
