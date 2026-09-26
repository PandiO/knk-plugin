package net.knightsandkings.knk.core.messaging;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link RateLimiter} as a gate. The console and holders of {@code knk.msg.bypass.ratelimit} are
 * exempt.
 */
public final class RateLimitGate implements PrivateMessageGate {

    private final RateLimiter limiter;

    public RateLimitGate(RateLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter must not be null");
    }

    @Override
    public Optional<Denial> check(SendAttempt attempt) {
        boolean bypass = attempt.sender().isConsole()
                || attempt.senderNodes().contains(PrivateMessageNodes.BYPASS_RATE_LIMIT);
        Optional<Duration> wait = limiter.tryAcquire(attempt.sender(), attempt.recipient(), attempt.text(), bypass);
        return wait.map(w -> new Denial(Reason.RATE_LIMITED,
                "Slow down — you can send another message in " + RateLimiter.waitSeconds(w) + "s."));
    }
}
