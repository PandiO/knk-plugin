package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.regions.RegionIds;

/**
 * PandiO/knk-workspace#668: the NPC module starts in {@code KnKPlugin.registerCommands}, before the plugin builds its
 * WorldGuard region tracker. It used to take the tracker's region query at start, which was null on every server, so
 * the world its rule checks read regions from (the recheck of the way ahead, the trespass guard, a group's follower
 * lanes and guard) never saw a region, and every follower walked into a district whose entry had been removed. The
 * query is now asked for when route following is built.
 */
class NpcNavigationModuleRegionsTest {

    @TempDir
    Path dataFolder;

    private YamlConfiguration shipped() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            shipped.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return shipped;
    }

    private NpcNavigationModule start(java.util.function.Supplier<RegionIds> regions) throws Exception {
        YamlConfiguration shipped = shipped();
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(shipped);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        return NpcNavigationModule.start(plugin, mock(KnkAdminCommand.class), null, regions, () -> null);
    }

    @Test
    void theRuleChecksSeeTheRegionTrackerBuiltAfterTheModuleStarted() throws Exception {
        // KnKPlugin's order: the module starts while the region tracker does not exist yet
        AtomicReference<RegionIds> tracker = new AtomicReference<>();
        AtomicInteger asked = new AtomicInteger();
        NpcNavigationModule module = start(() -> {
            asked.incrementAndGet();
            return tracker.get();
        });
        assertFalse(module.rulesWorld().knowsRegions(), "no tracker yet: no regions");

        tracker.set(new RegionIds(() -> {
            throw new AssertionError("no WorldGuard query is made by building the world");
        }));

        assertTrue(module.rulesWorld().knowsRegions(),
            "the world the NPC rule checks read regions from uses the tracker built after the module started");
        assertTrue(asked.get() >= 2, "asked for when used, not once at start");
    }

    @Test
    void withoutWorldGuardTheRuleChecksSeeNoRegions() throws Exception {
        assertFalse(start(null).rulesWorld().knowsRegions());
        assertFalse(start(() -> null).rulesWorld().knowsRegions());
    }

    /**
     * The developer's question on #668: {@code /knk npc nav group 20} run by an op takes its NPCs' access profile from
     * the command's profile argument, else {@code default}, never from the sender; the shipped {@code default} does
     * not bypass the domain rules (the core test {@code NavGroupDomainRulesTest} shows the NPC checks' subjects).
     */
    @Test
    void theShippedDefaultAccessProfileOfACommandStartedGroupDoesNotBypassDomainRules() throws Exception {
        NpcNavigationConfig config = NpcNavigationConfig.from(shipped().getConfigurationSection(NpcNavigationConfig.PATH));
        assertFalse(config.accessProfile(net.knightsandkings.knk.core.npc.nav.NpcAccessProfile.DEFAULT.id())
            .bypassDomainRules());
    }
}
