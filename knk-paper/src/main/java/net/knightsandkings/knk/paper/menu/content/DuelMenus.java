package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.view.ArenaMenuView;
import net.knightsandkings.knk.core.duel.view.DuelMenuIds;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.paper.duel.DuelMatch;
import net.knightsandkings.knk.paper.duel.DuelObserver;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * How {@code /duels}, {@code /arena} (P5's commands) and the announcement buttons open the duel menus (plan
 * Phase 6), and the repaint of open duel menus on every phase change. {@code KnKPlugin.getDuelMenus()}. Main thread.
 */
public final class DuelMenus implements DuelObserver {

    static final Set<String> KEYS = Set.of(DuelMenuIds.MENU_MATCHES, DuelMenuIds.MENU_MATCH_DETAIL,
            DuelMenuIds.MENU_ARENA, DuelMenuIds.MENU_CHALLENGE);

    private final MenuService menus;
    private final DuelMenuFeature feature;
    private final Supplier<List<KnkArena>> arenas;

    public DuelMenus(MenuService menus, DuelMenuFeature feature, Supplier<List<KnkArena>> arenas) {
        this.menus = Objects.requireNonNull(menus, "menus");
        this.feature = Objects.requireNonNull(feature, "feature");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
    }

    /** {@code /duels}: live matches of every arena. False when the menu isn't available. */
    public boolean openMatches(Player player) {
        return open(player, DuelMenuIds.MENU_MATCHES, MenuContextParams.EMPTY);
    }

    /** {@code /duels view <token>} ({@code [Details]}): one match. False when the menu isn't available. */
    public boolean openMatch(Player player, UUID token) {
        return open(player, DuelMenuIds.MENU_MATCH_DETAIL,
                MenuContextParams.of(Map.of(DuelMenuIds.CTX_TOKEN, token.toString())));
    }

    /**
     * {@code /arena [name]}: the arena menu of the arena with that name or key (any case), or with {@code name}
     * null the one the player stands in, else the first. False when the menu isn't available or no arena has that
     * name.
     */
    public boolean openArena(Player player, String name) {
        if (name == null || name.isBlank()) return open(player, DuelMenuIds.MENU_ARENA, MenuContextParams.EMPTY);
        Optional<KnkArena> arena = find(name);
        return arena.isPresent() && open(player, DuelMenuIds.MENU_ARENA,
                MenuContextParams.of(Map.of(DuelMenuIds.CTX_ARENA, String.valueOf(arena.get().id()))));
    }

    /** {@code /duels spectate <arenaId>} ({@code [Spectate]}): the spectator area's warp. */
    public void spectate(Player player, int arenaId) {
        feature.spectate(player, arenaId);
    }

    /** The arena with this name, key or id (any case). */
    public Optional<KnkArena> find(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        List<KnkArena> list = arenas.get();
        if (list == null) return Optional.empty();
        return list.stream().filter(a -> wanted.equals(String.valueOf(a.id()))
                || (a.key() != null && a.key().toLowerCase(Locale.ROOT).equals(wanted))
                || ArenaMenuView.displayName(a).toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    @Override
    public void phaseChanged(DuelMatch match, DuelPhase from, DuelPhase to) {
        refresh();
    }

    @Override
    public void settled(DuelMatch match, net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement settlement) {
        refresh();
    }

    @Override
    public void released(DuelMatch match) {
        refresh();
    }

    private void refresh() {
        menus.refreshOpenMenus(context -> KEYS.contains(context.menuKey()));
    }

    private boolean open(Player player, String key, MenuContextParams context) {
        if (!menus.isMenuAvailable(key)) return false;
        menus.openMenu(player, key, context);
        return true;
    }
}
