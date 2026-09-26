package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import org.bukkit.entity.Player;

/**
 * The engine's default getter-chain root (IMPLEMENTATION_PLAN.md Phase 3):
 * {@code $player.*$} resolves against the live {@link Player}.
 * <p>
 * InventoryMenu Phase 9 (E2): Phases 3-8 kept a static {@code DECLARED_TYPES}
 * map here plus a parallel {@code liveValues(Player)} map that both had to be
 * kept in sync by hand. Roots now live in one
 * {@link MenuVariableProviderRegistry} - declared type and provider registered
 * together - and {@code player} is simply the first (default) provider. Features
 * add their own roots through a {@link MenuFeature}.
 */
public final class MenuVariableContext {

    public static final String PLAYER = "player";

    private MenuVariableContext() {
    }

    public static void registerDefaults(MenuVariableProviderRegistry<Player> registry) {
        registry.register(PLAYER, Player.class, (player, ctx) -> player);
    }
}
