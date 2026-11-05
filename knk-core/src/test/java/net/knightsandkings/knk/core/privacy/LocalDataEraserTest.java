package net.knightsandkings.knk.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.privacy.LocalDataEraser.Where;

class LocalDataEraserTest {

    private static final ErasedAccount ACCOUNT = new ErasedAccount(3, 42, UUID.randomUUID());

    @Test
    void runsTheStepsOfOneThread_InOrder_AndAFailureDoesNotStopTheOthers() {
        List<String> ran = new ArrayList<>();
        LocalDataEraser eraser = new LocalDataEraser();
        eraser.register("offline store", Where.WORKER, a -> { ran.add("offline"); return 2; });
        eraser.register("siege vault", Where.MAIN_THREAD, a -> { ran.add("vault"); return 1; });
        eraser.register("broken", Where.WORKER, a -> { throw new IllegalStateException("disk"); });
        eraser.register("spool", Where.WORKER, a -> { ran.add("spool"); return -5; });

        LocalDataEraser.Outcome worker = eraser.run(Where.WORKER, ACCOUNT);

        assertEquals(List.of("offline", "spool"), ran);
        assertEquals(Map.of("offline store", 2, "spool", 0), worker.erased());
        assertEquals(List.of("broken"), worker.failed());
        assertEquals(Map.of("siege vault", 1), eraser.run(Where.MAIN_THREAD, ACCOUNT).erased());
    }

    @Test
    void aSecondRegistrationReplacesTheFirstInPlace() {
        LocalDataEraser eraser = new LocalDataEraser();
        eraser.register("a", Where.WORKER, a -> 1);
        eraser.register("b", Where.WORKER, a -> 1);
        eraser.register("a", Where.WORKER, a -> 5);

        assertEquals(List.of("a", "b"), eraser.names());
        assertEquals(5, eraser.run(Where.WORKER, ACCOUNT).erased().get("a"));
    }

    @Test
    void aStepNeedsANameAndABody() {
        LocalDataEraser eraser = new LocalDataEraser();
        assertThrows(IllegalArgumentException.class, () -> eraser.register(" ", Where.WORKER, a -> 0));
        assertThrows(IllegalArgumentException.class, () -> eraser.register("x", null, a -> 0));
        assertThrows(IllegalArgumentException.class, () -> eraser.register("x", Where.WORKER, null));
    }
}
