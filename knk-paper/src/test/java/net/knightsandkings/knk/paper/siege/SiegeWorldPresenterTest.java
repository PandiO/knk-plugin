package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: a team without a banner still gets a gradient colour from its chat colour. */
class SiegeWorldPresenterTest {

    @Test
    void everyChatColourMapsToAValidDyeColour() {
        for (String chat : new String[]{"BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE",
                "GOLD", "GRAY", "DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE"}) {
            String dye = SiegeWorldPresenter.dyeForChatColor(chat);
            assertTrue(BannerPatternSpec.DYE_COLORS.contains(dye), chat + " -> " + dye);
        }
        assertEquals("ORANGE", SiegeWorldPresenter.dyeForChatColor("gold"));
        assertEquals("WHITE", SiegeWorldPresenter.dyeForChatColor(null));
        assertEquals("WHITE", SiegeWorldPresenter.dyeForChatColor("nonsense"));
    }
}
