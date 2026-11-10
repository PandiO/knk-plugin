package net.knightsandkings.knk.core.chat.tokens;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Game Settings cooldown between two messages with statistics tokens (PandiO/knk-workspace#233). Thread-safe:
 * chat arrives on Paper's async chat threads. Pure apart from the clock.
 */
public final class ChatTokenCooldowns {

    private final Clock clock;
    private final Map<UUID, Instant> lastUse = new ConcurrentHashMap<>();

    public ChatTokenCooldowns(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a use and answers {@link Duration#ZERO}, or answers the time left without recording. A cooldown of 0
     * seconds or less is off.
     */
    public Duration tryUse(UUID player, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return Duration.ZERO;
        }
        Instant now = clock.instant();
        Duration[] left = {Duration.ZERO};
        lastUse.compute(player, (id, last) -> {
            if (last != null) {
                Duration remaining = Duration.between(now, last.plusSeconds(cooldownSeconds));
                if (remaining.compareTo(Duration.ZERO) > 0) {
                    left[0] = remaining;
                    return last;
                }
            }
            return now;
        });
        return left[0];
    }

    public void forget(UUID player) {
        lastUse.remove(player);
    }
}
