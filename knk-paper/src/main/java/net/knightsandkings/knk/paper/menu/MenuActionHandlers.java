package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.MenuActionException;

import java.util.Map;

/**
 * Concrete {@code ActionRegistry} handlers (IMPLEMENTATION_PLAN.md Phase 6,
 * open question 1): the small, real starting library this phase actually
 * needs - {@code menu.close} and {@code menu.open} for basic navigation,
 * enough to make the existing {@code example.placeholder} seed's button
 * work and to support simple menu-to-menu navigation - not a speculative
 * library built ahead of real content. Registered once at plugin enable
 * (see {@code KnKPlugin.onEnable}), mirroring how {@link MenuVariableContext}
 * separates declared shape (knk-core-visible) from live values
 * (knk-paper-only).
 */
public final class MenuActionHandlers {

    public static final String CLOSE = "menu.close";
    public static final String OPEN = "menu.open";

    private MenuActionHandlers() {
    }

    public static void registerDefaults(ActionRegistry<MenuActionContext> registry) {
        registry.register(CLOSE, MenuActionHandlers::close);
        registry.register(OPEN, MenuActionHandlers::open);
    }

    private static void close(MenuActionContext context, Map<String, String> params) {
        context.player().closeInventory();
    }

    /** Requires a {@code key} param naming the {@code MenuTemplate.Key} to navigate to. */
    private static void open(MenuActionContext context, Map<String, String> params) {
        String key = params.get("key");
        if (key == null || key.isBlank()) {
            throw new MenuActionException("menu.open action is missing its required 'key' param");
        }
        context.menuService().openMenu(context.player(), key);
    }
}
