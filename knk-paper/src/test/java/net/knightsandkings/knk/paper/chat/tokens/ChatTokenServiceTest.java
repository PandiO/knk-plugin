package net.knightsandkings.knk.paper.chat.tokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.chat.tokens.ChatToken;
import net.knightsandkings.knk.core.chat.tokens.ShareConsent;
import net.knightsandkings.knk.core.domain.settings.KnkChatTokenSettings;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** PandiO/knk-workspace#233: chat tokens end to end, without a server (direct executors, mocked API). */
class ChatTokenServiceTest {

    private static final PlayerStatistics.Profile PROFILE =
        new PlayerStatistics.Profile("Knight", 10, 20, 3, Instant.parse("2026-09-01T10:00:00Z"), 3600, 0);

    private final UUID steveId = UUID.randomUUID();
    private final Player steve = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final StatisticsApi api = mock(StatisticsApi.class);
    private final Set<String> denied = new HashSet<>();
    private KnkChatTokenSettings limits = KnkChatTokenSettings.off();
    private final Map<Integer, PlayerStatistics> publicViews = new java.util.HashMap<>();
    private final ShareConsent consent = new ShareConsent();
    private final AtomicInteger saves = new AtomicInteger();
    private ChatTokenService service;

    private static UserSummary user(int id, String name) {
        UserSummary user = mock(UserSummary.class);
        when(user.id()).thenReturn(id);
        when(user.username()).thenReturn(name);
        return user;
    }

    private static PlayerStatistics stats(int id, String name, PlayerStatistics.Metric... metrics) {
        return new PlayerStatistics(id, name, "lifetime", null, null, "UTC", "signedIn", PROFILE, List.of(metrics), null, null);
    }

    private static PlayerStatistics.Metric metric(String key, Double value) {
        return new PlayerStatistics.Metric(key, key, value, "count", "sum", List.of());
    }

