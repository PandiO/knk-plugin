package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.items.identity.ItemConversionLog;
import net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Item identity (PandiO/knk-workspace#141): {@code GET api/ItemIdentity/material-map} (the material → blueprint map)
 * and {@code POST api/ItemIdentity/conversion-counts} (plugin service key only).
 */
public interface ItemIdentityApi {
    CompletableFuture<MaterialBlueprintMap> fetchMaterialMap();

    /** Adds the counts to today's rows on the API; fails when the API can't be reached. */
    CompletableFuture<Void> reportConversionCounts(List<ItemConversionLog.Count> counts);
}
