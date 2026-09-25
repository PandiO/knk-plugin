package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.paper.menu.MenuActionHandlers;
import net.knightsandkings.knk.paper.menu.MenuConditionHandlers;
import net.knightsandkings.knk.paper.menu.MenuContentSourceHandlers;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuVariableContext;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;

import static org.mockito.Mockito.mock;

/** Test registries: engine defaults + every content-port feature, as {@code KnKPlugin} registers them. */
final class ContentFeatures {

    private ContentFeatures() {
    }

    static MenuFeatureRegistries engineDefaults() {
        MenuFeatureRegistries registries = new MenuFeatureRegistries(new ActionRegistry<>(), new ConditionRegistry<>(),
                new MenuContentSourceRegistry<>(), new MenuVariableProviderRegistry<>());
        MenuVariableContext.registerDefaults(registries.variables());
        MenuContentSourceHandlers.registerDefaults(registries.contentSources(), mock(ItemBlueprintsDataAccess.class));
        MenuActionHandlers.registerDefaults(registries.actions());
        MenuConditionHandlers.registerDefaults(registries.conditions());
        return registries;
    }

    static MenuFeatureRegistries all() {
        MenuFeatureRegistries registries = engineDefaults();
        new HubMenuFeature().registerMenuHandlers(registries);
        return registries;
    }
}
