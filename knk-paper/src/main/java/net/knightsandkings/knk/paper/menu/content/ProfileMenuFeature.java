package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Content port CP3 ({@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §5): {@code profile.main}.
 * Registers
 * <ul>
 *   <li>row source {@code titles.brackets} → {@link TitleRow}: every title bracket
 *       ({@link TitleBracketsDataAccess}, cached) as seen by the viewer. Each fetch also reads the
 *       viewer fresh ({@code UsersQueryApi.getByUuid}) - it runs before bindings resolve (engine
 *       Phase 9 §9.0), so the {@code profile} root below shows the same numbers as the rows
 *       ({@link FreshViewers});</li>
 *   <li>root {@code profile} → {@link ProfileView}: that fresh read, else the cached user
 *       (stale entry, never I/O - providers run on the main thread).</li>
 * </ul>
 */
public final class ProfileMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "profile.main";
    public static final String ROOT = "profile";
    public static final String ROWS_SOURCE = "titles.brackets";

    private static final Logger LOGGER = Logger.getLogger(ProfileMenuFeature.class.getName());

    private final FreshViewers viewers;
    private final TitleBracketsDataAccess titleBrackets;

    public ProfileMenuFeature(UsersQueryApi usersQueryApi, UserCache userCache, TitleBracketsDataAccess titleBrackets) {
        this.viewers = new FreshViewers(usersQueryApi, userCache);
        this.titleBrackets = titleBrackets;
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, ProfileView.class, (player, ctx) -> profileFor(player));
        registries.contentSources().registerRows(ROWS_SOURCE, TitleRow.class, (context, params, query) -> fetchRows(context));
    }

    /** Main thread, no I/O. */
    ProfileView profileFor(Player player) {
        if (player == null) {
            return ProfileView.unavailable();
        }
        return viewers.current(player.getUniqueId())
                .map(user -> new ProfileView(user, titleBrackets.cachedOrEmpty()))
                .orElseGet(ProfileView::unavailable);
    }

    CompletableFuture<Page<TitleRow>> fetchRows(MenuContentSourceContext context) {
        Player player = context.player();
        if (player == null) {
            return CompletableFuture.completedFuture(page(List.of()));
        }
        return titleBrackets.listAsync()
                .thenCombine(viewers.refresh(player.getUniqueId()), (list, viewer) -> {
                    if (viewer == null) {
                        return page(TitleRow.rows(list, null, null, 0));
                    }
                    TitleProgress progress = TitleProgress.of(list, viewer.titleBracketId(), viewer.experiencePoints());
                    return page(TitleRow.rows(list, progress, viewer.gender(), viewer.experiencePoints()));
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "titles.brackets: failed to load title brackets", ex);
                    return page(List.of());
                });
    }

    private static <T> Page<T> page(List<T> rows) {
        return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
    }
}
