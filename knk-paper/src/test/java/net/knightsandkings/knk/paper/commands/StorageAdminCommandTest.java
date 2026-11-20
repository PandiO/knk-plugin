package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.ports.api.StorageContainersApi;
import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;
import net.knightsandkings.knk.core.production.terminal.TerminalSettings;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;
import net.knightsandkings.knk.paper.production.terminal.StorageTerminalModule;

/** {@code /knk storage} (PandiO/knk-workspace#74): the node, "off", reload, usage and completion. */
class StorageAdminCommandTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock staff;
    private final AtomicReference<StorageTerminalModule> module = new AtomicReference<>();
    private StorageAdminCommand command;

    private final StorageContainersApi api = new StorageContainersApi() {
        public CompletableFuture<Optional<StorageContainerInfo>> byBlock(String w, int x, int y, int z) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        public CompletableFuture<List<StorageContainerInfo>> list(StorageContainerInfo.Mode mode, String worldName) {
            return CompletableFuture.completedFuture(List.of(new StorageContainerInfo(7, 12, "North",
                    StorageContainerInfo.Mode.TERMINAL, "Chest", false, 27, "world", 1, 64, 0, null)));
        }

        public CompletableFuture<StorageContainerInfo> bind(BindRequest request) { return null; }
        public CompletableFuture<Void> unbind(int containerId) { return null; }
        public CompletableFuture<TerminalPage> terminal(int c, int p, int s, Integer u) { return null; }
        public CompletableFuture<StockMoveResult> put(int c, int u, Integer b, Long i, long a, String k) { return null; }
        public CompletableFuture<StockMoveResult> take(int c, int u, Integer b, Long i, long a, String k) { return null; }
        public CompletableFuture<DisplaySample> display(int id) { return null; }
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        staff = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        command = new StorageAdminCommand(module::get, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
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
    void metadataGatesOnTheProductionNode() {
        assertEquals("storage", StorageAdminCommand.metadata().name());
        assertEquals("knk.admin.world.production", StorageAdminCommand.metadata().permission());
    }

    @Test
    void offWithoutProductionEnabled() {
        command.execute(staff, new String[] {"bind", "12", "terminal"});
        assertTrue(drain(staff).contains("Production is off on this server (production.enabled: false)."));
    }

    @Test
    void reloadReadsTheBindings_andTheUsageAndCompletion() {
        module.set(new StorageTerminalModule(plugin, TerminalSettings.defaults(true), api,
                new BlueprintItems(null, null), uuid -> 41, () -> null));

        command.execute(staff, new String[] {"reload"});
        server.getScheduler().performTicks(1);
        String said = drain(staff);
        assertTrue(said.contains("1 bound container(s)."), said);
        assertEquals(1, module.get().index().size());

        command.execute(staff, new String[] {});
        assertTrue(drain(staff).contains("Usage: /knk storage"));
        assertEquals(List.of("bind"), command.complete(staff, new String[] {"b"}));
        assertEquals(List.of("terminal", "display"), command.complete(staff, new String[] {"bind", "12", ""}));
        assertEquals(List.of("display"), command.complete(staff, new String[] {"bind", "12", "d"}));
        assertEquals(List.of(), command.complete(staff, new String[] {"unbind", "x"}));
    }
}
