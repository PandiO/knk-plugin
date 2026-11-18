package net.knightsandkings.knk.core.siege;

import java.util.stream.IntStream;

/**
 * Alpha test run 1 (PandiO/knk-workspace#154): in a siege the owner alliance's members - the defenders of a
 * gate - pass through it instead of toggling it. A right-click on the closed gate opens it (animated) and
 * it closes again by itself after the hold time, so it can't be left open for the attackers. A right-click
 * while such a pass-through is open extends it; a right-click on a gate that stands open for another reason
 * (its initial state, an objective capture) still closes it. Off ({@code gates.siege.defender-pass-through:
 * false}) is the MVP rule: right-clicks toggle (DESIGN §8.3). Pure rules; the controller does the rest.
 */
public final class SiegeDefenderPassThrough {

    /** Used when neither the setting nor any door gives a hold time. */
    public static final int FALLBACK_HOLD_SECONDS = 3;

    /** What a defender's right-click on an open locked gate does. */
    public enum OpenGateClick { CLOSE, EXTEND }

    private SiegeDefenderPassThrough() {
    }

    /**
     * How long the gate stays open: the configured seconds when positive, else the longest
     * {@code PassThroughDurationSeconds} of its doors (the gate's own pass-through time), at least one second.
     */
    public static int holdSeconds(int configuredSeconds, IntStream doorSeconds) {
        if (configuredSeconds > 0) return configuredSeconds;
        return Math.max(1, doorSeconds.max().orElse(FALLBACK_HOLD_SECONDS));
    }

    /** @param passThroughOpen a defender pass-through holds this gate open now (its close is scheduled) */
    public static OpenGateClick onOpenGateClick(boolean enabled, boolean passThroughOpen) {
        return enabled && passThroughOpen ? OpenGateClick.EXTEND : OpenGateClick.CLOSE;
    }
}
