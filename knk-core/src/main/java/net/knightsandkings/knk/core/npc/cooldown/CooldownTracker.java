package net.knightsandkings.knk.core.npc.cooldown;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * "Ready at tick" bookkeeping per actor and channel ({@link #MELEE}, {@link #RANGED}, or any
 * consumer string). Time is injected: every call takes the current tick, so the tracker has no clock
 * and tests drive it tick by tick. A Paper adapter's attack goal calls {@link #tryFire} with the
 * resolved cooldown; the first call for an actor is always ready.
 */
public final class CooldownTracker {
    public static final String MELEE = "melee";
    public static final String RANGED = "ranged";

    private final Map<UUID, Map<String, Long>> readyAt = new ConcurrentHashMap<>();
    private final CooldownJitter jitter;
    private final RandomGenerator random;

    /** No jitter. */
    public CooldownTracker() {
        this(CooldownJitter.OFF, RandomGenerator.getDefault());
    }

    public CooldownTracker(CooldownJitter jitter, RandomGenerator random) {
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.random = Objects.requireNonNull(random, "random");
    }

    public CooldownJitter jitter() {
        return jitter;
    }

    /** Whether the actor's channel may fire at {@code nowTick}. */
    public boolean isReady(UUID actor, String channel, long nowTick) {
        Map<String, Long> channels = readyAt.get(Objects.requireNonNull(actor, "actor"));
        if (channels == null) return true;
        Long at = channels.get(Objects.requireNonNull(channel, "channel"));
        return at == null || nowTick >= at;
    }

    /** Ticks until the channel is ready; 0 when ready now. */
    public long remaining(UUID actor, String channel, long nowTick) {
        OptionalLong at = readyAt(actor, channel);
        return at.isEmpty() ? 0 : Math.max(0, at.getAsLong() - nowTick);
    }

    public OptionalLong readyAt(UUID actor, String channel) {
        Map<String, Long> channels = readyAt.get(Objects.requireNonNull(actor, "actor"));
        if (channels == null) return OptionalLong.empty();
        Long at = channels.get(Objects.requireNonNull(channel, "channel"));
        return at == null ? OptionalLong.empty() : OptionalLong.of(at);
    }

    /**
     * Fires if ready: arms the channel for {@code cooldownTicks} (jitter applied) from {@code nowTick}
     * and returns {@code true}; otherwise returns {@code false} and changes nothing.
     */
    public boolean tryFire(UUID actor, String channel, long nowTick, int cooldownTicks) {
        if (!isReady(actor, channel, nowTick)) return false;
        arm(actor, channel, nowTick, cooldownTicks);
        return true;
    }

    /**
     * Arms the channel unconditionally: ready again at {@code nowTick + cooldown} where cooldown is at
     * least 1 tick plus jitter. Returns the ticks actually applied.
     */
    public int arm(UUID actor, String channel, long nowTick, int cooldownTicks) {
        int applied = jitter.apply(Math.max(1, cooldownTicks), random);
        readyAt.computeIfAbsent(Objects.requireNonNull(actor, "actor"), a -> new ConcurrentHashMap<>())
            .put(Objects.requireNonNull(channel, "channel"), nowTick + applied);
        return applied;
    }

    /** Makes the channel ready now (a consumer resetting after a target switch, for example). */
    public void reset(UUID actor, String channel) {
        Map<String, Long> channels = readyAt.get(Objects.requireNonNull(actor, "actor"));
        if (channels != null) channels.remove(Objects.requireNonNull(channel, "channel"));
    }

    /** Drops the actor's state (despawn, death). */
    public void forget(UUID actor) {
        readyAt.remove(Objects.requireNonNull(actor, "actor"));
    }

    public void clear() {
        readyAt.clear();
    }

    public int trackedActors() {
        return readyAt.size();
    }
}
