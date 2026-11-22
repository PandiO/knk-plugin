package net.knightsandkings.knk.core.landmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;

/** The arguments of {@code /knk landmark} (off-road phase 5.1, PandiO/knk-workspace#708, DESIGN § 4.5). */
class LandmarkCommandArgsTest {

    private static List<String> words(String text) {
        return List.of(text.split(" "));
    }

    @Test
    void createTakesANameWithSpacesAndAnOptionalRegion() {
        LandmarkCommandArgs.Create plain = LandmarkCommandArgs.parseCreate(words("Throne room"));
        assertEquals(new LandmarkCommandArgs.Create("Throne room", LandmarkAreaKind.NONE, null), plain);

        LandmarkCommandArgs.Create region = LandmarkCommandArgs.parseCreate(words("Throne room region keep_throne"));
        assertEquals(new LandmarkCommandArgs.Create("Throne room", LandmarkAreaKind.OWN_REGION, "keep_throne"), region);

        assertEquals("Radius tower", LandmarkCommandArgs.parseCreate(words("Radius tower")).name(),
            "radius is only refused where the old keyword stood");
    }

    /** A15 (landmark-domain LD08, #985): the radius form is gone. */
    @Test
    void theRadiusFormIsRefused_WithTheWayToARoundRegion() {
        for (String text : List.of("Old well radius 5", "Old well RADIUS 5,5", "Well radius", "Well radius five")) {
            LandmarkCommandArgs.RadiusGone gone = assertThrows(LandmarkCommandArgs.RadiusGone.class,
                () -> LandmarkCommandArgs.parseCreate(words(text)), text);
            assertEquals(LandmarkCommandArgs.RADIUS_GONE, gone.getMessage());
        }
        assertTrue(LandmarkCommandArgs.RADIUS_GONE.contains("//sel cyl"));
    }

    @Test
    void createRefusesWhatTheApiWouldRefuse() {
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(List.of()));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("region keep")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("Well region")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(words("#12")));
        assertThrows(IllegalArgumentException.class, () -> LandmarkCommandArgs.parseCreate(List.of("a".repeat(101))));
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
