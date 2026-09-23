package net.knightsandkings.knk.paper.menu;

import org.bukkit.entity.Player;

import java.util.Map;

/**
 * The single source of truth for which root variables a {@code $x.y$}
 * getter-chain binding can reference (IMPLEMENTATION_PLAN.md Phase 3), and
 * their types - shared by {@link MenuDefinitionValidationRunner} (declared
 * types only, no live instance needed) and live rendering (
 * {@code MenuRenderer}/{@code MenuItemBukkitMapper}, which need the actual
 * value). Kept in knk-paper, not knk-core, since {@code Player} is a Bukkit
 * type and knk-core's menu package is deliberately Bukkit-free.
 * <p>
 * Add a new root variable here (and to {@link #liveValues}) to expose more of
 * the live game state to menu variable expressions; both maps must stay in
 * sync, since {@code MenuDefinitionValidator} only validates what's declared
 * here regardless of what {@link #liveValues} happens to supply at render
 * time.
 */
public final class MenuVariableContext {

    /** {@code $player.*$} resolves against the live {@link Player}. */
    public static final Map<String, Class<?>> DECLARED_TYPES = Map.of("player", Player.class);

    private MenuVariableContext() {
    }

    public static Map<String, Object> liveValues(Player player) {
        return Map.of("player", player);
    }
}
