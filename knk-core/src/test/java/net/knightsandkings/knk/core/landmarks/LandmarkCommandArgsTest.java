package net.knightsandkings.knk.core.landmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/** The arguments of {@code /knk landmark} (off-road phase 5.1, PandiO/knk-workspace#708, DESIGN § 4.5). */
class LandmarkCommandArgsTest {

    private static List<String> words(String text) {
        return List.of(text.split(" "));
    }

    @Test
    void createTakesANameWithSpacesAndAnOptionalExtent() {
        LandmarkCommandArgs.Create plain = LandmarkCommandArgs.parseCreate(words("Throne room"));
        assertEquals(new LandmarkCommandArgs.Create("Throne room", Landmark.Extent.NONE, null, null), plain);

        LandmarkCommandArgs.Create region = LandmarkCommandArgs.parseCreate(words("Throne room region keep_throne"));
        assertEquals(new LandmarkCommandArgs.Create("Throne room", Landmark.Extent.REGION, "keep_throne", null), region);

        LandmarkCommandArgs.Create radius = LandmarkCommandArgs.parseCreate(words("Old well RADIUS 5,5"));
        assertEquals(new LandmarkCommandArgs.Create("Old well", Landmark.Extent.RADIUS, null, 5.5), radius);
    }

    @Test
    void createRefusesWhatTheApiWouldRefuse() {
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("region keep")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("Well radius 0")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("Well radius 513")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("Well radius five")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("Well radius")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("#12")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(List.of("a".repeat(101))));
        assertEquals(512, LandmarkCommandArgs.parseRadius("512"));
    }

    @Test
    void anIdIsHashAndANumber() {
        assertEquals(Optional.of(12), LandmarkCommandArgs.idRef("#12"));
        assertTrue(LandmarkCommandArgs.idRef("12").isEmpty());
        assertTrue(LandmarkCommandArgs.idRef("#x").isEmpty());
        assertTrue(LandmarkCommandArgs.idRef("#0").isEmpty());
    }

    @Test
    void theLongestNameIsSplitOffWhatFollows() {
        List<String> names = List.of("Throne", "Throne room", "Old well");
        var split = LandmarkCommandArgs.split(words("throne ROOM Kardenna Keep"), names, 1).orElseThrow();
        assertEquals("Throne room", split.reference());
        assertEquals(List.of("Kardenna", "Keep"), split.rest());

        var whole = LandmarkCommandArgs.split(words("Throne room"), names, 0).orElseThrow();
        assertEquals("Throne room", whole.reference());
        assertTrue(whole.rest().isEmpty());

        var byId = LandmarkCommandArgs.split(words("#7 Keep"), names, 1).orElseThrow();
        assertEquals("#7", byId.reference());
        assertEquals(List.of("Keep"), byId.rest());

        assertTrue(LandmarkCommandArgs.split(words("Throne room"), names, 1).map(s -> s.reference()).filter("Throne"::equals)
            .isPresent(), "a domain must follow: the shorter name");
        assertTrue(LandmarkCommandArgs.split(words("Nowhere"), names, 0).isEmpty());
        assertTrue(LandmarkCommandArgs.split(words("#7"), names, 1).isEmpty());
        assertNull(LandmarkCommandArgs.split(List.of(), names, 0).orElse(null));
    }
}
