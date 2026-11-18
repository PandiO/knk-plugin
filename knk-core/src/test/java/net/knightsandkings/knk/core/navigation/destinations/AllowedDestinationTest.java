package net.knightsandkings.knk.core.navigation.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;

/** The handle (DESIGN §6): minted inside the engine package only, carrying what re-validation needs. */
class AllowedDestinationTest {

    private final Candidate kardenna = Fixtures.town(1, "Kardenna", "world");

    @Test
    void carriesPlayerScopeCandidateModeAndModelVersion() {
        AllowedDestination handle = new AllowedDestination(Fixtures.PLAYER, InitialScopes.WORLD, kardenna, Mode.REGION, 4);
        assertEquals(Fixtures.PLAYER, handle.player());
        assertEquals(InitialScopes.WORLD, handle.scopeId());
        assertSame(kardenna, handle.candidate());
        assertEquals("domain:Town:1", handle.candidateKey());
        assertEquals(Mode.REGION, handle.mode());
        assertEquals(4, handle.modelVersion());
        assertTrue(handle.toString().contains("domain:Town:1"));
    }

    @Test
    void aMissingModeIsTheDefault() {
        assertEquals(Mode.DEFAULT, new AllowedDestination(Fixtures.PLAYER, "s", kardenna, null, 0).mode());
        assertThrows(NullPointerException.class, () -> new AllowedDestination(null, "s", kardenna, Mode.DEFAULT, 0));
        assertThrows(NullPointerException.class, () -> new AllowedDestination(Fixtures.PLAYER, "s", null, Mode.DEFAULT, 0));
    }

    @Test
    void theConstructorIsNotPublic() {
        for (Constructor<?> constructor : AllowedDestination.class.getDeclaredConstructors()) {
            int modifiers = constructor.getModifiers();
            assertTrue(!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers),
                "the handle must be constructible inside the engine package only: " + constructor);
        }
        assertTrue(Modifier.isFinal(AllowedDestination.class.getModifiers()), "no subclass can widen it");
    }
}
