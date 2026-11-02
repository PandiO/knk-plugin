package net.knightsandkings.knk.core.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One refused-join notice per player per cooldown (PandiO/knk-workspace#159), so a client retrying in a loop, or one
 * refusal seen by both the pre-login and the login check, doesn't flood staff chat. Thread-safe.
 */
public final class JoinRefusalThrottle {

    private final Duration cooldown;
    private final Clock clock;
    private final Map<UUID, Instant> lastNotice = new ConcurrentHashMap<>();

    public JoinRefusalThrottle(Duration cooldown, Clock clock) {
        this.cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether a notice for {@code uuid} may go out now; if so, the cooldown starts. */
    public boolean tryAcquire(UUID uuid) {
        Instant now = clock.instant();
        boolean[] acquired = {false};
        lastNotice.compute(uuid, (key, last) -> {
            if (last == null || !now.isBefore(last.plus(cooldown))) {
                acquired[0] = true;
                return now;
            }
            return last;
        });
        if (lastNotice.size() > 1024) {
            lastNotice.values().removeIf(at -> !now.isBefore(at.plus(cooldown)));
        }
        return acquired[0];
    }
}
