package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.discovery.DiscoverySpool;
import net.knightsandkings.knk.core.discovery.DiscoveryTracker;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryTypeCount;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.user.UserAdminService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Domain discovery DESIGN.md §3.6: {@code /discoveries} and {@code /knk discovery list|reset|status}. */
class DiscoveryCommandsTest {

    private final DiscoveriesApi api = mock(DiscoveriesApi.class);
    private final UserAdminService users = mock(UserAdminService.class);
    private final UUID steveUuid = UUID.randomUUID();
    private final UserSummary steve = new UserSummary(7, "Steve", steveUuid, 250);
    private final Player staff = mock(Player.class);
    private final Player steveOnline = mock(Player.class);
    private final DiscoveryTracker tracker = new DiscoveryTracker(12);
    private final List<UUID> afterReset = new ArrayList<>();

    DiscoveryCommandsTest() {
        doAnswer(inv -> {
            Consumer<UserSummary> onFound = inv.getArgument(2);
            onFound.accept(steve);
            return null;
        }).when(users).resolveTarget(any(), anyString(), any());
    }

    private DiscoveryAdminCommand command(boolean bukkitNode, boolean knkNode) {
        when(staff.hasPermission(DiscoveryAdminCommand.NODE)).thenReturn(bukkitNode);
        return new DiscoveryAdminCommand(api, users, Runnable::run, player -> 42, (player, node) -> knkNode,
                () -> tracker, () -> null, uuid -> uuid.equals(steveUuid) ? steveOnline : null, afterReset::add);
    }

    // ===== /discoveries =====

    @Test
    void discoveriesOpensTheMenuForPlayersOnly() {
        MenuService service = mock(MenuService.class);
        Player player = mock(Player.class);
        new DiscoveriesCommand(() -> service).onCommand(player, mock(Command.class), "discoveries", new String[0]);
        verify(service).openMenu(player, "discoveries.main", MenuContextParams.EMPTY);

        CommandSender console = mock(CommandSender.class);
        new DiscoveriesCommand(() -> service).onCommand(console, mock(Command.class), "disc", new String[0]);
        verify(console).sendMessage(contains("Only players"));

        new DiscoveriesCommand(() -> null).onCommand(player, mock(Command.class), "disc", new String[0]);
        verify(player).sendMessage(contains("unavailable"));
    }

    // ===== permission =====

    @Test
    void theNodeFromEitherPermissionSystemPasses_ConsoleAlways() {
        command(false, false).execute(staff, new String[] {"status"});
        verify(staff).sendMessage(contains("don't have permission"));

        Player viaWeb = mock(Player.class);
        new DiscoveryAdminCommand(api, users, Runnable::run, p -> 42, (p, node) -> node.equals("knk.admin.discovery"),
                () -> null, () -> null, u -> null, u -> { }).execute(viaWeb, new String[] {"status"});
        verify(viaWeb).sendMessage(contains("disabled"));

        CommandSender console = mock(CommandSender.class);
        command(false, false).execute(console, new String[] {"status"});
        verify(console).sendMessage(contains("enabled"));
        assertEquals("discovery", DiscoveryAdminCommand.metadata().name());
    }

    // ===== list =====

    @Test
    void listShowsCountsRewardsAndNewestDiscoveriesWithIds() {
        DiscoverySummary summary = new DiscoverySummary(List.of(new DiscoveryTypeCount("Town", 1, 2),
                new DiscoveryTypeCount("Structure", 0, 0)), null, 12, 500, 5, 60);
        OffsetDateTime when = OffsetDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZoneOffset.UTC);
        when(api.summary(7)).thenReturn(CompletableFuture.completedFuture(summary));
        when(api.progress(eq(7), any())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(
                new DiscoveryProgressRow(14, "Market", "District", "Rivia", true, when, 0, 0, 0)), 12, 1, 10)));

        command(true, false).execute(staff, new String[] {"list", "Steve"});

        ArgumentCaptor<PagedQuery> query = ArgumentCaptor.forClass(PagedQuery.class);
        verify(api).progress(eq(7), query.capture());
        assertEquals(Map.of("status", "discovered"), query.getValue().filters());
        assertEquals("discoveredAt", query.getValue().sortBy());
        assertTrue(query.getValue().sortDescending());
        verify(staff).sendMessage("§6Discoveries of Steve§7 - Town 1/2");
        verify(staff).sendMessage("§7Earned: §f500 coins, 5 gems, 60 XP");
        verify(staff).sendMessage("§7 #14 §fMarket§7 (District in Rivia) - 2026-09-20");
        verify(staff).sendMessage("§7Page 1/2 - /knk discovery list Steve 2");
    }

    @Test
    void listRejectsABadPage() {
        command(true, false).execute(staff, new String[] {"list", "Steve", "two"});
        verify(staff).sendMessage(contains("Page must be a number"));
        verify(api, never()).summary(anyInt());
    }

    // ===== reset =====

    @Test
    void resetSendsTheStaffMemberAsActorAndReloadsAnOnlinePlayersKnownSet() {
        tracker.startSession(steveUuid, 7);
        tracker.knownLoaded(steveUuid, List.of(new KnownDiscovery(14, "district_market")));
        when(api.reset(42, 7, 14)).thenReturn(CompletableFuture.completedFuture(null));
        when(api.known(7)).thenReturn(CompletableFuture.completedFuture(List.of()));

        command(false, true).execute(staff, new String[] {"reset", "Steve", "14"});

        verify(api).reset(42, 7, 14);
        verify(staff).sendMessage(contains("Reset Steve's discovery of domain #14"));
        assertTrue(tracker.offer(steveUuid, "district_market", DiscoverySource.REGION_ENTER, Instant.now()),
                "the reset place counts again this session");
        assertEquals(List.of(steveUuid), afterReset);
    }

    @Test
    void resetFromTheConsoleHasNoActor_AndA404SaysNotDiscovered() {
        when(api.reset(null, 7, 99)).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException(new ApiException("url", 404, "Request failed", "{}"))));
        CommandSender console = mock(CommandSender.class);

        command(false, false).execute(console, new String[] {"reset", "Steve", "99"});

        verify(console).sendMessage("§cSteve hasn't discovered domain #99.");
        assertTrue(afterReset.isEmpty());
    }

    @Test
    void resetNeedsANumericDomainId() {
        command(true, false).execute(staff, new String[] {"reset", "Steve", "market"});
        verify(staff).sendMessage(contains("Domain id must be a number"));
        verify(api, never()).reset(any(), anyInt(), anyInt());
    }

    // ===== status =====

    @Test
    void statusShowsTrackerAndSpoolSizes() {
        tracker.startSession(steveUuid, 7);
        tracker.offer(steveUuid, "town_rivia", DiscoverySource.REGION_ENTER, Instant.now());
        DiscoverySpool spool = mock(DiscoverySpool.class);
        when(spool.entryCount()).thenReturn(3);
        when(spool.directory()).thenReturn(java.nio.file.Path.of("plugins/KnK/discovery-spool"));
        when(staff.hasPermission(DiscoveryAdminCommand.NODE)).thenReturn(true);

        new DiscoveryAdminCommand(api, users, Runnable::run, p -> 42, (p, n) -> false, () -> tracker, () -> spool,
                u -> null, u -> { }).execute(staff, new String[] {"status"});

        verify(staff).sendMessage("§7Tracked players: §f1§7, pending places: §f1");
        verify(staff).sendMessage(contains("Spooled (API unreachable): §f3"));
        assertFalse(DiscoveryAdminCommand.USAGE.isBlank());
    }
}
