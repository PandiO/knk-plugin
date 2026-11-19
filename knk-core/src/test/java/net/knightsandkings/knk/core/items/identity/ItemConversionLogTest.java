package net.knightsandkings.knk.core.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemConversionLog.Count;
import net.knightsandkings.knk.core.items.identity.ItemConversionLog.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemConversionLogTest {

    private static final MaterialBlueprintMap MAP = MaterialBlueprintMap.of(List.of(new MaterialBlueprintMap.Entry("minecraft:oak_log", 10, true)));

    private final ItemConversionLog log = new ItemConversionLog();

    @Test
    void countsItemsPerSourceOutcomeAndMaterial_andDrainResets() {
        ItemIdentityResolution log10 = ItemIdentityResolver.resolve(ItemIdentityFacts.plain("minecraft:oak_log"), MAP);
        ItemIdentityResolution stone = ItemIdentityResolver.resolve(ItemIdentityFacts.plain("STONE"), MAP);
        log.record(Source.PICKUP, log10, "minecraft:oak_log", 16);
        log.record(Source.PICKUP, log10, "OAK_LOG", 4);
        log.record(Source.BLOCK_DROP, stone, "STONE", 1);
        log.record(Source.BLOCK_DROP, stone, "STONE", 0);

        List<Count> drained = log.drain();

        assertEquals(List.of(new Count("block_drop", "unresolved_no_match", "minecraft:stone", 1),
                new Count("pickup", "vanilla_match", "minecraft:oak_log", 20)), drained);
        assertEquals("unresolved_no_match=1, vanilla_match=20", ItemConversionLog.summary(drained));
        assertTrue(log.drain().isEmpty());
        assertEquals("none", ItemConversionLog.summary(List.of()));
    }

    @Test
    void restore_putsAFailedReportBack() {
        log.restore(List.of(new Count("craft", "vanilla_match", "minecraft:oak_log", 3)));
        log.record(Source.CRAFT, ItemIdentityResolver.resolve(ItemIdentityFacts.plain("minecraft:oak_log"), MAP), "minecraft:oak_log", 2);
        assertEquals(List.of(new Count("craft", "vanilla_match", "minecraft:oak_log", 5)), log.drain());
    }

    @Test
    void unresolvedItems_areLoggedOncePerReasonAndMaterial() {
        ItemIdentityResolution named = ItemIdentityResolver.resolve(
                new ItemIdentityFacts("minecraft:bread", null, null, true, false, false, false), MAP);
        assertTrue(log.firstUnresolved(named, "minecraft:bread"));
        assertFalse(log.firstUnresolved(named, "BREAD"));
        assertTrue(log.firstUnresolved(named, "minecraft:apple"));
        assertFalse(log.firstUnresolved(ItemIdentityResolver.resolve(ItemIdentityFacts.plain("minecraft:oak_log"), MAP), "minecraft:oak_log"));
    }
}
