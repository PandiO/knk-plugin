package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryReport;
import net.knightsandkings.knk.core.territory.TerritoryService;
import net.knightsandkings.knk.core.territory.TerritorySettings;

/** {@code /knk territory here|status|rebuild} (PandiO/knk-workspace#689); the node is the metadata's. */
class TerritoryAdminCommandTest {

    private ServerMock server;
    private World world;
    private PlayerMock staff;
    private final AtomicReference<TerritoryService> service = new AtomicReference<>();
    private TerritoryAdminCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        staff = server.addPlayer();
        command = new TerritoryAdminCommand(service::get, MockBukkit.createMockPlugin());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private TerritoryService running() {
        TerritoryInputs.Source district = new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT, 10, "Old Town",
                "world", "d", List.of(), TerritoryHolder.town(1), false, 40);
        TerritoryInputs inputs = new TerritoryInputs(false, "abcdef0123456789", Map.of(1, "Phoenix"), Map.of(),
                List.of(district), List.of());
        TerritoryApi api = new TerritoryApi() {
            public CompletableFuture<TerritoryInputs> fetchInputs() {
                return CompletableFuture.completedFuture(inputs);
            }

            public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
                throw new AssertionError("the API's flag is off");
            }
        };
        RegionOutline outline = new RegionOutline("d", "world", List.of(new OutlinePoint(0, 0), new OutlinePoint(15, 0),
                new OutlinePoint(15, 15), new OutlinePoint(0, 15)), 0, 255);
        return new TerritoryService(new TerritorySettings(true, 10, true, true, false, 0, Map.of()), api,
                (w, r) -> Optional.of(outline), null, null, Runnable::run, Runnable::run, Logger.getAnonymousLogger());
    }

    private String drain(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = player.nextMessage()) != null) {
            all.append(org.bukkit.ChatColor.stripColor(line)).append('\n');
        }
        return all.toString();
    }

    @Test
    void withProductionOff_everyFormSaysSo() {
        command.execute(staff, new String[] { "here" });
        assertEquals("Territory is off on this server (production.enabled: false).\n", drain(staff));
        assertEquals(TerritoryAdminCommand.PERMISSION, TerritoryAdminCommand.metadata().permission());
    }

    @Test
    void rebuild_thenHere_namesTheHolderAndTheSplitChunk() {
        service.set(running());
        command.execute(staff, new String[] { "rebuild" });
        String rebuilt = drain(staff);
        assertTrue(rebuilt.contains("Territory: "), rebuilt);
        assertTrue(rebuilt.contains("version abcdef012345"), rebuilt);

        staff.teleport(new Location(world, 50.5, 64, 8.5));
        command.execute(staff, new String[] { "here" });
        String here = drain(staff);
        assertTrue(here.contains("Territory at 50 8 (chunk 3 0, split): Phoenix"), here);

        staff.teleport(new Location(world, 300.5, 64, 8.5));
        command.execute(staff, new String[] { "here" });
        assertTrue(drain(staff).contains("wilderness"));
    }

    @Test
    void status_showsTheCacheAndThatTheReportWasNotSent() {
        service.set(running());
        service.get().refresh("test", false).join();

        command.execute(staff, new String[] { "status" });
        String status = drain(staff);

        assertTrue(status.contains("Sources: 1, awards: 0, API Production:Enabled: false"), status);
        assertTrue(status.contains("Last report: not sent: the API's Production:Enabled is off"), status);
    }

    @Test
    void completesTheActions_andShowsUsageOtherwise() {
        assertEquals(List.of("here"), command.complete(staff, new String[] { "h" }));
        service.set(running());
        command.execute(staff, new String[0]);
        assertEquals("Usage: /knk territory <here|status|rebuild>\n", drain(staff));
    }
}
