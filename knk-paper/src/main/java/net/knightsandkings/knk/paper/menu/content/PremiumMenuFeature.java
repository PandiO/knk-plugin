package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import org.bukkit.entity.Player;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Content port CP5 ({@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §7): {@code premium.tiers},
 * read-only. Registers row source {@code premium.tiers} → {@link PremiumTierRow} (premium-tier
 * permission groups by weight, {@link PermissionGroupsDataAccess}; each fetch also reads the viewer
 * fresh) and root {@code premium} → {@link PremiumView} (the viewer's tier + expiry).
 */
public final class PremiumMenuFeature implements MenuFeature {

    public static final String MENU_KEY = "premium.tiers";
    public static final String ROOT = "premium";
    public static final String ROWS_SOURCE = "premium.tiers";

    private static final Logger LOGGER = Logger.getLogger(PremiumMenuFeature.class.getName());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final PermissionGroupsDataAccess groups;
    private final FreshViewers viewers;

    public PremiumMenuFeature(PermissionGroupsDataAccess groups, UsersQueryApi usersQueryApi, UserCache userCache) {
        this.groups = groups;
        this.viewers = new FreshViewers(usersQueryApi, userCache);
    }

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.variables().register(ROOT, PremiumView.class, (player, ctx) -> premiumFor(player));
        registries.contentSources().registerRows(ROWS_SOURCE, PremiumTierRow.class, (context, params, query) -> fetchRows(context));
    }

    /** Main thread, no I/O. */
    PremiumView premiumFor(Player player) {
        return new PremiumView(player != null ? viewers.current(player.getUniqueId()).orElse(null) : null);
    }

    CompletableFuture<Page<PremiumTierRow>> fetchRows(MenuContentSourceContext context) {
        Player player = context.player();
        CompletableFuture<UserSummary> viewer = player != null
                ? viewers.refresh(player.getUniqueId())
                : CompletableFuture.completedFuture(null);
        return groups.listAsync()
                .thenCombine(viewer, (list, user) -> {
                    List<PremiumTierRow> rows = PremiumTierRow.rows(list,
                            user != null ? user.premiumTierGroupId() : null, user != null ? user.premiumTierExpiresAt() : null);
                    return new Page<>(rows, rows.size(), 1, Math.max(1, rows.size()));
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "premium.tiers: failed to load permission groups", ex);
                    return new Page<>(List.of(), 0, 1, 1);
                });
    }

    /** The {@code premium} root. */
    public static final class PremiumView {
        private final UserSummary user;

        PremiumView(UserSummary user) {
            this.user = user;
        }

        public String getTierName() {
            return user != null ? user.premiumTierName() : null;
        }

        /** "&7Your tier: &6Noble&7, expires 2026-10-01" / "... &7(permanent)" / "&7No premium tier". */
        public String getTierLine() {
            if (user == null) {
                return "&cYour account isn't loaded yet";
            }
            if (user.premiumTierName() == null) {
                return "&7No premium tier";
            }
            String line = "&7Your tier: &6" + user.premiumTierName();
            return user.premiumTierExpiresAt() != null
                    ? line + "&7, expires " + DATE.format(user.premiumTierExpiresAt())
                    : line + " &7(permanent)";
        }
    }
}
