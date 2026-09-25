package net.knightsandkings.knk.paper.menu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-side counterpart to {@link net.knightsandkings.knk.core.menu.MenuSessionRegistry}:
 * owns the live {@link OpenMenuContext} (Inventory + click-routing snapshot)
 * for every player currently looking at a KnK menu. Cleared on
 * {@code PlayerQuitEvent} alongside the core session registry - see
 * {@code MenuQuitListener}.
 */
public final class OpenMenuContextRegistry {

    private final ConcurrentHashMap<UUID, OpenMenuContext> contexts = new ConcurrentHashMap<>();

    public void register(UUID playerId, OpenMenuContext context) {
        contexts.put(playerId, context);
    }

    public Optional<OpenMenuContext> get(UUID playerId) {
        return Optional.ofNullable(contexts.get(playerId));
    }

    public void close(UUID playerId) {
        contexts.remove(playerId);
    }

    /** InventoryMenu Phase 9 (E4): snapshot of every currently-open menu, for {@code MenuService.refreshOpenMenus}. */
    public List<OpenMenuContext> all() {
        return List.copyOf(contexts.values());
    }
}
