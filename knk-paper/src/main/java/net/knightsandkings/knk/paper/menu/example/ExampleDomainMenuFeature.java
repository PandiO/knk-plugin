package net.knightsandkings.knk.paper.menu.example;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * InventoryMenu Phase 9 demo feature backing the create-only
 * {@code example.domain} / {@code example.domain.detail} seed menus
 * (knk-web-api {@code MenuTemplateSeed.DomainIntegration.cs}). It is the
 * smallest possible example of what Siege Phase 8b will do - one
 * {@link MenuFeature} registering:
 * <ul>
 *   <li>row source {@code example.rows} → {@link ExampleMenuRow} (E3), honouring
 *       interpolated params {@code excludeId}/{@code tag} and the search term;
 *       returns every matching row and lets the engine slice the page;</li>
 *   <li>root {@code exampleClock} → {@link ExampleClockView} (E2, for the E4
 *       auto-refresh demo);</li>
 *   <li>root {@code exampleSelected} → the row named by {@code $ctx.rowId$}
 *       (E1 + E2: a provider reading the menu's context parameters).</li>
 * </ul>
 * Not game content. Remove it (and the two seeds) whenever demo menus are no
 * longer wanted.
 */
public final class ExampleDomainMenuFeature implements MenuFeature {

    public static final String ROWS_SOURCE = "example.rows";
    public static final String CLOCK_ROOT = "exampleClock";
    public static final String SELECTED_ROOT = "exampleSelected";

    static final List<ExampleMenuRow> ROWS = List.of(
            new ExampleMenuRow(1, "Emerald", "minecraft:emerald", 3, null, null, "HIGHLIGHT", false, "gem",
                    "&7A shiny gem (&aHIGHLIGHT&7 display mode).", List.of("&7Detail line A", "&aDetail line B")),
            new ExampleMenuRow(2, "Red Banner", "WHITE_BANNER", 20, "RED|stripe_bottom:WHITE,border:BLACK", null,
                    "NORMAL", false, "banner", null, List.of()),
            new ExampleMenuRow(3, "Gradient Banner", "minecraft:white_banner", 1,
                    "stripe_top:GREEN,stripe_middle:LIME,stripe_bottom:YELLOW", null, "NORMAL", false, "banner",
                    "&7No base colour: keeps the material's white.", List.of("&7Three pattern layers")),
            new ExampleMenuRow(4, "Notch's Head", "PLAYER_HEAD", 1, null, "Notch", "NORMAL", false, "head",
                    "&7SkullOwner by player name.", List.of()),
            new ExampleMenuRow(5, "Broken Material", "minecraft:definitely_not_a_block", 70, null, null, "NORMAL",
                    false, "gem", "&cUnknown material - falls back to PAPER (logged once); amount 70 clamps to 64.",
                    List.of()),
            new ExampleMenuRow(6, "Secret Row", "STONE", 1, null, null, "NORMAL", true, "gem",
                    "&7Hidden by a Render condition - you should never see this.", List.of()),
            new ExampleMenuRow(7, "Disabled Row", "GRAY_DYE", 1, null, null, "DISABLED", false, "gem",
                    "&7DisplayMode DISABLED: clicking does nothing.", List.of()),
            new ExampleMenuRow(8, "Diamond", "DIAMOND", 1, null, null, "NORMAL", false, "gem", null, List.of()),
            new ExampleMenuRow(9, "Gold Ingot", "GOLD_INGOT", 5, null, null, "NORMAL", false, "ingot", null, List.of()),
            new ExampleMenuRow(10, "Iron Ingot", "IRON_INGOT", 12, null, null, "NORMAL", false, "ingot", null, List.of()));

    private static final ExampleMenuRow NONE_SELECTED = new ExampleMenuRow(0, "No row selected", "BARRIER", 1, null,
            null, "NORMAL", false, "none", null, List.of("&7Open this menu from a row of example.domain."));

    private final ExampleClockView clock = new ExampleClockView(System.currentTimeMillis());

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        registries.contentSources().registerRows(ROWS_SOURCE, ExampleMenuRow.class, (context, params, query) -> {
            String excludeId = params.get("excludeId");
            String tag = params.get("tag");
            String search = query.searchTerm() != null ? query.searchTerm().trim().toLowerCase(Locale.ROOT) : "";
            List<ExampleMenuRow> rows = ROWS.stream()
                    .filter(row -> excludeId == null || excludeId.isBlank() || !String.valueOf(row.getId()).equals(excludeId.trim()))
                    .filter(row -> tag == null || tag.isBlank() || row.getTag().equalsIgnoreCase(tag.trim()))
                    .filter(row -> search.isEmpty() || row.getName().toLowerCase(Locale.ROOT).contains(search))
                    .toList();
            return CompletableFuture.completedFuture(new Page<>(rows, rows.size(), 1, rows.size()));
        });
        registries.variables().register(CLOCK_ROOT, ExampleClockView.class, (player, ctx) -> clock);
        registries.variables().register(SELECTED_ROOT, ExampleMenuRow.class, (player, ctx) -> selected(ctx));
    }

    static ExampleMenuRow selected(MenuContextParams ctx) {
        String rowId = ctx.get("rowId");
        if (rowId == null) {
            return NONE_SELECTED;
        }
        return ROWS.stream()
                .filter(row -> String.valueOf(row.getId()).equals(rowId.trim()))
                .findFirst()
                .orElse(NONE_SELECTED);
    }
}
