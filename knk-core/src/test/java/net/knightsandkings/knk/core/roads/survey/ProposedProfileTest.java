package net.knightsandkings.knk.core.roads.survey;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposedProfileTest {
    private static final Material SURFACE = new Material("STONE_BRICKS", RoadMaterialRole.SURFACE, false, 0.9, 0.0, 100);
    private static final Material KERB = new Material("COBBLESTONE", RoadMaterialRole.EDGE, true, 0.1, 1.0, 100);

    @Test
    void lookupsByNameAndRole() {
        ProposedProfile profile = new ProposedProfile(List.of(SURFACE, KERB), 5, 7, 100);

        assertEquals(Optional.of(KERB), profile.material("COBBLESTONE"));
        assertEquals(Optional.of(RoadMaterialRole.SURFACE), profile.roleOf("STONE_BRICKS"));
        assertEquals(Optional.empty(), profile.roleOf("GRASS_BLOCK"));
        assertEquals(List.of(KERB), profile.withRole(RoadMaterialRole.EDGE));
        assertTrue(profile.withRole(RoadMaterialRole.OVERLAY).isEmpty());
    }

    @Test
    void materialsAreCopiedAndUnmodifiable() {
        List<Material> materials = new ArrayList<>(List.of(SURFACE));
        ProposedProfile profile = new ProposedProfile(materials, 1, 1, 1);
        materials.add(KERB);

        assertEquals(1, profile.materials().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.materials().add(KERB));
    }

    @Test
    void widthsMustBeOrderedAndAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new ProposedProfile(List.of(), 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProposedProfile(List.of(), 3, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProposedProfile(List.of(), 1, 1, -1));
        assertThrows(NullPointerException.class, () -> new Material(null, RoadMaterialRole.SURFACE, false, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new Material("X", null, false, 0, 0, 0));
    }
}
