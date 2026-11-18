package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PandiO/knk-workspace#151: loading the admin-set combat values. */
class EnchantmentTuningRefreshTest {
    private final EnchantmentTuningTable table = EnchantmentTuningTable.getInstance();

    @AfterEach
    void reset() {
        table.clearOverrides();
    }

    @Test
    void aSuccessfulLoadAppliesTheValues_AndAFailedOneKeepsThem() {
        AtomicReference<CompletableFuture<List<EnchantmentTuningOverride>>> next = new AtomicReference<>(
                CompletableFuture.completedFuture(List.of(
                        new EnchantmentTuningOverride("knk:freeze", 5d, 0d, true, 30, 1d, 0d))));
        EnchantmentTuningRefresh refresh = new EnchantmentTuningRefresh(next::get, table, Logger.getLogger("test"));

        assertEquals(1, refresh.refresh().join());
        assertEquals(0.05d, table.get("freeze").chanceAt(3), 1e-9);
        assertEquals(30_000L, table.get("freeze").effectiveCooldownMs());

        next.set(CompletableFuture.failedFuture(new IllegalStateException("API down")));
        assertEquals(-1, refresh.refresh().join());
        assertEquals(0.05d, table.get("freeze").chanceAt(3), 1e-9);
    }

    @Test
    void aSourceThatThrowsCountsAsAFailedLoad() {
        EnchantmentTuningRefresh refresh = new EnchantmentTuningRefresh(() -> {
            throw new UnsupportedOperationException("no API");
        }, table, Logger.getLogger("test"));

        assertEquals(-1, refresh.refresh().join());
        assertEquals(table.builtIn("freeze").orElseThrow(), table.get("freeze"));
    }
}
