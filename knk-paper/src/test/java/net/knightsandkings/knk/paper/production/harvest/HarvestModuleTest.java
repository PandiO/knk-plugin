package net.knightsandkings.knk.paper.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.HarvestApi;
import net.knightsandkings.knk.core.production.harvest.BlockStates;
import net.knightsandkings.knk.core.production.harvest.HarvestArea;
import net.knightsandkings.knk.core.production.harvest.HarvestCatalog;
import net.knightsandkings.knk.core.production.harvest.HarvestRule;
import net.knightsandkings.knk.core.production.harvest.HarvestSettings;
import net.knightsandkings.knk.core.production.harvest.RegenJob;
import net.knightsandkings.knk.core.production.harvest.RegenWorld;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * PandiO/knk-workspace#134 (PRODUCTION_KINDS § 6-§ 7, A16) on a MockBukkit server: a qualified player's break in a yield
 * area loses its vanilla drops, gets a placeholder and a regeneration job and is reported; the answer's worker's share
 * lands in the inventory (or waits for an offline player); unqualified players are refused; staff are left alone; a
 * Rejected entry or a 409 pays nothing; a failed report is sent again; jobs, reports and owed shares survive a restart.
 */
class HarvestModuleTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private Plugin plugin;
    private HarvestModule module;
    @TempDir
    Path dir;

    private final Set<String> granted = new HashSet<>(Set.of("knk.harvest.quarry"));
    private final Set<Player> staff = new HashSet<>();
    private final List<List<HarvestApi.ReportEntry>> reports = new ArrayList<>();
    private Function<List<HarvestApi.ReportEntry>, CompletableFuture<HarvestApi.ReportResult>> answer = this::applied;
    private final Map<String, String> placed = new HashMap<>();

    /** The regeneration queue's world: real reads from MockBukkit, writes recorded (MockBukkit can't parse block data). */
    private final RegenWorld regenWorld = new RegenWorld() {
        public boolean chunkLoaded(String w, int cx, int cz) {
            return true;
        }

        public String blockData(String w, int x, int y, int z) {
            String key = x + " " + y + " " + z;
            return placed.containsKey(key) ? placed.get(key) : world.getBlockAt(x, y, z).getBlockData().getAsString();
        }

        public boolean occupied(String w, int x, int y, int z) {
            return false;
        }

        public void set(String w, int x, int y, int z, String data) {
            placed.put(x + " " + y + " " + z, data);
        }
    };

    private final HarvestApi api = new HarvestApi() {
        public CompletableFuture<HarvestCatalog> catalog() {
            return CompletableFuture.completedFuture(HarvestModuleTest.catalog(1));
        }

        public CompletableFuture<ReportResult> report(List<ReportEntry> entries) {
            reports.add(entries);
            return answer.apply(entries);
        }
    };

    private final BlueprintItems cobble = new BlueprintItems(null, null) {
        @Override
        public Template cached(int blueprintId) {
            return new Template(new ItemStack(Material.COBBLESTONE), 64, true);
        }

        @Override
        public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
            return CompletableFuture.completedFuture(cached(blueprintId));
        }
    };

    private CompletableFuture<HarvestApi.ReportResult> applied(List<HarvestApi.ReportEntry> entries) {
        return CompletableFuture.completedFuture(new HarvestApi.ReportResult(true, entries.stream()
                .map(e -> new HarvestApi.EntryResult(e.idempotencyKey(), "Applied", null, 41, 2L * e.breaks(), 90, 90, 100))
                .toList()));
    }

    static HarvestRule stone() {
        return new HarvestRule(7, "Quarry stone", 3, HarvestRule.TargetKind.BLOCK, List.of("STONE"), 0,
                "knk.harvest.quarry", null, HarvestRule.ToolClass.PICKAXE, HarvestRule.ToolTier.ANY, 0, 41,
                HarvestRule.Placeholder.DEPLETED_BLOCK, "BEDROCK", 60);
    }

    static HarvestCatalog catalog(double fill) {
        AreaSpec pit = new AreaSpec(5, 4, "Quarry", "Pit", "Cuboid", true, true, "world", null, List.of(), null, null, null,
                null, 0, 60, 0, 9, 69, 9);
        return new HarvestCatalog(true, Instant.EPOCH, List.of(stone()), List.of(new HarvestArea(pit, 3, true, fill)));
    }

    private HarvestModule newModule() {
        HarvestSettings settings = HarvestSettings.defaults(true);
        return new HarvestModule(plugin, settings, api, cobble, uuid -> 3,
                (uuid, r) -> r.permission() == null || granted.contains(r.permission()) ? null : "you need the permission "
                        + r.permission(), () -> { }, AreaSpec.RegionShapes.NONE, staff::contains, null, "test", dir,
                java.time.Clock.systemUTC(), regenWorld);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
        module = newModule();
        module.start();
        module.apply(catalog(1));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block stoneAt(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        return block;
    }

    private BlockBreakEvent breakBlock(Player who, Block block) {
        BlockBreakEvent event = new BlockBreakEvent(block, who);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private int cobbleInInventory() {
        int n = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.COBBLESTONE) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    @Test
    void aQualifiedBreakIsHarvestedAndPaidWhenTheApiAnswers() {
        Block block = stoneAt(3, 64, 3);
        BlockBreakEvent event = breakBlock(player, block);
        assertFalse(event.isCancelled());
        assertFalse(event.isDropItems(), "the rule's items replace the vanilla drops");
        assertEquals(1, module.queue().size());
        assertTrue(module.queue().regenerating("world", 3, 64, 3));
        assertEquals("STONE", module.overlay().originalAt("world", 3, 64, 3), "surveys count it as stone");
        assertEquals(1, module.outbox().size());

        server.getScheduler().performOneTick(); // the placeholder goes down after the break
        assertEquals("BEDROCK", BlockStates.material(placed.get("3 64 3")));

        breakBlock(player, stoneAt(4, 64, 3)); // a second break joins the same entry
        module.flush();
        assertEquals(1, reports.size());
        HarvestApi.ReportEntry sent = reports.get(0).get(0);
        assertEquals(2, sent.breaks());
        assertEquals(3, sent.userId());
        assertEquals(7, sent.harvestRuleId());
        assertEquals(5, sent.productionAreaId());
        assertTrue(sent.idempotencyKey().startsWith("harvest-test-"));
        assertEquals(4, cobbleInInventory(), "the answer's workerUnits");
        assertTrue(player.nextMessage().contains("+4 Cobblestone"));
        assertEquals(0, module.outbox().size());
        RegenJob job = module.queue().jobs().iterator().next();
        assertEquals(job.createdAt() + 90_000, job.restoreAt(), "the API's regeneration time wins");
    }

    @Test
    void theBlockComesBackWhenDue() {
        breakBlock(player, stoneAt(3, 64, 3));
        server.getScheduler().performOneTick();
        RegenJob job = module.queue().jobs().iterator().next();
        module.queue().add(job.withRestoreAt(0)); // due now
        module.tick();
        assertEquals("minecraft:stone", placed.get("3 64 3"));
        assertEquals(0, module.queue().size());
        assertFalse(module.queue().regenerating("world", 3, 64, 3));
    }

    @Test
    void aBlockWaitingToRegenerateIsRefused() {
        breakBlock(player, stoneAt(3, 64, 3));
        player.nextMessage();
        Block again = world.getBlockAt(3, 64, 3);
        again.setType(Material.BEDROCK);
        BlockBreakEvent event = breakBlock(player, again);
        assertTrue(event.isCancelled());
        assertEquals("§7This block is regenerating.", player.nextMessage());
    }

    @Test
    void anUnqualifiedPlayerIsRefusedAndNothingIsReported() {
        granted.clear();
        BlockBreakEvent event = breakBlock(player, stoneAt(3, 64, 3));
        assertTrue(event.isCancelled());
        assertTrue(player.nextMessage().contains("you need the permission knk.harvest.quarry"));
        assertEquals(0, module.outbox().size());
        assertEquals(0, module.queue().size());
    }

    @Test
    void staffAndBlocksOutsideTheAreasAreLeftAlone() {
        staff.add(player);
        BlockBreakEvent staffBreak = breakBlock(player, stoneAt(3, 64, 3));
        assertFalse(staffBreak.isCancelled());
        assertTrue(staffBreak.isDropItems());
        staff.clear();
        BlockBreakEvent outside = breakBlock(player, stoneAt(30, 64, 3));
        assertFalse(outside.isCancelled());
        assertTrue(outside.isDropItems());
        Block dirt = world.getBlockAt(3, 65, 3);
        dirt.setType(Material.DIRT);
        assertTrue(breakBlock(player, dirt).isDropItems());
        assertEquals(0, module.outbox().size());
    }

    @Test
    void withTheApiFlagOffBreaksBehaveAsBefore() {
        module.apply(new HarvestCatalog(false, null, List.of(), List.of()));
        BlockBreakEvent event = breakBlock(player, stoneAt(3, 64, 3));
        assertFalse(event.isCancelled());
        assertTrue(event.isDropItems());
        assertEquals(0, module.outbox().size());
    }

    @Test
    void aRejectedEntryPaysNothing() {
        answer = entries -> CompletableFuture.completedFuture(new HarvestApi.ReportResult(true, List.of(
                new HarvestApi.EntryResult(entries.get(0).idempotencyKey(), "Rejected", "The rule is switched off.", null, 0,
                        0, 0, 0))));
        breakBlock(player, stoneAt(3, 64, 3));
        module.flush();
        assertEquals(0, cobbleInInventory());
        assertTrue(player.nextMessage().contains("was not booked: The rule is switched off."));
        assertEquals(0, module.outbox().size());
    }

    @Test
    void a409DropsTheReportAndPaysNothing() {
        answer = entries -> CompletableFuture.failedFuture(new ApiException("http://api/Harvest/reports", 409, "Request failed",
                "{\"code\":\"ProductionDisabled\"}"));
        breakBlock(player, stoneAt(3, 64, 3));
        module.flush();
        assertEquals(0, cobbleInInventory());
        assertEquals(0, module.outbox().size());
        assertTrue(player.nextMessage().contains("production is switched off"));
    }

    @Test
    void aFailedReportIsSentAgainWithTheSameKey() {
        answer = entries -> CompletableFuture.failedFuture(new IOException("timeout"));
        breakBlock(player, stoneAt(3, 64, 3));
        module.flush();
        assertEquals(1, module.outbox().size());
        assertEquals(0, cobbleInInventory());
        String key = reports.get(0).get(0).idempotencyKey();
        // replay: the API booked it the first time and now says Replayed
        answer = entries -> CompletableFuture.completedFuture(new HarvestApi.ReportResult(true, List.of(
                new HarvestApi.EntryResult(entries.get(0).idempotencyKey(), "Replayed", null, 41, 2, 60, 1, 1))));
        HarvestModuleTestAccess.retryNow(module);
        assertEquals(2, reports.size());
        assertEquals(key, reports.get(1).get(0).idempotencyKey());
        assertEquals(2, cobbleInInventory());
    }

    @Test
    void anOfflinePlayersShareWaitsUntilTheyJoin() {
        breakBlock(player, stoneAt(3, 64, 3));
        player.disconnect();
        module.flush();
        assertEquals(0, cobbleInInventory());
        player.reconnect();
        server.getScheduler().performTicks(60);
        assertEquals(2, cobbleInInventory());
    }

    @Test
    void jobsReportsAndSharesSurviveARestart() throws IOException {
        answer = entries -> new CompletableFuture<>(); // no answer before the stop
        breakBlock(player, stoneAt(3, 64, 3));
        module.flush();
        module.stop();
        assertTrue(Files.readString(dir.resolve(HarvestStore.REGEN)).contains("minecraft:stone"));
        assertTrue(Files.readString(dir.resolve(HarvestStore.OUTBOX)).contains("harvest-test-"));

        answer = this::applied;
        HarvestModule after = newModule();
        after.start();
        assertEquals(1, after.queue().size());
        assertEquals("STONE", after.overlay().originalAt("world", 3, 64, 3));
        assertEquals(1, after.outbox().size());
        after.flush();
        assertEquals(reports.get(0).get(0).idempotencyKey(), reports.get(1).get(0).idempotencyKey());
        assertEquals(2, cobbleInInventory());
        after.stop();
    }

    @Test
    void statusDescribesTheModule() {
        breakBlock(player, stoneAt(3, 64, 3));
        List<String> lines = module.status();
        assertTrue(lines.get(0).contains("API flag on, 1 rules, 1 areas"), lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Regenerating: 1 jobs")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Reports: 1 entries waiting")));
    }

    @Test
    void depletedBlockDefaultsFollowTheWorld() {
        HarvestRule noMaterial = new HarvestRule(1, "r", 3, null, List.of("STONE"), 0, null, null, null, null, 0, 41,
                HarvestRule.Placeholder.DEPLETED_BLOCK, null, 1);
        assertEquals("STONE", BlockStates.material(HarvestModule.depleted(noMaterial, world)));
        assertEquals("BEDROCK", BlockStates.material(HarvestModule.depleted(stone(), world)));
    }

    @Test
    void configReadsTheHarvestBlock() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertFalse(HarvestConfig.read(yaml).enabled());
        yaml.set("production.enabled", true);
        yaml.set("production.harvest.report-batch", 500);
        yaml.set("production.harvest.deny-at-empty-pool", true);
        yaml.set("production.harvest.messages.paid", "+{amount}");
        HarvestSettings s = HarvestConfig.read(yaml);
        assertTrue(s.enabled());
        assertEquals(200, s.reportBatch(), "capped at the API's report size");
        assertTrue(s.denyAtEmptyPool());
        assertEquals("+{amount}", s.messages().paid());
        assertEquals("&7This block is regenerating.", s.messages().regenerating());
        assertEquals(HarvestSettings.DEFAULT_REGEN_BLOCKS_PER_TICK, s.regenBlocksPerTick());
        assertNotNull(HarvestConfig.read(null));
    }
}
