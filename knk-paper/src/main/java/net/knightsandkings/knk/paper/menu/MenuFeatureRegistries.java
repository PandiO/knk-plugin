package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import org.bukkit.entity.Player;

/**
 * InventoryMenu Phase 9 (E2): the four menu registries a {@link MenuFeature}
 * registers into. {@link #lockAll()} is called by
 * {@link MenuDefinitionValidationRunner} before it validates anything.
 */
public record MenuFeatureRegistries(
        ActionRegistry<MenuActionContext> actions,
        ConditionRegistry<MenuActionContext> conditions,
        MenuContentSourceRegistry<MenuContentSourceContext> contentSources,
        MenuVariableProviderRegistry<Player> variables
) {

    public void lockAll() {
        actions.lock();
        conditions.lock();
        contentSources.lock();
        variables.lock();
    }
}
