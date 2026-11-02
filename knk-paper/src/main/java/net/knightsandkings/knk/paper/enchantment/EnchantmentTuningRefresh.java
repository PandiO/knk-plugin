package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Loads the combat values admins set on the custom enchantment definitions into {@link EnchantmentTuningTable}
 * (PandiO/knk-workspace#151): at startup, every {@code enchantments.tuning-refresh-minutes} and on {@code /ce reload}.
 * A failed load keeps the previous values (the built-in ones before the first success), so combat never waits on the
 * API.
 */
public class EnchantmentTuningRefresh {
    private final Supplier<CompletableFuture<List<EnchantmentTuningOverride>>> source;
    private final EnchantmentTuningTable table;
    private final Logger logger;
    private volatile boolean loadedOnce;

    public EnchantmentTuningRefresh(Supplier<CompletableFuture<List<EnchantmentTuningOverride>>> source,
                                    EnchantmentTuningTable table, Logger logger) {
        this.source = source;
        this.table = table;
        this.logger = logger;
    }

    /** Starts one load; the returned future completes with the number of matched overrides, or -1 on failure. */
    public CompletableFuture<Integer> refresh() {
        CompletableFuture<List<EnchantmentTuningOverride>> pending;
        try {
            pending = source.get();
        } catch (RuntimeException e) {
            pending = CompletableFuture.failedFuture(e);
        }
        return pending.handle((overrides, error) -> {
            if (error != null || overrides == null) {
                logger.warning("Enchantment combat values could not be loaded; keeping the "
                        + (loadedOnce ? "previous" : "built-in") + " values"
                        + (error != null ? ": " + rootMessage(error) : ""));
                return -1;
            }
            int matched = table.replaceOverrides(overrides);
            long set = overrides.stream().filter(o -> o != null && !o.isEmpty()).count();
            if (!loadedOnce) {
                logger.info("Enchantment combat values loaded: " + matched + " custom enchantments matched, "
                        + set + " with admin values");
            }
            loadedOnce = true;
            return matched;
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