    @BeforeEach
    void setUp() {
        when(steve.getUniqueId()).thenReturn(steveId);
        when(steve.getName()).thenReturn("Steve");
        when(steve.isOnline()).thenReturn(true);
        when(steve.getInventory()).thenReturn(inventory);
        UserSummary steveUser = user(7, "Steve");
        UserSummary alexUser = user(8, "Alex");
        var cache = new ChatStatisticsCache((id, acting) -> publicViews.containsKey(id)
            ? CompletableFuture.completedFuture(publicViews.get(id))
            : CompletableFuture.failedFuture(new IllegalStateException("404")), System::nanoTime);
        service = new ChatTokenService(Runnable::run, (player, node) -> !denied.contains(node), () -> limits,
            id -> id.equals(steveId) ? Optional.of(steveUser) : Optional.empty(),
            name -> CompletableFuture.completedFuture(name.equalsIgnoreCase("Alex") ? Optional.of(alexUser) : Optional.empty()),
            name -> null, user -> "Alex".equals(user.username()) ? NamedTextColor.BLUE : null, cache, api, consent, saves::incrementAndGet, Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC));
    }

    private ChatTokenService.Result expand(String legacyText, Runnable resend) {
        return service.expand(steve, LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText), resend, true).join();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private List<String> sentToSteve() {
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(steve, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(ChatTokenServiceTest::plain).toList();
    }

    @Test
    void aMessageWithoutTokensIsUnchanged() {
        assertEquals(ChatTokenService.Outcome.UNCHANGED, expand("hello #lol C#", () -> { }).outcome());
    }

    @Test
    void handBecomesTheItemsHoverableName_InsideColouredText_AndAnEmptyHandStaysText() {
        ItemStack sword = mock(ItemStack.class);
        when(sword.getAmount()).thenReturn(1);
        when(sword.displayName()).thenReturn(Component.text("[Excalibur]"));
        when(inventory.getItemInMainHand()).thenReturn(sword);

        var result = expand("&clook: #hand!", () -> { });

        assertEquals(ChatTokenService.Outcome.EXPANDED, result.outcome());
        assertEquals("look: [Excalibur]!", plain(result.message()));

        ItemStack air = mock(ItemStack.class);
        when(air.isEmpty()).thenReturn(true);
        when(inventory.getItemInMainHand()).thenReturn(air);
        assertEquals("look: #hand!", plain(expand("look: #hand!", () -> { }).message()));
    }

    @Test
    void severalTokensInOneMessage_AndTheSamePlayerIsReadOnce() {
        publicViews.put(7, stats(7, "Steve", metric("pvp_kills", 12d), metric("deaths", 4d)));

        var result = expand("#kills and #deaths, so #kd", () -> { });

        assertEquals("[12 kills] and [4 deaths], so [K/D 3.00]", plain(result.message()));
    }

    @Test
    void withoutTheTokensNodeTheTokenStaysText() {
        denied.add(ChatToken.XP.node());
        publicViews.put(7, stats(7, "Steve"));

        assertEquals("#xp and [3 title]".replace("[3 title]", "[Knight]"), plain(expand("#xp and #title", () -> { }).message()));
    }

    @Test
    void theMaxPerMessageLimitLeavesLaterTokensAsText_UnlessBypassed() {
        publicViews.put(7, stats(7, "Steve"));
        limits = new KnkChatTokenSettings(1, 0);
        denied.add(ChatToken.BYPASS_NODE);

        assertEquals("[10 XP] #title", plain(expand("#xp #title", () -> { }).message()));

        denied.remove(ChatToken.BYPASS_NODE);
        assertEquals("[10 XP] [Knight]", plain(expand("#xp #title", () -> { }).message()));
    }

    @Test
    void theStatisticsCooldownSendsTheSecondMessagesTokensAsText() {
        publicViews.put(7, stats(7, "Steve"));
        limits = new KnkChatTokenSettings(0, 30);
        denied.add(ChatToken.BYPASS_NODE);

        assertEquals("[10 XP]", plain(expand("#xp", () -> { }).message()));
        assertEquals("#xp", plain(expand("#xp", () -> { }).message()));
        assertTrue(sentToSteve().get(0).startsWith("Statistics tokens can be used again in 30s"));
    }

    @Test
    void anotherPlayersPrivateStatisticShowsAsPrivate_AndAnUnknownNameStaysText() {
        publicViews.put(8, stats(8, "Alex"));

        assertEquals("[Alex: player kills private] #kills@Nobody", plain(expand("#kills@Alex #kills@Nobody", () -> { }).message()));
    }

    @Test
    void anotherPlayersPublicStatisticNamesThem() {
        publicViews.put(8, stats(8, "Alex", metric("wins", 5d)));

        assertEquals("[Alex: 5 wins]", plain(expand("#wins@alex", () -> { }).message()));
    }

    @Test
    void ownPrivateStatistic_HoldsTheMessageAndAsks_ThenSendWithoutLeavesItAsTextOnce() {
        publicViews.put(7, stats(7, "Steve"));
        List<String> resent = new ArrayList<>();

        var held = expand("gg #kills", () -> resent.add("again"));

        assertEquals(ChatTokenService.Outcome.HELD, held.outcome());
        String prompt = sentToSteve().get(0);
        assertTrue(prompt.startsWith("Your player kills is private"), prompt);
        assertTrue(prompt.contains("[Make public & send] [Send without] [Never ask]"));

        service.answer(steve, "1", "plain");
        assertEquals(List.of("again"), resent);
        assertEquals("gg #kills", plain(expand("gg #kills", () -> { }).message()));
        assertEquals(ChatTokenService.Outcome.HELD, expand("gg #kills", () -> { }).outcome(), "only once");
    }

    @Test
    void neverAskIsRemembered_AndSaved() {
        publicViews.put(7, stats(7, "Steve"));
        List<String> resent = new ArrayList<>();
        expand("#deaths", () -> resent.add("again"));

        service.answer(steve, "1", "never");

        assertEquals(List.of("again"), resent);
        assertEquals(1, saves.get());
        assertTrue(consent.neverAsk(steveId, "deaths"));
        assertEquals("#deaths", plain(expand("#deaths", () -> { }).message()));
    }

    @Test
    void makePublic_ChangesTheMetricLevelAndHiddenOverrides_ThenResends() {
        publicViews.put(7, stats(7, "Steve"));
        var current = new StatisticsVisibilitySettings(7, false, List.of(new StatisticsVisibilitySettings.Setting("pvp_kills", "combat",
            "Player kills", true, StatisticVisibility.NOBODY, List.of(
                new StatisticsVisibilitySettings.ContextValue("siege", StatisticVisibility.NOBODY, true),
                new StatisticsVisibilitySettings.ContextValue("open_world", StatisticVisibility.NOBODY, false)))));
        when(api.getVisibility(7, 7)).thenReturn(CompletableFuture.completedFuture(current));
        when(api.updateVisibility(eq(7), eq(7), anyList())).thenReturn(CompletableFuture.completedFuture(current));
        List<String> resent = new ArrayList<>();
        expand("#kills", () -> resent.add("again"));

        service.answer(steve, "1", "public");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StatisticsVisibilitySettings.Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(api).updateVisibility(eq(7), eq(7), changes.capture());
        assertEquals(List.of(
            new StatisticsVisibilitySettings.Change("pvp_kills", "", StatisticVisibility.NOBODY, StatisticVisibility.EVERYONE),
            new StatisticsVisibilitySettings.Change("pvp_kills", "siege", StatisticVisibility.NOBODY, StatisticVisibility.EVERYONE)),
            changes.getValue());
        assertEquals(List.of("again"), resent);
    }

    @Test
    void anExpiredOrForeignAnswerDoesNothing() {
        publicViews.put(7, stats(7, "Steve"));
        List<String> resent = new ArrayList<>();
        expand("#kills", () -> resent.add("again"));

        service.answer(steve, "99", "plain");
        service.answer(steve, "1", "maybe");

        assertTrue(resent.isEmpty());
        verify(api, never()).updateVisibility(any(Integer.class), any(Integer.class), anyList());
    }

    @Test
    void tokensAloneIsNotSent_ButListsTheTokensTheSenderMayUse() {
        denied.add(ChatToken.LOCATION.node());

        assertEquals(ChatTokenService.Outcome.HELD, expand("#tokens", () -> { }).outcome());

        String list = sentToSteve().get(0);
        assertTrue(list.contains("#hand - the item in your main hand"));
        assertTrue(!list.contains("#location"));
        assertTrue(list.contains("#kills@Steve"));
    }

    @Test
    void theExpanderInterfaceAnswersTheBody_OrNullWhenHeld() {
        publicViews.put(7, stats(7, "Steve"));

        assertEquals("plain text", plain(service.expand(steve, "plain text", () -> { }).join()));
        assertEquals("[10 XP]", plain(service.expand(steve, "#xp", () -> { }).join()));
        assertEquals(null, service.expand(steve, "#kills", () -> { }).join());
    }

    @Test
    void anotherPlayersNameIsInTheirTabListColour_InTheLabelAndThePrivateLabel() {
        publicViews.put(8, stats(8, "Alex", metric("wins", 5d)));

        Component wins = expand("#wins@alex", () -> { }).message();
        assertEquals("&e[&9Alex&f: 5 wins&e]", LegacyComponentSerializer.legacyAmpersand().serialize(wins));

        Component kills = expand("#kills@Alex", () -> { }).message();
        assertEquals("&7[&9Alex&7: player kills private]", LegacyComponentSerializer.legacyAmpersand().serialize(kills));
    }

    @Test
    void aColourBecomesItsLegacyCode() {
        assertEquals("&9", ChatTokenService.legacyCode(NamedTextColor.BLUE));
        assertEquals(null, ChatTokenService.legacyCode(null));
    }
}
