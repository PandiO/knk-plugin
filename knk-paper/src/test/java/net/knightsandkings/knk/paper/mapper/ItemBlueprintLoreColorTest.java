package net.knightsandkings.knk.paper.mapper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A blueprint description without a colour of its own is dark gray, not vanilla's purple lore (Flaming Samurai). */
class ItemBlueprintLoreColorTest {

    @Test
    void anUncoloredDescription_isDarkGray_lineByLine() {
        // The first line is 41 visible characters: since PandiO/knk-workspace#212 it wraps at the default 40.
        assertEquals(List.of("\u00a78Forged in the last fire of a fallen", "\u00a78dojo.", "\u00a78Its edge never cools."),
                ItemBlueprintBukkitMapper.buildLore("Forged in the last fire of a fallen dojo.\nIts edge never cools."));
    }

    @Test
    void aColorTheDescriptionSetsItself_winsOverTheDefault() {
        assertEquals(List.of("\u00a77Gray line", "\u00a7aGreen " + "\u00a78then dark"),
                ItemBlueprintBukkitMapper.buildLore("&7Gray line\n&aGreen &8then dark"));
    }

    @Test
    void aLineWiderThanTheLoreWidth_continuesOnTheNextLineInItsColour() {
        // PandiO/knk-workspace#212: 63 visible characters, the default width is 40.
        assertEquals(List.of("\u00a78Forged in the last fire of a fallen", "\u00a78dojo, where the steel sang."),
                ItemBlueprintBukkitMapper.buildLore("Forged in the last fire of a fallen dojo, where the steel sang."));
    }

    @Test
    void aBlankDescription_hasNoLore() {
        assertEquals(List.of(), ItemBlueprintBukkitMapper.buildLore("  \n "));
        assertEquals(List.of(), ItemBlueprintBukkitMapper.buildLore(null));
    }
}
