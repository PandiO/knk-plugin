package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Content port CP3: {@code profile} root, {@code titles.brackets} rows, the {@code profile.main} seed. */
class ProfileMenuFeatureTest {

    static final List<TitleBracket> BRACKETS = List.of(
            new TitleBracket(1, "Peasant", "Peasant", 0, 10, 0, 0, 0),
            new TitleBracket(2, "Squire", "Maid", 100, 20, 5, 1, 0),
            new TitleBracket(3, "Knight", "Dame", 300, 40, 10, 2, 5));

    static UserSummary user(UUID uuid, int xp, Integer bracketId, String gender, int prestige, String tier,
                            OffsetDateTime tierUntil) {
        return new UserSummary(9, "Alice", uuid, null, 120, 7, xp, true, false, GatePassThroughMethod.DEFAULT,
                ActiveMode.NONE, bracketId, "server-title", prestige, tier == null ? null : 4, tier, tierUntil, false, null,
                gender);
    }

    // ===== ProfileView =====

    @Test
    void profileShowsBalancesTitleAndProgressForAFemaleViewer() {
        ProfileView view = new ProfileView(user(UUID.randomUUID(), 150, 2, "Female", 0, null, null), BRACKETS);

        assertEquals(120, view.getCoins());
        assertEquals(7, view.getGems());
        assertEquals(150, view.getExperience());
        assertEquals("Maid", view.getTitleName());
        assertEquals("&7Title: &fMaid", view.getTitleLine());
        assertEquals(List.of("&7Current: &fMaid", "&7Next: &fDame &7- &f150 XP &7to go"), view.getProgressLines());
        assertNull(view.getPrestigeLine());
        assertNull(view.getPremiumLine());
        assertFalse(view.isHighestTitle());
        assertEquals(3, view.getTitleCount());
    }

    @Test
    void highestTitleShowsPrestigeAndPremium() {
        OffsetDateTime until = OffsetDateTime.of(2026, 10, 1, 1, 30, 0, 0, ZoneOffset.ofHours(2));
        ProfileView view = new ProfileView(user(UUID.randomUUID(), 900, 3, "Male", 600, "Royal", until), BRACKETS);

        assertEquals("Knight", view.getTitleName());
        assertEquals(List.of("&7Current: &fKnight", "&aHighest title reached"), view.getProgressLines());
        assertTrue(view.isHighestTitle());
        assertEquals("&7Prestige XP: &f+600", view.getPrestigeLine());
        assertEquals("&7Premium tier: &6Royal &7(until 2026-09-30)", view.getPremiumLine());
    }

    @Test
    void progressFallsBackToExperienceWhenTheServerBracketIsUnknown() {
        ProfileView view = new ProfileView(user(UUID.randomUUID(), 120, null, null, 0, null, null), BRACKETS);

        assertEquals("Squire", view.getTitleName(), "unset gender -> male name");
    }

    @Test
    void unavailableProfileSaysSo() {
        ProfileView view = ProfileView.unavailable();
        assertFalse(view.isLoaded());
        assertEquals("&cYour account isn't loaded yet", view.getTitleLine());
        assertTrue(view.getProgressLines().isEmpty());
    }

    // ===== TitleRow =====

    @Test
    void titleRowsMarkCurrentReachedAndAheadForEachGender() {
        TitleProgress progress = TitleProgress.of(BRACKETS, 2, 150);

        List<TitleRow> male = TitleRow.rows(BRACKETS, progress, "Male", 150);
        assertEquals(List.of("NORMAL", "HIGHLIGHT", "DISABLED"), male.stream().map(TitleRow::getDisplayMode).toList());
        assertEquals(List.of("Peasant", "Squire", "Knight"), male.stream().map(TitleRow::getName).toList());
        assertTrue(male.get(1).getLoreLines().contains("&aYour current title"));
        assertTrue(male.get(1).getLoreLines().contains("&8Also known as Maid"));
        assertTrue(male.get(0).getLoreLines().contains("&aReached"));
        assertTrue(male.get(2).getLoreLines().contains("&c150 more XP needed"));
        assertTrue(male.get(2).getLoreLines().contains("&7Promotion bonus: &f10 coins, 2 gems, 5 XP"));

        List<TitleRow> female = TitleRow.rows(BRACKETS, progress, "Female", 150);
        assertEquals(List.of("Peasant", "Maid", "Dame"), female.stream().map(TitleRow::getName).toList());
        assertTrue(female.get(1).getLoreLines().contains("&8Also known as Squire"));
        assertFalse(female.get(0).getLoreLines().stream().anyMatch(l -> l.startsWith("&8Also known")), "same name both genders");
    }

    @Test
    void highestBracketIsHighlightedAndEverythingElseReached() {
        List<TitleRow> rows = TitleRow.rows(BRACKETS, TitleProgress.of(BRACKETS, 3, 5000), null, 5000);
        assertEquals(List.of("NORMAL", "NORMAL", "HIGHLIGHT"), rows.stream().map(TitleRow::getDisplayMode).toList());
    }

    // ===== feature =====

    @Test
    void fetchReadsTheViewerFreshAndTheRootShowsThatRead() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        UsersQueryApi users = mock(UsersQueryApi.class);
        when(users.getByUuid(uuid)).thenReturn(CompletableFuture.completedFuture(user(uuid, 350, 3, "Female", 50, null, null)));
        UserCache cache = new UserCache(Duration.ofMinutes(5));
        cache.put(user(uuid, 10, 1, "Female", 0, null, null));
        TitleBracketsQueryApi api = () -> CompletableFuture.completedFuture(BRACKETS);
        ProfileMenuFeature feature = new ProfileMenuFeature(users, cache, new TitleBracketsDataAccess(api, Duration.ofMinutes(10)));

        assertEquals("server-title", feature.profileFor(player).getTitleName(),
                "before any fetch: the cached user, with its server-resolved title (no brackets cached yet)");
        List<TitleRow> rows = feature.fetchRows(new MenuContentSourceContext(player, null, MenuContextParams.EMPTY)).join().items();

        assertEquals("HIGHLIGHT", rows.get(2).getDisplayMode());
        assertEquals("Dame", rows.get(2).getName());
        ProfileView view = feature.profileFor(player);
        assertEquals(350, view.getExperience());
        assertEquals("Dame", view.getTitleName());
    }

    @Test
    void failedFreshReadFallsBackToTheCachedUser() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        UsersQueryApi users = mock(UsersQueryApi.class);
        when(users.getByUuid(uuid)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        UserCache cache = new UserCache(Duration.ofMinutes(5));
        cache.put(user(uuid, 150, 2, "Male", 0, null, null));
        ProfileMenuFeature feature = new ProfileMenuFeature(users, cache,
                new TitleBracketsDataAccess(() -> CompletableFuture.completedFuture(BRACKETS), Duration.ofMinutes(10)));

        List<TitleRow> rows = feature.fetchRows(new MenuContentSourceContext(player, null, MenuContextParams.EMPTY)).join().items();

        assertEquals("HIGHLIGHT", rows.get(1).getDisplayMode());
    }

    @Test
    void profileSeedValidatesAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(ProfileMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
    }
}
