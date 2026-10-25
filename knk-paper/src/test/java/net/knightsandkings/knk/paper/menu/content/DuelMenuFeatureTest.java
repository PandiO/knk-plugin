package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.paper.duel.DuelSpectateTeleports;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.duel.view.DuelHudView;
import net.knightsandkings.knk.core.duel.view.DuelMatchSnapshot;
import net.knightsandkings.knk.core.duel.view.DuelMenuIds;
import net.knightsandkings.knk.core.duel.view.DuelSideCardView;
import net.knightsandkings.knk.core.duel.view.DuelStakeRowView;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.util.BlockProbe;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels P6 (DESIGN §9.2–§9.3, KNG-220): the duel menus against the exported seeds, their rows from the
 * runtime's snapshots, and the spectate action - rev. 6 (developer 2026-10-24): an engine teleport of kind
 * {@code ARENA} to the spectator area's arrival, after the area's own title and gem conditions (the teleport fee path,
 * {@code DuelSpectateTeleports}), refunded when the teleport doesn't happen.
 */
class DuelMenuFeatureTest {

    private static final int ARENA_ID = 7;
    private static final int STANDS_ID = 41;

    // ===== the real teleport engine (as TeleportMenuFeatureTest builds it) =====

    private long now = 1_000_000;
    private final Set<String> granted = new HashSet<>();
    // Kept in a field: a Location only holds its World weakly (CLAUDE.md).
    private final World world = world();
    private final BlockProbe flat = new BlockProbe() {
        @Override public boolean isPassable(int x, int y, int z) { return y >= 64; }
        @Override public boolean isSolid(int x, int y, int z) { return y < 64; }
        @Override public boolean isHazard(int x, int y, int z) { return false; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
    };
    private final TeleportService.PermissionLookup permissions = (player, node) ->
        CompletableFuture.completedFuture(granted.contains(node));
    private final TeleportService engine = new TeleportService(Runnable::run, permissions, TeleportSettings.defaults(),
        () -> now, w -> flat);
    private final Player alice = player("Alice", new Location(world, 0.5, 64, 0.5));
    private final Player bob = player("Bob", new Location(world, 100.5, 64, 100.5));
    private final Player steve = player("Steve", new Location(world, 50.5, 64, 50.5));
    private final Map<UUID, Integer> userIds = Map.of(alice.getUniqueId(), 7, bob.getUniqueId(), 8, steve.getUniqueId(), 9);
    private final KnkPermissible permissible = mock(KnkPermissible.class);

    // ===== the spectate fee path (rev. 6) =====

    private final List<Integer> chargedAreas = new ArrayList<>();
    private final List<String> refundedKeys = new ArrayList<>();
    private TeleportChargeResult spectateAnswer = TeleportChargeResult.allowed("Gems", 10, 40, false, null);
    private final TeleportDestinationsCommandApi chargeApi = new TeleportDestinationsCommandApi() {
        @Override public CompletableFuture<TeleportChargeResult> chargeWarp(int d, int u, String k, boolean r, boolean c) {
            throw new AssertionError("spectating is no warp any more");
        }
        @Override public CompletableFuture<TeleportChargeResult> chargeSpectateFee(int a, int u, String k, boolean r, boolean c) {
            chargedAreas.add(a);
            return CompletableFuture.completedFuture(spectateAnswer);
        }
        @Override public CompletableFuture<TeleportChargeResult> chargeRequestFee(int u, int a, String k, Integer o) {
            return CompletableFuture.completedFuture(TeleportChargeResult.allowed("Coins", 0, 0, false, null));
        }
        @Override public CompletableFuture<TeleportChargeResult> chargeBackFee(int u, int a, String k, String b) {
            return CompletableFuture.completedFuture(TeleportChargeResult.allowed("Coins", 0, 0, false, null));
        }
        @Override public CompletableFuture<TeleportRefundResult> refund(int u, String k, String r) {
            refundedKeys.add(k);
            return CompletableFuture.completedFuture(new TeleportRefundResult(true, "Gems", 10, 50L, false));
        }
    };
    private DuelSpectateTeleports.Engine spectateEngine = engine::start;
    private final DuelSpectateTeleports spectateTeleports = new DuelSpectateTeleports(plan -> spectateEngine.start(plan),
        new TeleportCharger(chargeApi, 1, Runnable::run, Runnable::run), userIds::get,
        loc -> Optional.of(new Location(world, loc.x(), loc.y(), loc.z())),
        uuid -> this.matches.stream().anyMatch(m -> m.involves(uuid)),
        p -> CompletableFuture.completedFuture(DuelSpectateTeleports.Bypass.NONE), Runnable::run);

    // ===== the duel side =====

    /** The stands: an arrival, a 10-gem price, no title. */
    private List<KnkArenaArea> areas = List.of(stands(10, null, true));
    private final List<DuelMatchSnapshot> matches = new ArrayList<>();
    private final Map<UUID, List<ItemStack>> stacks = new HashMap<>();
    private final Set<UUID> insideArena = new HashSet<>();
    private final StatisticsApi statistics = mock(StatisticsApi.class);
    private final List<String> claimed = new ArrayList<>();
    private Instant clockNow = Instant.parse("2026-10-21T12:00:00Z");

    private final DuelMenuFeature.Data data = new DuelMenuFeature.Data() {
        @Override public List<DuelMatchSnapshot> matches() { return List.copyOf(matches); }
        @Override public List<ItemStack> stakeStacks(UUID token, DuelSide side) { return stacks.getOrDefault(token, List.of()); }
        @Override public DuelHudView.QueueInfo queue(KnkArena arena) { return new DuelHudView.QueueInfo(1, 3, 1, 2); }
        @Override public int pendingClaims(UUID playerId) { return playerId.equals(alice.getUniqueId()) ? 2 : 0; }
        @Override public String claim(Player player) { claimed.add(player.getName()); return "Collected."; }
        @Override public Optional<String> busy(UUID playerId) {
            return matches.stream().anyMatch(m -> m.involves(playerId)) ? Optional.of("in a duel") : Optional.empty();
        }
    };
    private final DuelMenuFeature.Places places = new DuelMenuFeature.Places() {
        @Override public Optional<KnkArena> arenaAt(Player player) {
            return insideArena.contains(player.getUniqueId()) ? Optional.of(arena()) : Optional.empty();
        }
        @Override public boolean isInside(Player player, KnkArena arena) { return insideArena.contains(player.getUniqueId()); }
    };
    private DuelMenuFeature.Parts parts = new DuelMenuFeature.Parts(data, () -> List.of(arena()), places,
        () -> spectateTeleports::spectate, statistics, userIds::get, () -> List.of(alice, bob, steve));
    private final DuelMenuFeature feature = new DuelMenuFeature(() -> parts, new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return clockNow; }
    });
    private final MenuFeatureRegistries registries = ContentFeatures.all(feature);

    DuelMenuFeatureTest() {
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv ->
            CompletableFuture.completedFuture(granted.contains((String) inv.getArgument(1))));
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv -> permissible
            .hasPermissionAsync(inv.getArgument(0), inv.getArgument(1))
            .thenApply(net.knightsandkings.knk.core.domain.permissions.PermissionDecision::of));
    }

    // ===== seed contract =====

    @Test
    void theSeededDuelMenusValidateAgainstTheRegisteredFeature() {
        for (String key : List.of(DuelMenuIds.MENU_MATCHES, DuelMenuIds.MENU_MATCH_DETAIL, DuelMenuIds.MENU_ARENA,
                DuelMenuIds.MENU_CHALLENGE)) {
            assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(key), registries), key);
        }
    }

    // ===== spectating: an ARENA teleport after the area's own conditions (rev. 6) =====

    private static KnkArenaArea stands(Integer priceGems, Integer titleId, boolean withArrival) {
        return new KnkArenaArea(STANDS_ID, ARENA_ID, KnkArenaArea.Kind.SPECTATOR, "stands", "Stands", 0, true, null, null,
            withArrival ? new KnkLocation(1, "stands", 30.5, 64.0, 30.5, 0f, 0f, "world") : null, titleId, priceGems,
            List.of(new KnkArenaAreaRegion(410, "arena_7_spectator_410", "world")));
    }

    @Test
    void spectatingPaysTheAreasPrice_ThenStartsAnArenaTeleportWithAWarmupToTheArrival() {
        click(DuelMenuIds.ACTION_SPECTATE, alice, Map.of("arenaId", String.valueOf(ARENA_ID)));

        verify(alice).closeInventory();
        assertEquals(List.of(STANDS_ID), chargedAreas, "the area's own price, through the teleport fee path");
        assertTrue(engine.isWarmingUp(alice.getUniqueId()), "the engine's player warmup");
        advance(5_000);
        verify(alice).teleportAsync(any(Location.class), any(TeleportCause.class));
        assertTrue(refundedKeys.isEmpty());
    }

    @Test
    void aTitleRefusalIsTold_NothingIsChargedAndNoTeleportStarts() {
        areas = List.of(stands(null, 3, true));
        spectateAnswer = TeleportChargeResult.refused("TitleTooLow", "Reach title Knight to teleport to the spectator area.");

        click(DuelMenuIds.ACTION_SPECTATE, alice, Map.of("arenaId", String.valueOf(ARENA_ID)));
        advance(5_000);

        verify(alice).sendMessage(contains("Reach title Knight"));
        assertFalse(engine.isWarmingUp(alice.getUniqueId()));
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void anAreaWithoutConditionsAsksTheApiNothing() {
        areas = List.of(stands(null, null, true));

        feature.spectate(alice, ARENA_ID);

        assertTrue(chargedAreas.isEmpty());
        assertTrue(engine.isWarmingUp(alice.getUniqueId()));
    }

    @Test
    void aPaidTeleportThatDoesntHappenIsRefunded() {
        spectateEngine = plan -> CompletableFuture.completedFuture(
            net.knightsandkings.knk.core.teleport.TeleportOutcome.cancelled(net.knightsandkings.knk.core.teleport.WarmupCancelReason.MOVED));

        feature.spectate(alice, ARENA_ID);

        assertEquals(List.of(STANDS_ID), chargedAreas);
        assertEquals(1, refundedKeys.size());
        assertTrue(refundedKeys.getFirst().startsWith("spectate:"));
    }

    @Test
    void aDuelistCantSpectateDuringTheirOwnDuel() {
        matches.add(snapshot("own", DuelPhase.WARMUP));

        feature.spectate(alice, ARENA_ID);

        verify(alice).sendMessage(contains("own duel"));
        assertTrue(chargedAreas.isEmpty());
        assertFalse(engine.isWarmingUp(alice.getUniqueId()));
    }

    @Test
    void theDetailsSpectateButtonFindsTheArenaByTheMatchToken() {
        areas = List.of(stands(null, null, true));
        DuelMatchSnapshot m = snapshot("detail", DuelPhase.FIGHT);
        matches.add(m);

        MenuContextParams ctx = MenuContextParams.of(Map.of(DuelMenuIds.CTX_TOKEN, m.token().toString()));
        click(DuelMenuIds.ACTION_SPECTATE, bob, Map.of("token", m.token().toString()), ctx);

        assertTrue(engine.isWarmingUp(bob.getUniqueId()));
    }

    @Test
    void anAreaWithoutAnArrivalIsReachedOnFoot_AndAnArenaWithoutOneSaysSo() {
        areas = List.of(stands(10, null, false));
        feature.spectate(alice, ARENA_ID);
        verify(alice).sendMessage(contains("Walk to Stands to watch"));

        areas = List.of();
        feature.spectate(bob, ARENA_ID);
        verify(bob).sendMessage(contains("has no spectator area"));
        assertFalse(engine.isWarmingUp(alice.getUniqueId()));
        assertTrue(chargedAreas.isEmpty());
    }

    // ===== rows, roots, actions =====

    @Test
    void liveMatchesAreListedFromTheWarmup_AndNarrowedToAnArena() {
        matches.add(snapshot("travelling", DuelPhase.TRAVELLING));
        matches.add(snapshot("warmup", DuelPhase.WARMUP));
        assertEquals(1, feature.listed(null).size());
        assertEquals(1, feature.listed(ARENA_ID).size());
        assertEquals(0, feature.listed(99).size());
        assertFalse(registries.conditions().test(DuelMenuIds.CONDITION_MATCHES_EMPTY, ctx(alice, MenuContextParams.EMPTY), Map.of()).allowed());
        matches.clear();
        assertTrue(registries.conditions().test(DuelMenuIds.CONDITION_MATCHES_EMPTY, ctx(alice, MenuContextParams.EMPTY), Map.of()).allowed());
    }

    @Test
    void stakeItemsAreShownAsTheRealStakedStacks() {
        DuelMatchSnapshot m = snapshot("stakes", DuelPhase.FIGHT);
        matches.add(m);
        ItemStack sword = mock(ItemStack.class);
        stacks.put(m.token(), List.of(sword));

        List<DuelStakeRowView> rows = feature.stakeRows(m.token(), DuelSide.CHALLENGER);

        assertEquals(List.of(DuelStakeRowView.Kind.COINS, DuelStakeRowView.Kind.ITEM), rows.stream().map(DuelStakeRowView::kind).toList());
        DuelMenuFeature.StakeStackRow item = (DuelMenuFeature.StakeStackRow) rows.get(1);
        assertSame(sword, item.menuItemStack());
        assertEquals(List.of(), feature.stakeRows(UUID.randomUUID(), DuelSide.CHALLENGER), "an unknown match has none");
    }

    @Test
    void aSideCardReadsTheDuelistsStatisticsAsTheViewer_AndReusesTheReadBriefly() {
        DuelMatchSnapshot m = snapshot("stats", DuelPhase.FIGHT);
        matches.add(m);
        PlayerStatistics stats = new PlayerStatistics(2, "Notch", "lifetime", null, null, "UTC", "public",
            new PlayerStatistics.Profile("Squire", 10, 0, 0, null, 0, 0), List.of(), null, null);
        when(statistics.getUserStatistics(2, 8, "lifetime", null)).thenReturn(CompletableFuture.completedFuture(stats));

        DuelSideCardView card = feature.sideCard(bob, m.token(), DuelSide.OPPONENT).join().items().getFirst();
        assertTrue(card.getLines().contains("&7Title: &fSquire"), card.getLines().toString());
        feature.sideCard(bob, m.token(), DuelSide.OPPONENT).join();
        verify(statistics, times(1)).getUserStatistics(2, 8, "lifetime", null);

        clockNow = clockNow.plusSeconds(11);
        feature.sideCard(bob, m.token(), DuelSide.OPPONENT).join();
        verify(statistics, times(2)).getUserStatistics(2, 8, "lifetime", null);

        when(statistics.getUserStatistics(2, 9, "lifetime", null)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        assertTrue(feature.sideCard(steve, m.token(), DuelSide.OPPONENT).join().items().getFirst().getLines()
            .contains("&8Statistics aren't available right now."));
    }

    @Test
    void challengingRunsTheDuelCommand_OnlyInsideTheArena() {
        assertFalse(registries.conditions().test(DuelMenuIds.CONDITION_IN_ARENA, ctx(alice, MenuContextParams.EMPTY), Map.of())
            .allowed(), "outside the arena");
        insideArena.add(alice.getUniqueId());
        assertTrue(registries.conditions().test(DuelMenuIds.CONDITION_IN_ARENA, ctx(alice, MenuContextParams.EMPTY), Map.of()).allowed());

        click(DuelMenuIds.ACTION_CHALLENGE, alice, Map.of("name", "Steve"));
        verify(alice).performCommand("duel Steve");
        click(DuelMenuIds.ACTION_CHALLENGE, alice, Map.of("name", "Steve; op me"));
        verify(alice, times(1)).performCommand(anyString());
    }

    @Test
    void theChallengePickerListsTheOtherPlayers_BusyOnesDisabled() {
        matches.add(snapshot("busy", DuelPhase.FIGHT, bob));
        var rows = feature.challengeRows(alice);
        assertEquals(List.of("Bob", "Steve"), rows.stream().map(r -> r.getName()).toList());
        assertEquals(List.of("DISABLED", "NORMAL"), rows.stream().map(r -> r.getDisplayMode()).toList());
    }

    @Test
    void theArenaRootIsTheViewersArena_AndClaimsAreCollected() {
        insideArena.add(alice.getUniqueId());
        var view = feature.arenaView(alice, MenuContextParams.EMPTY);
        assertEquals("7", view.getArenaId());
        assertEquals("NORMAL", view.getChallengeDisplayMode());
        assertEquals("HIGHLIGHT", view.getClaimsDisplayMode());
        assertEquals("DISABLED", feature.arenaView(bob, MenuContextParams.of(Map.of(DuelMenuIds.CTX_ARENA, "7")))
            .getChallengeDisplayMode(), "Bob is outside");

        click(DuelMenuIds.ACTION_CLAIM, alice, Map.of());
        assertEquals(List.of("Alice"), claimed);
        verify(alice).sendMessage("Collected.");
    }

    @Test
    void whileDuelsAreOffTheMenusSaySo() {
        parts = null;
        assertEquals(List.of("&cDuels aren't running right now."), feature.serverView().getLines());
        click(DuelMenuIds.ACTION_SPECTATE, alice, Map.of("arenaId", "7"));
        verify(alice).sendMessage(contains(DuelMenuFeature.UNAVAILABLE));
        assertEquals(List.of(), feature.listed(null));
    }

    @Test
    void theOpenersUseTheSeededKeys_AndPhaseChangesRepaintOnlyOpenDuelMenus() {
        var menus = mock(net.knightsandkings.knk.paper.menu.MenuService.class);
        when(menus.isMenuAvailable(anyString())).thenReturn(true);
        DuelMenus openers = new DuelMenus(menus, feature, () -> List.of(arena()));
        UUID token = UUID.randomUUID();

        assertTrue(openers.openMatches(alice));
        verify(menus).openMenu(alice, DuelMenuIds.MENU_MATCHES, MenuContextParams.EMPTY);
        assertTrue(openers.openMatch(alice, token));
        verify(menus).openMenu(alice, DuelMenuIds.MENU_MATCH_DETAIL, MenuContextParams.of(Map.of("token", token.toString())));
        assertTrue(openers.openArena(alice, "ARENA_2"), "by key, any case");
        assertTrue(openers.openArena(alice, "the arena"), "by name");
        verify(menus, times(2)).openMenu(alice, DuelMenuIds.MENU_ARENA, MenuContextParams.of(Map.of("arena", "7")));
        assertFalse(openers.openArena(alice, "nowhere"));
        assertTrue(openers.openArena(alice, null));
        verify(menus).openMenu(alice, DuelMenuIds.MENU_ARENA, MenuContextParams.EMPTY);

        when(menus.isMenuAvailable(DuelMenuIds.MENU_MATCHES)).thenReturn(false);
        assertFalse(openers.openMatches(bob), "an unvalidated menu isn't opened; P5 falls back to chat");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.function.Predicate<net.knightsandkings.knk.paper.menu.OpenMenuContext>> filter =
            org.mockito.ArgumentCaptor.forClass(java.util.function.Predicate.class);
        openers.phaseChanged(null, DuelPhase.WARMUP, DuelPhase.BATTLE_COUNTDOWN);
        verify(menus).refreshOpenMenus(filter.capture());
        var duelMenu = mock(net.knightsandkings.knk.paper.menu.OpenMenuContext.class);
        when(duelMenu.menuKey()).thenReturn(DuelMenuIds.MENU_MATCH_DETAIL);
        var other = mock(net.knightsandkings.knk.paper.menu.OpenMenuContext.class);
        when(other.menuKey()).thenReturn("teleport.destinations");
        assertTrue(filter.getValue().test(duelMenu));
        assertFalse(filter.getValue().test(other));
    }

    // ===== helpers =====

    private KnkArena arena() {
        KnkArenaArea bg = new KnkArenaArea(1, ARENA_ID, KnkArenaArea.Kind.BATTLEGROUND, "bg1", "Pit", 1, true, null, null,
            null, null, null, List.of(new KnkArenaAreaRegion(10, "arena_7_battleground_10", "world")));
        List<KnkArenaArea> all = new ArrayList<>(areas);
        all.add(bg);
        return new KnkArena(ARENA_ID, "arena_2", "The Arena", "arena_2", "world", 1, null, null, true, 3, 3, 60,
            true, true, 10_000, 9, 50, 5, null, null, 3, 10, 25_000, true, false, false, false,
            KnkArena.DuelGearMode.OWN_GEAR_KEEP, 30, 10, 300, 180, 5, true, true,
            List.of(new KnkArenaKit(11, "Knight", 1)), all, List.of());
    }

    private DuelMatchSnapshot snapshot(String token, DuelPhase phase) {
        return snapshot(token, phase, alice);
    }

    private DuelMatchSnapshot snapshot(String token, DuelPhase phase, Player challenger) {
        UUID id = UUID.nameUUIDFromBytes(token.getBytes());
        return new DuelMatchSnapshot(id, ARENA_ID, "The Arena", null, phase, GearMode.OWN_GEAR_KEEP,
            new DuelMatchSnapshot.Side(challenger.getUniqueId(), challenger.getName(), 1, null, 100,
                List.of(new StakeItem("s1", "DIAMOND_SWORD", 1))),
            new DuelMatchSnapshot.Side(UUID.nameUUIDFromBytes("Notch".getBytes()), "Notch", 2, null, 100, List.of()),
            60, 300, false, 0, false, 5, 0, null, null, false);
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        return world;
    }

    private static Player player(String name, Location location) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(location);
        when(player.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(true));
        return player;
    }

    private Player byId(UUID id) {
        for (Player p : List.of(alice, bob, steve)) if (p.getUniqueId().equals(id)) return p;
        return null;
    }

    private static MenuActionContext ctx(Player player, MenuContextParams params) {
        net.knightsandkings.knk.core.menu.MenuSession session = mock(net.knightsandkings.knk.core.menu.MenuSession.class);
        when(session.currentContext()).thenReturn(params);
        return new MenuActionContext(player, session, Map.of(), null, null, null, null, null);
    }

    private void click(String action, Player player, Map<String, String> params) {
        click(action, player, params, MenuContextParams.EMPTY);
    }

    private void click(String action, Player player, Map<String, String> params, MenuContextParams context) {
        registries.actions().execute(action, ctx(player, context), params);
    }

    private void advance(long millis) {
        now += millis;
        engine.tick(id -> false);
    }
}
