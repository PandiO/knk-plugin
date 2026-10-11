package net.knightsandkings.knk.core.roads.route;

import java.util.Objects;

/**
 * What an {@link AccessPolicy} says about one edge for one player right now (DESIGN §6.7):
 * usable, usable with a hint (a pass-through gate: "right-click the gate to pass"), or blocked
 * with a reason. {@code cause} names the element behind a hint or a block so the explainer
 * ("No open route to X — the West Gate is closed") and the live re-route ("The West Gate closed —
 * recalculating") can refer to it; {@code message} is the player-facing wording.
 *
 * @param kind    open, pass-through or blocked
 * @param message the hint or reason (empty for {@link Kind#OPEN})
 * @param cause   the gate, domain or flag behind it ({@code null} for OPEN)
 */
public record EdgeVerdict(Kind kind, String message, Cause cause) {

    public enum Kind {
        OPEN,
        /** Passable for this player, but they must do something (right-click the gate). */
        PASS_THROUGH,
        BLOCKED
    }

    /** Which kind of element decided the verdict. */
    public enum CauseType {
        GATE,
        DOMAIN,
        FLAG
    }

    /**
     * The element behind a verdict.
     *
     * @param type gate door, domain or static flag
     * @param id   the door id, the domain id or the flag's api name (as text)
     * @param name what to call it to the player ("the West Gate", "Kardenna Castle", "closed road")
     */
    public record Cause(CauseType type, String id, String name) {
        public Cause {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }

        public static Cause gate(int doorId, String name) {
            return new Cause(CauseType.GATE, Integer.toString(doorId), name);
        }

        public static Cause domain(int domainId, String name) {
            return new Cause(CauseType.DOMAIN, Integer.toString(domainId), name);
        }

        public static Cause flag(String flagName, String name) {
            return new Cause(CauseType.FLAG, flagName, name);
        }
    }

    private static final EdgeVerdict OPEN = new EdgeVerdict(Kind.OPEN, "", null);

    public EdgeVerdict {
        Objects.requireNonNull(kind, "kind");
        message = message == null ? "" : message;
        if (kind != Kind.OPEN && cause == null) {
            throw new IllegalArgumentException(kind + " needs a cause");
        }
    }

    public static EdgeVerdict open() {
        return OPEN;
    }

    public static EdgeVerdict passThrough(String hint, Cause cause) {
        return new EdgeVerdict(Kind.PASS_THROUGH, hint, cause);
    }

    public static EdgeVerdict blocked(String reason, Cause cause) {
        return new EdgeVerdict(Kind.BLOCKED, reason, cause);
    }

    public boolean isOpen() {
        return kind == Kind.OPEN;
    }

    public boolean isBlocked() {
        return kind == Kind.BLOCKED;
    }

    public boolean isPassThrough() {
        return kind == Kind.PASS_THROUGH;
    }

    /** Whether the edge may be part of a route (OPEN or PASS_THROUGH). */
    public boolean isUsable() {
        return kind != Kind.BLOCKED;
    }

    /** The stricter of two verdicts: BLOCKED beats PASS_THROUGH beats OPEN; the first wins ties. */
    public static EdgeVerdict stricter(EdgeVerdict a, EdgeVerdict b) {
        return b.kind.ordinal() > a.kind.ordinal() ? b : a;
    }
}
