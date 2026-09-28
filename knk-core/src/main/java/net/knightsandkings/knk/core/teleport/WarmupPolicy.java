package net.knightsandkings.knk.core.teleport;

import java.util.Objects;

/**
 * How long a teleport waits before it happens (docs/specs/teleport/DESIGN.md §3.4 step 2, §4 D2):
 * staff teleports and holders of {@code knk.teleport.bypass.warmup} go instantly; holders of
 * {@code knk.teleport.warmup.short} wait the short warmup (v1: 3 s for any donator rank); everyone
 * else waits the normal one (v1: 5 s). The short warmup never makes a teleport slower than the
 * normal one, even if the config sets it higher.
 */
public final class WarmupPolicy {

    private final TeleportSettings settings;

    public WarmupPolicy(TeleportSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    public int warmupSeconds(TeleportKind kind, boolean shortWarmup, boolean bypassWarmup) {
        if (kind.isStaff() || bypassWarmup) {
            return 0;
        }
        if (shortWarmup) {
            return Math.min(settings.warmupShortSeconds(), settings.warmupSeconds());
        }
        return settings.warmupSeconds();
    }
}
