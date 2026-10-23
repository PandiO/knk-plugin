package net.knightsandkings.knk.paper.lootbox;

import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KNG-266: {@code LootboxWorldGuardListener} lets WorldGuard allow a click only on a box's own entities, so any other
 * entity in a protected region (an armor stand, a villager, an item frame) stays protected.
 */
class LootboxPresenterBoxEntityTest {

    private static Entity tagged(String raw) {
        Entity entity = mock(Entity.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(LootboxPresenter.TOKEN_KEY, PersistentDataType.STRING)).thenReturn(raw);
        when(entity.getPersistentDataContainer()).thenReturn(pdc);
        return entity;
    }

    @Test
    void aBoxEntityIsOne() {
        assertTrue(LootboxPresenter.isBoxEntity(tagged(UUID.randomUUID().toString())));
    }

    @Test
    void anOrphanWithAnUnreadableTagIsOne_soItsClickStillReachesThePurge() {
        assertTrue(LootboxPresenter.isBoxEntity(tagged("not-a-uuid")));
    }

    @Test
    void anyOtherEntityIsNot() {
        assertFalse(LootboxPresenter.isBoxEntity(tagged(null)));
        assertFalse(LootboxPresenter.isBoxEntity(null));
    }
}
