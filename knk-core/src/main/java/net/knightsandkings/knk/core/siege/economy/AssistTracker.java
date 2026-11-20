package net.knightsandkings.knk.core.siege.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Who helped kill whom (siege shop P13, PandiO/knk-workspace#866): a per-victim log of the damage each attacker dealt
 * in the last {@code window}. When the victim dies, every attacker other than the killer whose damage in the window
 * adds up to at least the threshold has an assist. Only the last window is kept per victim (DESIGN § 9.8); a death
 * clears the victim's log. Pure; main thread only; times are caller-supplied milliseconds.
 */
public final class AssistTracker {

    private record Hit(long atMillis, UUID attacker, double damage) { }

    private final double minDamage;
    private final long windowMillis;
    private final Map<UUID, Deque<Hit>> hitsByVictim = new HashMap<>();

    public AssistTracker(double minDamage, long windowMillis) {
        this.minDamage = Math.max(0, minDamage);
        this.windowMillis = Math.max(0, windowMillis);
    }

    public static AssistTracker of(SiegePointsSettings settings) {
        return new AssistTracker(settings.assistMinDamage(), settings.assistWindowSeconds() * 1000L);
    }

    /** {@code attacker} dealt {@code damage} to {@code victim} at {@code nowMillis}; non-positive damage and self-hits are ignored. */
    public void recordDamage(UUID victim, UUID attacker, double damage, long nowMillis) {
        Objects.requireNonNull(victim, "victim");
        if (attacker == null || attacker.equals(victim) || !(damage > 0) || Double.isInfinite(damage)) return;
        Deque<Hit> hits = hitsByVictim.computeIfAbsent(victim, v -> new ArrayDeque<>());
        prune(hits, nowMillis);
        hits.addLast(new Hit(nowMillis, attacker, damage));
    }

    /**
     * The victim died at {@code nowMillis}: the assisters in order of their first hit in the window, without the
     * killer (null for a death without a killing player). The victim's log is cleared.
     */
    public List<UUID> assistersOnDeath(UUID victim, UUID killer, long nowMillis) {
        Deque<Hit> hits = hitsByVictim.remove(victim);
        if (hits == null) return List.of();
        prune(hits, nowMillis);
        Map<UUID, Double> totals = new LinkedHashMap<>();
        for (Hit hit : hits) totals.merge(hit.attacker(), hit.damage(), Double::sum);
        List<UUID> assisters = new ArrayList<>();
        totals.forEach((attacker, total) -> {
            if (!attacker.equals(killer) && total + 1e-9 >= minDamage) assisters.add(attacker);
        });
        return List.copyOf(assisters);
    }

    /** A player left the match: their own log goes (hits they dealt others age out with the window). */
    public void forget(UUID player) {
        hitsByVictim.remove(player);
    }

    public void clear() {
        hitsByVictim.clear();
    }

    /** Victims with a log, for the memory check of DESIGN § 9.8. */
    public int trackedVictims() {
        return hitsByVictim.size();
    }

    private void prune(Deque<Hit> hits, long nowMillis) {
        Iterator<Hit> it = hits.iterator();
        while (it.hasNext()) {
            if (nowMillis - it.next().atMillis() > windowMillis) it.remove();
            else break;
        }
    }
}
