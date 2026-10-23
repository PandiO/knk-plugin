package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuGrowth;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KNG-231: a page change asks the renderer to fail when a content source fails, so the previous
 * page can be put back instead of an empty one being shown. Bukkit types are mocked.
 */
class MenuRendererContentFailureTest {

    @Test
    void aFailingContentSourceFailsTheRenderWhenAsked() {
        MenuContentSourceRegistry<MenuContentSourceContext> sources = new MenuContentSourceRegistry<>();
        sources.registerRows("broken", Object.class, (context, params, query) ->
                CompletableFuture.failedFuture(new RuntimeException("API down")));
        MenuRenderer renderer = new MenuRenderer(mock(MinecraftMaterialRefsDataAccess.class), sources,
                new ConditionRegistry<>(), new MenuVariableProviderRegistry<>(), Runnable::run);

        RuntimeMenuSection section = mock(RuntimeMenuSection.class);
        when(section.id()).thenReturn(1);
        when(section.name()).thenReturn("Items");
        when(section.hasContentSource()).thenReturn(true);
        when(section.contentSourceId()).thenReturn("broken");
        when(section.contentSourceParams()).thenReturn(Map.of());
        when(section.isVisibleTo(any())).thenReturn(true);
        when(section.computeSlotPool(54)).thenReturn(new RuntimeMenuSection.SlotPool(Map.of(), List.of(0, 1, 2)));
        RuntimeMenu menu = new RuntimeMenu("items.catalog", "Catalogue", 6, MenuGrowth.STATIC, null, List.of(section), 0);
        Player player = mock(Player.class);
        MenuSession session = new net.knightsandkings.knk.core.menu.MenuSessionRegistry().open(UUID.randomUUID());

        CompletableFuture<MenuRenderResult> render = renderer.render(menu, session, player, Map.of(), null, true);

        CompletionException failure = assertThrows(CompletionException.class, render::join);
        assertInstanceOf(MenuRenderer.ContentFetchFailedException.class, failure.getCause());
    }
}
