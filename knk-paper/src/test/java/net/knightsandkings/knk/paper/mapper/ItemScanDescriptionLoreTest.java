package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import net.knightsandkings.knk.core.domain.item.ItemLoreLayout;
import net.knightsandkings.knk.core.util.LoreWrap;
import net.knightsandkings.knk.paper.tasks.ScannedItemJsonBuilder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#40 + #212: what an ItemBlueprint scan offers as the Display description for an item the
 * plugin built - only the description, as authored, never the generated lines and never the wrap.
 */
class ItemScanDescriptionLoreTest {

    private final LocalEnchantmentRepositoryImpl repository = new LocalEnchantmentRepositoryImpl();
    private final ScannedItemJsonBuilder builder = new ScannedItemJsonBuilder(repository);

    /**
     * Two authored lines where the first wraps and the second would look like its continuation to a width-only
     * unwrap ("dojo, where the steel sang all night." is 37 characters, "Its" doesn't fit after it).
     */
    private static final String DESCRIPTION =
            "Forged in the last fire of a fallen dojo, where the steel sang all night.\nIts edge never cools.";
    private static final List<String> AUTHORED =
            List.of("§8Forged in the last fire of a fallen dojo, where the steel sang all night.", "§8Its edge never cools.");
    private static final String GRADE = "§l§bGrade: ★★★★★";

    @AfterEach
    void restoreDefaultWidth() {
        LoreWrap.setMaxWidth(LoreWrap.DEFAULT_MAX_WIDTH);
    }

    /** The lore {@code ItemBlueprintBukkitMapper.fromBlueprint} + the assembler's custom enchantments make. */
    private List<String> builtLore(String description, String origin) {
        List<String> lore = new ArrayList<>(ItemBlueprintBukkitMapper.buildLore(description));
        lore.add(GRADE);
        if (origin != null) {
            lore.addAll(LoreWrap.wrap(origin));
        }
        lore = new ArrayList<>(ItemLoreLayout.compose(List.of(), lore));
        lore = new ArrayList<>(CustomEnchantmentLore.apply(repository, lore, "strength", 2));
        return CustomEnchantmentLore.apply(repository, lore, "chaos", 1);
    }

    /** What {@code fromBlueprint} stamps as {@link ItemDescriptionTag}, read back through a map-backed PDC. */
    private static List<String> stampedTag(String description) {
        Map<Object, Object> values = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(2)))
                .when(pdc).set(any(), eq(PersistentDataType.STRING), any());
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(inv -> values.get(inv.getArgument(0)));
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);

        List<String> authored = ItemBlueprintBukkitMapper.descriptionLines(description);
        ItemDescriptionTag.stamp(meta, authored, ItemBlueprintBukkitMapper.buildLore(description));
        return ItemDescriptionTag.read(meta);
    }

    @Test
    void anEnchantedGradedItemScansToItsDescriptionAsAuthored() {
        List<String> lore = builtLore(DESCRIPTION, "§7Origin: The Free City of Cinix on the River (Town)");
        assertTrue(lore.contains("§7Strength II") && lore.contains("§7Chaos I") && lore.contains(GRADE), "built: " + lore);
        assertTrue(lore.contains("§8dojo, where the steel sang all night."), "the description wrapped: " + lore);

        assertEquals(AUTHORED, builder.descriptionLore(lore, stampedTag(DESCRIPTION)));
    }

    @Test
    void theScannedDescriptionRebuildsTheSameItem() {
        List<String> lore = builtLore(DESCRIPTION, "§7Origin: Cinix (Town)");
        String scanned = String.join("\n", builder.descriptionLore(lore, stampedTag(DESCRIPTION)));

        assertEquals(lore, builtLore(scanned, "§7Origin: Cinix (Town)"), "scan → save → build gives the same lore");
        assertEquals(stampedTag(DESCRIPTION), stampedTag(scanned));
    }

    @Test
    void aDescriptionThatDidNotWrapHasNoTagAndScansAsItIs() {
        String shortDescription = "A fine blade.\nIt cuts.";
        assertEquals(List.of(), stampedTag(shortDescription), "no extra tag, so the item stacks as before");

        assertEquals(List.of("§8A fine blade.", "§8It cuts."),
                builder.descriptionLore(builtLore(shortDescription, null), List.of()));
    }

    @Test
    void whenTheWidthChangedSinceTheBuildTheAuthoredLinesStillCount() {
        LoreWrap.setMaxWidth(24);
        List<String> tag = stampedTag(DESCRIPTION);
        List<String> lore = builtLore(DESCRIPTION, null);
        LoreWrap.setMaxWidth(30);

        assertEquals(AUTHORED, builder.descriptionLore(lore, tag));
    }

    @Test
    void aDescriptionEditedByHandAfterTheBuildFallsBackToJoiningTheWrap() {
        List<String> lore = new ArrayList<>(builtLore(DESCRIPTION, null));
        lore.set(lore.indexOf("§8Its edge never cools."), "§8Its edge never sleeps.");

        // The tag no longer matches: the wrap is joined back by width (here it also takes the second line along).
        assertEquals(List.of("§8Forged in the last fire of a fallen dojo, where the steel sang all night. Its edge never sleeps."),
                builder.descriptionLore(lore, stampedTag(DESCRIPTION)));
    }

    @Test
    void anItemWithOnlyGeneratedLinesHasNoDescription() {
        assertEquals(List.of(), builder.descriptionLore(builtLore(null, "§7Origin: Cinix (Town)"), List.of()));
        assertEquals(List.of(), builder.descriptionLore(List.of(), List.of()));
    }

    @Test
    void anItemThePluginDidNotBuildKeepsAllItsLore() {
        List<String> lore = List.of("§5A plain vanilla-style lore line that is long enough", "§5and a second one");
        assertEquals(lore, builder.descriptionLore(lore, List.of()));
    }
}
