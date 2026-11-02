package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface EnchantmentDefinitionsQueryApi {
    CompletableFuture<Page<KnkEnchantmentDefinition>> search(PagedQuery query);
    CompletableFuture<KnkEnchantmentDefinition> getById(int id);

    /**
     * The combat values an admin set on the custom enchantment definitions (PandiO/knk-workspace#151), one entry per
     * custom definition. Implementations without it answer with an {@link UnsupportedOperationException}.
     */
    default CompletableFuture<List<EnchantmentTuningOverride>> listCombatValues() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("listCombatValues"));
    }
}
