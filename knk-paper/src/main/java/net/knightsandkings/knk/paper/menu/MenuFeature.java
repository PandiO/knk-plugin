package net.knightsandkings.knk.paper.menu;

/**
 * InventoryMenu Phase 9 (E2): how a feature (Siege, Kits, the
 * {@code example.domain} demo, …) plugs into the menu engine - one call that
 * registers its variable roots, content sources, actions and conditions.
 * <p>
 * {@code KnKPlugin} calls {@link #registerMenuHandlers} for every feature in
 * its menu-feature list <em>before</em> {@link MenuDefinitionValidationRunner}
 * runs; the runner then locks every registry, so registering from anywhere
 * later (a delayed task, a lazily-initialised service) throws
 * {@link IllegalStateException} at that point instead of leaving menus that
 * were validated without the registration. To add a feature, implement this
 * and add it to that list.
 * <p>
 * Everything registered here is invoked on the server main thread
 * (IMPLEMENTATION_PLAN.md Phase 9 §9.0): providers and content sources may
 * read main-thread-owned state directly.
 */
@FunctionalInterface
public interface MenuFeature {
    void registerMenuHandlers(MenuFeatureRegistries registries);
}
