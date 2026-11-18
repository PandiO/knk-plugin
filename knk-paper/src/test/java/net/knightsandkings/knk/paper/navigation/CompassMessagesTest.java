package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Kind;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The navigation compass wording (KNG-234). */
class CompassMessagesTest {

    private static final KnkLocation AT = new KnkLocation(1, "x", 1.0, 64.0, 1.0, 0f, 0f, "w");

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Target target(Kind kind, boolean underAttack, int percent) {
        return new Target("objective:1", "Keep Gate", kind, underAttack, AT, percent);
    }

    @Test
    void aDefendedObjectiveUnderAttackSaysSoWithHowMuchIsHeld() {
        assertEquals("defend, under attack, 62% held", CompassMessages.siegeDetail(target(Kind.DEFEND, true, 62)));
    }

    @Test
    void anObjectiveToCaptureSaysHowMuchIsToGo() {
        assertEquals("capture, 40% to go", CompassMessages.siegeDetail(target(Kind.CAPTURE, false, 40)));
    }

    @Test
    void anUntouchedObjectiveHasNoPercentage() {
        assertEquals("capture", CompassMessages.siegeDetail(target(Kind.CAPTURE, false, 100)));
        assertEquals("defend", CompassMessages.siegeDetail(target(Kind.DEFEND, false, 100)));
    }

    @Test
    void theSpawnIsCalledYourSpawn() {
        assertEquals("your spawn", CompassMessages.siegeDetail(new Target("spawn:1", "Camp", Kind.SPAWN, false, AT, -1)));
    }

    @Test
    void theSelectionLineNumbersTheCycleAndNamesTheTarget() {
        String line = plain(CompassMessages.selected(2, 5, "Keep Gate", "defend, under attack"));
        assertTrue(line.endsWith("Compass 2/5: Keep Gate (defend, under attack)"), line);
    }

    @Test
    void aSelectionWithoutDetailHasNoBrackets() {
        String line = plain(CompassMessages.selected(1, 1, "Kardenna", null));
        assertTrue(line.endsWith("Compass 1/1: Kardenna"), line);
    }
}
