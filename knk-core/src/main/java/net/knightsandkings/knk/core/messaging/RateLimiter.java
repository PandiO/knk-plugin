package net.knightsandkings.knk.core.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private-message spam limit per sender (docs/specs/private-messages/DESIGN.md §3.3.6): at most
 * {@code maxMessages} per sliding {@code window}, and the same text to the same recipient again
 * within {@code duplicateWindow} counts as spam. Only allowed messages are recorded, so a refused
 * attempt doesn't extend the wait.
 */
public final class RateLimiter {

    private record Sent(Instant at, ParticipantId recipient, String normalizedText) {
    }

    private final int maxMessages;
    private final Duration window;
    private final Duration duplicateWindow;
    private final Clock clock;
    private final Map<ParticipantId, Deque<Sent>> history = new ConcurrentHashMap<>();

    public RateLimiter(int maxMessages, Duration window, Duration duplicateWindow, Clock clock) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be at least 1 (got " + maxMessages + ")");
        }
        this.maxMessages = maxMessages;
        this.window = Objects.requireNonNull(window, "window must not be null");
        this.duplicateWindow = Objects.requireNonNull(duplicateWindow, "duplicateWindow must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Checks, and on success records, one message.
     *
     * @param bypass true for senders exempt from the limit (nothing is recorded for them)
     * @return empty if the message may be sent; otherwise how long the sender must wait
     */
    public Optional<Duration> tryAcquire(ParticipantId sender, ParticipantId recipient, String text, boolean bypass) {
        if (bypass) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        String normalized = normalize(text);
        Deque<Sent> sent = history.computeIfAbsent(sender, key -> new ArrayDeque<>());
        synchronized (sent) {
            prune(sent, now);

            Duration wait = Duration.ZERO;
            for (Sent previous : sent) {
                if (previous.recipient().equals(recipient) && previous.normalizedText().equals(normalized)) {
                    Duration remaining = Duration.between(now, previous.at().plus(duplicateWindow));
                    if (remaining.compareTo(wait) > 0) {
                        wait = remaining;
                    }
                }
            }

            int inWindow = 0;
            Instant oldestInWindow = null;
            Instant windowStart = now.minus(window);
            for (Sent previous : sent) {
                if (previous.at().isAfter(windowStart)) {
                    inWindow++;
                    if (oldestInWindow == null || previous.at().isBefore(oldestInWindow)) {
                        oldestInWindow = previous.at();
                    }
                }
            }
            if (inWindow >= maxMessages && oldestInWindow != null) {
                Duration remaining = Duration.between(now, oldestInWindow.plus(window));
                if (remaining.compareTo(wait) > 0) {
                    wait = remaining;
                }
            }

            if (wait.isPositive()) {
                return Optional.of(wait);
            }
            sent.addLast(new Sent(now, recipient, normalized));
            return Optional.empty();
        }
    }

    /** Drops the sender's history (they quit). */
    public void forget(ParticipantId sender) {
        history.remove(sender);
    }

    /** Whole seconds to show a player, rounded up and at least 1. */
    public static long waitSeconds(Duration wait) {
        long millis = wait.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private void prune(Deque<Sent> sent, Instant now) {
        Duration keep = window.compareTo(duplicateWindow) >= 0 ? window : duplicateWindow;
        Instant cutoff = now.minus(keep);
        Iterator<Sent> it = sent.iterator();
        while (it.hasNext()) {
            if (!it.next().at().isAfter(cutoff)) {
                it.remove();
            }
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }
}
