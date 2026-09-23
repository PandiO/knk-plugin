package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;

import java.util.ArrayList;
import java.util.List;

/**
 * IMPLEMENTATION_PLAN.md Phase 8: maps one real catalog row
 * ({@link KnkItemBlueprint}, via {@code ItemBlueprintsDataAccess.searchAsync})
 * into a {@link RuntimeMenuItem} so a content-source-backed section's fetched
 * page can render through the exact same, unmodified Phase 2 pipeline
 * ({@code MenuItemBukkitMapper.toItemStack}) every hand-authored
 * {@code MenuItemTemplate}-backed item already does - no new rendering code
 * path, only a new way to produce the {@link RuntimeMenuItem} input to the
 * existing one.
 * <p>
 * Name/Lore are synthesized as literal (non-getter-chain) {@code STATIC}
 * {@link KnkVariableBinding}s with a {@code null} id - {@link
 * net.knightsandkings.knk.core.menu.VariableResolver#resolve} only caches a
 * resolved value when a binding has a real persisted id (see its javadoc),
 * so a null-id binding here simply resolves fresh - correct-by-construction,
 * since these bindings are synthesized fresh from the query result on every
 * fetch rather than being a stable, cacheable persisted row. A literal
 * expression with no {@code $...$} placeholder resolves to itself verbatim
 * ({@code VariableResolver.resolveExpression}'s pass-through case), so no new
 * resolution machinery is needed for catalog content.
 */
public final class ItemBlueprintMenuMapper {

    private ItemBlueprintMenuMapper() {
    }

    public static RuntimeMenuItem toRuntimeMenuItem(KnkItemBlueprint blueprint) {
        List<KnkVariableBinding> bindings = new ArrayList<>();

        String name = blueprint.defaultDisplayName() != null && !blueprint.defaultDisplayName().isBlank()
                ? blueprint.defaultDisplayName()
                : blueprint.name();
        bindings.add(literalBinding("Name", 0, name));

        String description = blueprint.defaultDisplayDescription();
        if (description != null && !description.isBlank()) {
            String[] lines = description.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (!trimmed.isEmpty()) {
                    bindings.add(literalBinding("Lore", i, trimmed));
                }
            }
        }

        int amount = blueprint.defaultQuantity() != null ? Math.max(1, blueprint.defaultQuantity()) : 1;

        return new RuntimeMenuItem(
                blueprint.id(), 0, null, blueprint.iconMaterialRefId(), amount,
                null, null, MenuDisplayMode.NORMAL, null, null,
                List.copyOf(bindings), List.of(), List.of()
        );
    }

    private static KnkVariableBinding literalBinding(String targetProperty, int sortOrder, String literalText) {
        return new KnkVariableBinding(null, targetProperty, sortOrder, literalText, "STATIC", null);
    }
}
