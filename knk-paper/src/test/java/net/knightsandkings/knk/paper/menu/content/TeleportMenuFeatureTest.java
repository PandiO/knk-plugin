package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.TeleportDestinationsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;
import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.paper.commands.SpawnCommand;
import net.knightsandkings.knk.paper.commands.WarpCommand;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.teleport.TeleportCharges;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportRequestService;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teleport menu (docs/specs/teleport/DESIGN.md §3.8, KNG-17 Phase 6): the feature's registrations,
 * the rows built from the same list as /warps, and the clicks run through the real teleport engine -
 * warmup, server charge after it, and every registered restriction (the siege guard's hook), since
 * /menu passes the siege command filter.
 */
class TeleportMenuFeatureTest {

    private long now = 1_000_000;
    private final Set<String> granted = new HashSet<>();
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
    private final Map<UUID, Integer> userIds = Map.of(alice.getUniqueId(), 7, bob.getUniqueId(), 8);
    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
        name -> null, () -> List.of(alice, bob));
    private final VisibleTargetResolver targets = new VisibleTargetResolver(
        name -> "bob".equalsIgnoreCase(name) ? bob : "alice".equalsIgnoreCase(name) ? alice : null,
        () -> List.of(alice, bob), p -> false);

    private final KnkTeleportDestination kardenna = new KnkTeleportDestination(3, "Kardenna", "Town", "world",
        50.5, 64, 50.5, 0f, 0f, 10, null, null, false, true, true, true, null, null);
    private final KnkTeleportDestination keep = new KnkTeleportDestination(4, "Keep", "Structure", "world",
        10.5, 64, 10.5, 0f, 0f, 0, "Knight", null, false, false, false, true, "TitleTooLow", "Reach title Knight to unlock");
    private final KnkTeleportDestination market = new KnkTeleportDestination(6, "Market", "District", "world",
        20.5, 64, 20.5, 0f, 0f, 25, null, "Noble", true, false, true, false, "InsufficientGems",
        "You don't have enough gems to teleport to this location!");
    private List<KnkTeleportDestination> list = List.of(kardenna, keep, market);
    private final TeleportDestinationsDataAccess destinations = new TeleportDestinationsDataAccess(
        userId -> CompletableFuture.completedFuture(list), Duration.ofSeconds(60));

    private final List<Integer> chargedDomains = new ArrayList<>();
    private final TeleportDestinationsCommandApi chargeApi = new TeleportDestinationsCommandApi() {
        @Override public CompletableFuture<TeleportChargeResult> chargeWarp(int d, int u, String k, boolean r, boolean c) {
            chargedDomains.add(d);
            return CompletableFuture.completedFuture(TeleportChargeResult.allowed("Gems", 10, 40, false, null));
        }
        @Override public CompletableFuture<TeleportChargeResult> chargeRequestFee(int u, int a, String k, Integer o) {
            return CompletableFuture.completedFuture(TeleportChargeResult.allowed("Coins", 0, 0, false, null));
        }
        @Override public CompletableFuture<TeleportRefundResult> refund(int u, String k, String r) {
            return CompletableFuture.completedFuture(new TeleportRefundResult(false, null, 0, null, false));
        }
    };
    private final TeleportCharges charges = new TeleportCharges(new TeleportCharger(chargeApi, 1, Runnable::run, Runnable::run),
        uuid -> CompletableFuture.completedFuture(userIds.get(uuid)), name -> world, this::byId);
    private final TargetRankCheck rankCheck = (sender, targetName, onAllowed) ->
        onAllowed.accept(new UserSummary(8, targetName, UUID.nameUUIDFromBytes(targetName.getBytes()), 0));
    private final WarpCommand warps = new WarpCommand(WarpCommand.Form.WARP, new WarpCommand.Deps(support, rankCheck, targets,
        engine, destinations, charges, uuid -> CompletableFuture.completedFuture(userIds.get(uuid)), permissions,
        name -> "world".equals(name) ? world : null, p -> false));
    private final TeleportRequestService requests = new TeleportRequestService(engine, Runnable::run, permissions,
        targets, this::byId);
    private final SpawnCommand spawn = mock(SpawnCommand.class);

    private TeleportMenuFeature.Teleports parts = new TeleportMenuFeature.Teleports(engine, destinations,
        uuid -> CompletableFuture.completedFuture(userIds.get(uuid)), permissions, warps, spawn, requests);
    private final TeleportMenuFeature feature = new TeleportMenuFeature(() -> parts);
    private final MenuFeatureRegistries registries = ContentFeatures.all(feature);

    TeleportMenuFeatureTest() {
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv ->
            CompletableFuture.completedFuture(granted.contains((String) inv.getArgument(1))));
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
        return alice.getUniqueId().equals(id) ? alice : bob.getUniqueId().equals(id) ? bob : null;
    }

    private Page<TeleportDestinationRow> rows(Player viewer) {
        return feature.fetchRows(new MenuContentSourceContext(viewer, null, MenuContextParams.EMPTY)).join();
    }

    private void click(String action, Player player, Map<String, String> params) {
        registries.actions().execute(action, new MenuActionContext(player, null, Map.of(), null, null, null, null, null), params);
    }

    private void advance(long millis) {
        now += millis;
        engine.tick(id -> false);
    }

    // ===== registrations and seed =====

    @Test
    void registersTheRootTheRowSourceAndTheThreeActions() {
        assertEquals(TeleportMenuView.class, registries.variables().declaredTypes().get(TeleportMenuFeature.ROOT));
        assertEquals(TeleportDestinationRow.class, registries.contentSources().rowTypes().get(TeleportMenuFeature.ROWS_SOURCE));
        assertTrue(registries.actions().registeredIds().containsAll(Set.of(
            TeleportMenuFeature.WARP_ACTION, TeleportMenuFeature.SPAWN_ACTION, TeleportMenuFeature.REQUESTS_ACTION)));
    }

    @Test
    void teleportSeedAndTheHubValidateAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(TeleportMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(HubMenuFeature.HUB_KEY), ContentFeatures.all()));
    }

    // ===== rows =====

    @Test
    void rowsAreTheWarpListWithLocksAsDisabledTiles() {
        granted.add(TeleportNodes.WARP);

        List<TeleportDestinationRow> rows = rows(alice).items();

        assertEquals(List.of(3, 4, 6), rows.stream().map(TeleportDestinationRow::getDomainId).toList());
        assertEquals(List.of("NORMAL", "DISABLED", "DISABLED"), rows.stream().map(TeleportDestinationRow::getDisplayMode).toList());
        assertEquals(List.of("FILLED_MAP", "BRICKS", "OAK_SIGN"), rows.stream().map(TeleportDestinationRow::getMaterial).toList());
        assertEquals(List.of("&7Town", "&7Price: &f10 gems", "", "&aAvailable! Click here to teleport"), rows.get(0).getLoreLines());
        assertEquals(List.of("&7Structure", "&7Price: &afree", "&7Title: &fKnight", "", "&cLocked! Reach title Knight to unlock"),
            rows.get(1).getLoreLines());
        assertEquals(List.of("&7District", "&7Price: &f25 gems", "&7Premium tier: &6Noble", "&aDiscovered", "",
            "&cLocked! You don't have enough gems to teleport to this location!"), rows.get(2).getLoreLines());
    }

    @Test
    void bypassNodesUnlockRowsAndWithoutTheWarpNodeEverythingIsLocked() {
        granted.addAll(Set.of(TeleportNodes.WARP, TeleportNodes.BYPASS_REQUIREMENTS, TeleportNodes.BYPASS_COST));
        assertTrue(rows(alice).items().stream().allMatch(row -> row.getDisplayMode().equals("NORMAL")));

        granted.clear();
        List<TeleportDestinationRow> locked = rows(alice).items();
        assertTrue(locked.stream().allMatch(row -> row.getDisplayMode().equals("DISABLED")));
        assertEquals("&cLocked! " + TeleportDestinationRow.NO_PERMISSION, locked.get(0).getLoreLines().get(3));
    }

    @Test
    void discoveryLineIsOnlyAsCertainAsTheServersFirstLock() {
        KnkTeleportDestination undiscovered = new KnkTeleportDestination(9, "Ruins", "Structure", "world", 0, 64, 0, 0f, 0f,
            0, null, null, true, false, false, true, "NotDiscovered", "Discover Ruins first");
        KnkTeleportDestination titleFirst = new KnkTeleportDestination(9, "Ruins", "Structure", "world", 0, 64, 0, 0f, 0f,
            0, "Knight", null, true, false, false, true, "TitleTooLow", "Reach title Knight to unlock");

        assertEquals("&8Not yet discovered", TeleportDestinationRow.discoveryLine(undiscovered));
        assertEquals("&7Must be discovered first", TeleportDestinationRow.discoveryLine(titleFirst));
        assertEquals("&cLocked! Discover Ruins first",
            TeleportDestinationRow.of(undiscovered, true, false, false).getLoreLines().get(4));
    }

    @Test
    void emptyListUnknownPlayerAndStoppedEngineEachShowOneDisabledRow() {
        list = List.of();
        assertEquals("&7No teleport destinations yet", rows(alice).items().get(0).getName());

        Player stranger = player("Stranger", new Location(world, 0, 64, 0));
        assertEquals("&cWarps aren't available right now", rows(stranger).items().get(0).getName());

        parts = null;
        Page<TeleportDestinationRow> off = rows(alice);
        assertEquals(1, off.items().size());
        assertEquals("DISABLED", off.items().get(0).getDisplayMode());
    }

    // ===== root =====

    @Test
    void theInfoTileShowsTheViewersOwnWarmup() {
        assertEquals("&7Teleportation starts after a short wait", feature.viewFor(alice).getWarmupLine(), "before the rows");

        rows(alice);
        assertEquals("&7Teleportation starts after &f5 &7seconds", feature.viewFor(alice).getWarmupLine());

        granted.add(TeleportNodes.WARMUP_SHORT);
        rows(alice);
        assertEquals("&7Teleportation starts after &f3 &7seconds", feature.viewFor(alice).getWarmupLine());

        granted.add(TeleportNodes.BYPASS_WARMUP);
        rows(alice);
        assertEquals("&7Teleportation starts right away", feature.viewFor(alice).getWarmupLine());
    }

    @Test
    void spawnAndRequestTilesFollowTheViewersState() {
        rows(alice);
        assertEquals("DISABLED", feature.viewFor(alice).getSpawnDisplayMode(), "no knk.teleport.spawn");
        granted.add(TeleportNodes.SPAWN);
        rows(alice);
        assertEquals("NORMAL", feature.viewFor(alice).getSpawnDisplayMode());

        assertEquals("DISABLED", feature.viewFor(alice).getRequestsDisplayMode());
        assertEquals(List.of("&7No pending requests"), feature.viewFor(alice).getRequestLines());
        requests.send(bob, alice, Direction.TO_TARGET);
        TeleportMenuView view = feature.viewFor(alice);
        assertEquals("NORMAL", view.getRequestsDisplayMode());
        assertEquals(List.of("&fBob &7wants to teleport to you", "", "&eClick to answer them in chat"), view.getRequestLines());
        assertEquals(List.of("&7You asked to teleport to &fAlice", "", "&eClick to see it in chat"),
            feature.viewFor(bob).getRequestLines());
    }

    // ===== actions =====

    @Test
    void clickingADestinationRunsTheWarpPathWarmupThenChargeThenTeleport() {
        granted.add(TeleportNodes.WARP);

        click(TeleportMenuFeature.WARP_ACTION, alice, Map.of("domainId", "3"));

        verify(alice).closeInventory();
        assertTrue(engine.isWarmingUp(alice.getUniqueId()), "a menu warp waits like /warp");
        assertTrue(chargedDomains.isEmpty(), "charged only after the warmup");
        advance(5_000);
        assertEquals(List.of(3), chargedDomains);
        verify(alice).teleportAsync(any(Location.class), eq(TeleportCause.COMMAND));
        verify(alice).sendMessage(contains("You teleported to Kardenna."));
    }

    @Test
    void aRegisteredRestrictionLikeTheSiegeGuardStopsAMenuWarp() {
        granted.add(TeleportNodes.WARP);
        // Stand-in for the siege minigame's TeleportRestriction (registerTeleportRestriction): /menu
        // passes the siege command filter, so this engine guard is what stops a player in a match.
        engine.registerRestriction(check -> check.subject().equals(alice)
            ? Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, "You can't teleport during a siege."))
            : Optional.empty());

        click(TeleportMenuFeature.WARP_ACTION, alice, Map.of("domainId", "3"));
        advance(5_000);

        assertFalse(engine.isWarmingUp(alice.getUniqueId()));
        assertTrue(chargedDomains.isEmpty(), "nothing charged");
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
        verify(alice).sendMessage(contains("You can't teleport during a siege."));
    }

    @Test
    void aLockedOrUnknownDestinationIsRefusedEvenIfClicked() {
        granted.add(TeleportNodes.WARP);

        click(TeleportMenuFeature.WARP_ACTION, alice, Map.of("domainId", "4"));
        click(TeleportMenuFeature.WARP_ACTION, alice, Map.of("domainId", "99"));
        advance(5_000);

        verify(alice).sendMessage(contains("Reach title Knight to unlock"));
        verify(alice).sendMessage(contains("isn't available any more"));
        assertTrue(chargedDomains.isEmpty());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void theWarpNodeIsCheckedOnClick() {
        click(TeleportMenuFeature.WARP_ACTION, alice, Map.of("domainId", "3"));

        assertFalse(engine.isWarmingUp(alice.getUniqueId()));
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void spawnAndRequestsTilesDelegateAndABadDomainIdFailsLoudly() {
        click(TeleportMenuFeature.SPAWN_ACTION, alice, Map.of());
        verify(spawn).teleportSelf(alice);

        requests.send(bob, alice, Direction.TO_TARGET);
        click(TeleportMenuFeature.REQUESTS_ACTION, alice, Map.of());
        assertEquals(List.of("Bob"), requests.pendingRequesterNames(alice), "shown again, not accepted");

        assertThrows(MenuActionException.class,
            () -> click(TeleportMenuFeature.WARP_ACTION, alice, Map.of("domainId", "$row.getDomainId$")));

        parts = null;
        click(TeleportMenuFeature.WARP_ACTION, bob, Map.of("domainId", "3"));
        verify(bob).sendMessage(contains(TeleportMenuFeature.UNAVAILABLE));
    }
}
