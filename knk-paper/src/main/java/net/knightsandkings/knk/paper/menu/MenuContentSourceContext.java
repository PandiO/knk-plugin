package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import org.bukkit.entity.Player;

/**
 * IMPLEMENTATION_PLAN.md Phase 8: the concrete execution context knk-paper
 * instantiates {@code MenuContentSourceRegistry<MenuContentSourceContext>}
 * with - mirrors the {@code MenuVariableContext.DECLARED_TYPES}/{@code liveValues}
 * and {@code MenuActionContext} split Phases 3/6 already established: the
 * registry mechanism itself stays Bukkit-free in knk-core, while this record
 * (knk-paper only) carries the live {@link Player} a content source might
 * eventually need (e.g. a future "items this player owns" source) even
 * though the one real source shipped with this phase (backed by
 * {@code ItemBlueprintsDataAccess}) doesn't use it - kept for the same
 * reason {@code MenuActionContext} carries a full {@code Player} rather than
 * just a player id.
 * <p>
 * {@code menuContext} (InventoryMenu Phase 9, E1): the ctx params the menu was
 * opened with. Row sources additionally receive the section's interpolated
 * {@code ContentSourceParamsJson} as their own argument (see
 * {@code MenuRowSource}). Content sources are called on the main thread.
 */
public record MenuContentSourceContext(Player player, MenuSession session, MenuContextParams menuContext) {
}
