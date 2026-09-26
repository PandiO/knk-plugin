package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Content port CP5: {@code premium.tiers} rows, the {@code premium} root and the seed. */
class PremiumMenuFeatureTest {

    private static final List<PermissionGroupSummary> GROUPS = List.of(
            new PermissionGroupSummary(5, "Royal", 30, true, 1.2),
            new PermissionGroupSummary(1, "Default", 0, false, 1.0),
            new PermissionGroupSummary(4, "Noble", 20, true, 1.1),
            new PermissionGroupSummary(6, "Dragon Blood", 40, true, 1.5),
            new PermissionGroupSummary(9, "Staff", 100, false, 1.0));

    @Test
    void rowsArePremiumGroupsByWeightWithTheViewersTierHighlighted() {
        OffsetDateTime until = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        List<PremiumTierRow> rows = PremiumTierRow.rows(GROUPS, 5, until);

        assertEquals(List.of("Noble", "Royal", "Dragon Blood"), rows.stream().map(PremiumTierRow::getName).toList());
        assertEquals(List.of("IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK"), rows.stream().map(PremiumTierRow::getMaterial).toList());
        assertEquals(List.of("NORMAL", "HIGHLIGHT", "NORMAL"), rows.stream().map(PremiumTierRow::getDisplayMode).toList());
        assertEquals("x1.2", rows.get(1).getSalaryMultiplierText());
        assertTrue(rows.get(1).getLoreLines().containsAll(List.of("&aYour current tier", "&7Until &f2026-10-01")));
        assertEquals(List.of("&7Salary multiplier: &fx1.1"), rows.get(0).getLoreLines());
    }

    @Test
    void permanentTierAndMultiplierFormatting() {
        assertTrue(PremiumTierRow.rows(GROUPS, 4, null).get(0).getLoreLines().contains("&7Permanent"));
        assertEquals("x1", PremiumTierRow.multiplierText(1.0));
        assertEquals("x1.25", PremiumTierRow.multiplierText(1.25));
    }

    @Test
    void rootShowsTheViewersTierAfterAFetch() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        UsersQueryApi users = mock(UsersQueryApi.class);
        when(users.getByUuid(uuid)).thenReturn(CompletableFuture.completedFuture(ProfileMenuFeatureTest.user(uuid, 0, null,
                null, 0, "Royal", OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC))));
        PermissionGroupsDataAccess groups = new PermissionGroupsDataAccess(() -> CompletableFuture.completedFuture(GROUPS),
                Duration.ofMinutes(2), Clock.systemUTC());
        PremiumMenuFeature feature = new PremiumMenuFeature(groups, users, new UserCache(Duration.ofMinutes(5)));

        assertEquals("&cYour account isn't loaded yet", feature.premiumFor(player).getTierLine());
        List<PremiumTierRow> rows = feature.fetchRows(new MenuContentSourceContext(player, null, MenuContextParams.EMPTY))
                .join().items();

        assertEquals(3, rows.size());
        assertEquals("&7Your tier: &6Royal&7, expires 2026-10-01", feature.premiumFor(player).getTierLine());
        assertEquals("&7No premium tier", new PremiumMenuFeature.PremiumView(
                ProfileMenuFeatureTest.user(uuid, 0, null, null, 0, null, null)).getTierLine());
    }

    @Test
    void premiumSeedValidatesAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(PremiumMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
    }
}
