package net.knightsandkings.knk.core.npc.modifier;

/**
 * How a modifier combines with the base. The resolver applies all additions first, then all
 * multipliers: {@code (base + Σ add) × Π multiply}. See {@code StatResolver}.
 */
public enum ModifierOp {
    /** Added to the base before any multiplier (e.g. {@code +2} damage from an enchant). */
    ADD,
    /** Multiplied into the sum of base and additions (e.g. {@code ×1.35} from a Survival round). */
    MULTIPLY
}
