package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.build.ProfileSet.Profile;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSetTest {

    static Material mat(String name, RoadMaterialRole role, boolean ambiguous) {
        return new Material(name, role, ambiguous, role == RoadMaterialRole.SURFACE ? 0.8 : 0.1, 0.0, 10);
    }

    static Profile townRoad() {
        return new Profile(1, "Town road", true, 5, 5, Set.of(), List.of(
            mat("STONE_BRICKS", RoadMaterialRole.SURFACE, false),
            mat("POLISHED_ANDESITE", RoadMaterialRole.EDGE, false),
            mat("COBBLESTONE", RoadMaterialRole.EDGE, true),
            mat("STONE_BRICK_STAIRS", RoadMaterialRole.ACCENT, false),
            mat("SNOW", RoadMaterialRole.OVERLAY, false)));
    }

    static Profile gravelHighway() {
        return new Profile(2, "Gravel highway", true, 3, 7, Set.of(), List.of(
            mat("GRAVEL", RoadMaterialRole.SURFACE, false),
            mat("COBBLESTONE", RoadMaterialRole.EDGE, false)));
    }

    @Test
    void surfaceEdgeAndAccentAreRoadMaterialsOverlaysAreNot() {
        ProfileSet set = new ProfileSet(List.of(townRoad()));

        assertTrue(set.isRoadMaterial("STONE_BRICKS", 0, 0));
        assertTrue(set.isRoadMaterial("POLISHED_ANDESITE", 0, 0));
        assertTrue(set.isRoadMaterial("STONE_BRICK_STAIRS", 0, 0));
        assertFalse(set.isRoadMaterial("SNOW", 0, 0), "overlays are never floors");
        assertFalse(set.isRoadMaterial("GRASS_BLOCK", 0, 0));
    }

    @Test
    void ambiguousOnlyWhenEveryListingProfileSaysSo() {
        assertTrue(new ProfileSet(List.of(townRoad())).isAmbiguous("COBBLESTONE", 0, 0));
        assertFalse(new ProfileSet(List.of(townRoad(), gravelHighway())).isAmbiguous("COBBLESTONE", 0, 0),
            "the gravel highway marks cobblestone unambiguous");
        assertFalse(new ProfileSet(List.of(townRoad())).isAmbiguous("STONE_BRICKS", 0, 0));
        assertFalse(new ProfileSet(List.of(townRoad())).isAmbiguous("GRASS_BLOCK", 0, 0), "not a road material at all");
    }

    @Test
    void disabledProfilesAreDropped() {
        Profile disabled = new Profile(3, "Old", false, 1, 1, Set.of(), List.of(mat("DIRT_PATH", RoadMaterialRole.SURFACE, false)));
        ProfileSet set = new ProfileSet(List.of(townRoad(), disabled));

        assertEquals(1, set.profiles().size());
        assertFalse(set.isRoadMaterial("DIRT_PATH", 0, 0));
        assertTrue(set.profile(3).isEmpty());
        assertTrue(set.profile(1).isPresent());
    }

    @Test
    void infoMergesRolesAndProfileIds() {
        ProfileSet set = new ProfileSet(List.of(townRoad(), gravelHighway()));

        ProfileSet.MaterialInfo cobble = set.info("COBBLESTONE", 0, 0).orElseThrow();
        assertEquals(RoadMaterialRole.EDGE, cobble.role());
        assertFalse(cobble.ambiguous());
        assertEquals(List.of(1, 2), cobble.profileIds());

        ProfileSet.MaterialInfo bricks = set.info("STONE_BRICKS", 0, 0).orElseThrow();
        assertEquals(RoadMaterialRole.SURFACE, bricks.role());
        assertEquals(List.of(1), bricks.profileIds());

        assertTrue(set.info("SNOW", 0, 0).isEmpty());
        assertTrue(set.info("GRASS_BLOCK", 0, 0).isEmpty());
    }

    @Test
    void theMostCentralRoleWinsWhenProfilesDisagree() {
        Profile plaza = new Profile(4, "Plaza", true, 9, 15, Set.of(),
            List.of(mat("COBBLESTONE", RoadMaterialRole.SURFACE, false)));
        ProfileSet set = new ProfileSet(List.of(townRoad(), plaza));

        assertEquals(RoadMaterialRole.SURFACE, set.info("COBBLESTONE", 0, 0).orElseThrow().role());
    }

    @Test
    void maxWidthMaxIsTheLargestAmongListingProfiles() {
        ProfileSet set = new ProfileSet(List.of(townRoad(), gravelHighway()));

        assertEquals(OptionalInt.of(7), set.maxWidthMax("COBBLESTONE", 0, 0));
        assertEquals(OptionalInt.of(5), set.maxWidthMax("STONE_BRICKS", 0, 0));
        assertEquals(OptionalInt.empty(), set.maxWidthMax("GRASS_BLOCK", 0, 0));
    }

    @Test
    void scopedProfilesApplyOnlyInsideTheirTowns() {
        Profile kardenna = new Profile(5, "Kardenna main street", true, 5, 7, Set.of(42), List.of(
            mat("DEEPSLATE_TILES", RoadMaterialRole.SURFACE, false)));
        ScopeLookup lookup = (x, z) -> x >= 100 ? OptionalInt.of(42) : x >= 50 ? OptionalInt.of(7) : OptionalInt.empty();
        ProfileSet set = new ProfileSet(List.of(townRoad(), kardenna), lookup);

        assertTrue(set.isRoadMaterial("DEEPSLATE_TILES", 100, 0), "inside Kardenna");
        assertFalse(set.isRoadMaterial("DEEPSLATE_TILES", 50, 0), "another town");
        assertFalse(set.isRoadMaterial("DEEPSLATE_TILES", 0, 0), "wilderness");
        assertTrue(set.isRoadMaterial("STONE_BRICKS", 0, 0), "unscoped profiles apply everywhere");

        assertEquals(List.of(1, 5), set.profilesAt(100, 0).stream().map(Profile::id).toList());
        assertEquals(List.of(1), set.profilesAt(0, 0).stream().map(Profile::id).toList());
    }

    @Test
    void scopedProfilesNeverApplyWithoutALookup() {
        Profile kardenna = new Profile(5, "Kardenna", true, 5, 7, Set.of(42), List.of(
            mat("DEEPSLATE_TILES", RoadMaterialRole.SURFACE, false)));
        ProfileSet set = new ProfileSet(List.of(kardenna));

        assertFalse(set.isRoadMaterial("DEEPSLATE_TILES", 100, 0));
    }

    @Test
    void duplicateIdsAndBadWidthsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileSet(List.of(townRoad(), townRoad())));
        assertThrows(IllegalArgumentException.class, () -> new Profile(9, "bad", true, 3, 2, Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Profile(9, "bad", true, 0, 2, Set.of(), List.of()));
    }

    @Test
    void profileHelpers() {
        Profile road = townRoad();
        assertTrue(road.isFloorMaterial("STONE_BRICKS"));
        assertFalse(road.isFloorMaterial("SNOW"));
        assertFalse(road.isFloorMaterial("GRASS_BLOCK"));
        assertTrue(road.appliesTo(OptionalInt.empty()));
        assertTrue(road.appliesTo(OptionalInt.of(1)));
    }
}
