package net.knightsandkings.knk.core.npc.nav;

/**
 * Hears what road navigation re-checks its routes on (implementation plan Phase 3): a gate that changed state, and
 * availability that may have changed anywhere (the domain cache was refreshed, a siege lockdown started or ended).
 * The listener port between road navigation and NPC navigation. Called on the thread the change arrives on; the
 * listener hops to the main thread itself.
 */
public interface RuleChangeListener {

    /** The gate door with this id opened, closed, started moving, jammed or was destroyed. */
    void gateChanged(int doorId);

    /** A region's entry or exit rule may have changed; which one is not known. */
    void availabilityChanged();
}
