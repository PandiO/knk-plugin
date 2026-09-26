package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Content port CP1: the {@code menu-available} engine condition and the {@code main} hub seed. */
class HubMenuFeatureTest {

    private static MenuService realMenuService() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        return new MenuService(plugin, null, null, null, null, null, null);
    }

    private static ConditionOutcome menuAvailable(MenuService service, String key) {
        MenuActionContext context = new MenuActionContext(null, null, Map.of(), service, null, null, null, null);
        return ContentFeatures.all().conditions().test("menu-available", context, Map.of("key", key));
    }

    @Test
    void menuAvailableAllowsOnlyAValidatedUnblockedMenu() {
        MenuService service = realMenuService();
        service.markValidated("kits.overview");
        service.markValidated("broken.later");
        service.blockMenu("broken.later", "failed");
        service.blockMenu("never.valid", "failed");

        assertTrue(menuAvailable(service, "kits.overview").allowed());
        assertFalse(menuAvailable(service, "siege.overview").allowed(), "missing template");
        assertFalse(menuAvailable(service, "never.valid").allowed(), "blocked by validation");
        assertFalse(menuAvailable(service, "broken.later").allowed(), "blocked after being marked valid");
        assertEquals("That menu is unavailable right now.", menuAvailable(service, "siege.overview").denialMessage());
    }

    @Test
    void menuAvailableRequiresAKey() {
        MenuService service = realMenuService();
        assertThrows(RuntimeException.class, () -> menuAvailable(service, " "));
    }

    @Test
    void hubSeedValidatesAgainstTheRegisteredFeatures() {
        MenuFeatureRegistries registries = ContentFeatures.all();
        RuntimeMenu hub = ContentSeedFixture.assemble(HubMenuFeature.HUB_KEY);

        assertEquals(27, hub.height() * 9);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(hub, registries));
    }

    @Test
    void hubSeedFailsValidationWithoutTheMenuAvailableCondition() {
        MenuFeatureRegistries withoutConditions = new MenuFeatureRegistries(ContentFeatures.all().actions(),
                new net.knightsandkings.knk.core.menu.ConditionRegistry<>(), ContentFeatures.all().contentSources(),
                ContentFeatures.all().variables());
        RuntimeMenu hub = ContentSeedFixture.assemble(HubMenuFeature.HUB_KEY);

        assertThrows(RuntimeException.class, () -> ContentSeedFixture.validate(hub, withoutConditions));
    }
}
