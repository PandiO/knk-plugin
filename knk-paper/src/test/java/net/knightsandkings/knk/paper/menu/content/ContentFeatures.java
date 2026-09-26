package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.paper.kit.KitGrantFlow;
import net.knightsandkings.knk.paper.menu.MenuActionHandlers;
import net.knightsandkings.knk.paper.menu.MenuConditionHandlers;
import net.knightsandkings.knk.paper.menu.MenuContentSourceHandlers;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuVariableContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Test registries: engine defaults + every content-port feature, as {@code KnKPlugin} registers
 * them. Features are built over mocks unless a test passes its own instance (matched by class).
 */
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

    static List<MenuFeature> defaultFeatures() {
        List<MenuFeature> features = new ArrayList<>();
        features.add(new HubMenuFeature());
        features.add(new KitsMenuFeature(mock(KitsDataAccess.class), mock(ItemBlueprintsDataAccess.class),
                mock(MinecraftMaterialRefsDataAccess.class), mock(KitGrantFlow.class), Clock.systemUTC()));
        features.add(new ProfileMenuFeature(mock(net.knightsandkings.knk.core.ports.api.UsersQueryApi.class),
                new net.knightsandkings.knk.core.cache.UserCache(java.time.Duration.ofMinutes(5)),
                mock(net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess.class)));
        ItemBlueprintsDataAccess catalog = mock(ItemBlueprintsDataAccess.class);
        org.mockito.Mockito.when(catalog.searchAsync(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.util.concurrent.CompletableFuture<>());
        net.knightsandkings.knk.core.ports.api.CategoriesQueryApi categories =
                () -> java.util.concurrent.CompletableFuture.completedFuture(List.of());
        features.add(new ItemsCatalogMenuFeature(catalog, mock(MinecraftMaterialRefsDataAccess.class), categories, Clock.systemUTC()));
        features.add(new PremiumMenuFeature(mock(net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess.class),
                mock(net.knightsandkings.knk.core.ports.api.UsersQueryApi.class),
                new net.knightsandkings.knk.core.cache.UserCache(java.time.Duration.ofMinutes(5))));
        features.add(new UserManagerMenuFeature(mock(net.knightsandkings.knk.paper.user.UserAdminService.class),
                mock(net.knightsandkings.knk.core.ports.api.UsersQueryApi.class),
                new net.knightsandkings.knk.core.cache.UserCache(java.time.Duration.ofMinutes(5)),
                mock(net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess.class),
                mock(net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess.class), List::of));
        features.add(new DiscoveriesMenuFeature(mock(net.knightsandkings.knk.core.ports.api.DiscoveriesApi.class),
                new net.knightsandkings.knk.core.cache.UserCache(java.time.Duration.ofMinutes(5)), Clock.systemUTC()));
        features.add(new TeleportMenuFeature(() -> null));
        return features;
    }

    /** Engine defaults + every content feature; {@code overrides} replace the default of the same class. */
    static MenuFeatureRegistries all(MenuFeature... overrides) {
        MenuFeatureRegistries registries = engineDefaults();
        for (MenuFeature feature : defaultFeatures()) {
            MenuFeature chosen = feature;
            for (MenuFeature override : overrides) {
                if (override.getClass() == feature.getClass()) {
                    chosen = override;
                }
            }
            chosen.registerMenuHandlers(registries);
        }
        return registries;
    }
}
