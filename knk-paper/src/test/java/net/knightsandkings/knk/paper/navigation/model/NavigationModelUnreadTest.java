package net.knightsandkings.knk.paper.navigation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Destination engine phase 2 (PandiO/knk-workspace#103, DESIGN § 10): nothing in the running plugin reads the
 * navigation model before phase 3. Only the model's own packages and the start-up wiring in {@code KnKPlugin} name it,
 * so {@code /navigate}, completion, the compass and {@code NavigationService} behave exactly as before. Phase 3
 * (#69) widened the list: the engine's providers ({@code core/navigation/engine}) and its plugin adapters
 * ({@code paper/navigation/engine}) read the model, and only behind {@code navigation.destination-engine}; the
 * clients (the command, the compass, the service) never name it - they ask the engine.
 */
class NavigationModelUnreadTest {

    private static final String MODEL_PACKAGE = "net.knightsandkings.knk.core.navigation.model";
    private static final String RUNTIME_PACKAGE = "net.knightsandkings.knk.paper.navigation.model";

    @Test
    void onlyTheModelPackagesAndTheStartUpWiringUseTheModel() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<String> users = new ArrayList<>();
        for (String module : List.of("knk-core", "knk-api-client", "knk-paper")) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String path = root.relativize(file).toString().replace('\\', '/');
                    if (path.contains("/core/navigation/model/") || path.contains("/paper/navigation/model/")
                        || path.contains("/core/navigation/engine/") || path.contains("/paper/navigation/engine/")) {
                        continue;
                    }
                    String text = Files.readString(file);
                    if (text.contains(MODEL_PACKAGE) || text.contains(RUNTIME_PACKAGE)) {
                        users.add(path);
                    }
                }
            }
        }
        assertTrue(Files.isDirectory(root.resolve("knk-paper/src/main/java")), "run from the knk-paper module");
        assertEquals(List.of("knk-paper/src/main/java/net/knightsandkings/knk/paper/KnKPlugin.java"), users,
            "only KnKPlugin starts the model and wires it into the engine; the engine packages read it");
    }
}
