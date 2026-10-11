package net.knightsandkings.knk.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture guard for the Bukkit-free part of knk-core (road navigation plan §0.2 / Phase 2a
 * item 6). knk-core has {@code paper-api} on its compile classpath, so the compiler would happily
 * accept an {@code org.bukkit} import in the road builder, the router or the shared helpers they
 * use; this test is what stops it. Plain source scan - no ArchUnit on the classpath.
 *
 * <p>Runs with Gradle's default test working directory (the {@code knk-core} module), the same
 * relative root the other resource-reading tests assume.
 */
class ArchitectureGuardTest {
    private static final Path CORE_ROOT = Path.of("src", "main", "java", "net", "knightsandkings", "knk", "core");

    /** Packages that must stay Bukkit-free as a whole (scanned recursively; absent until built). */
    private static final List<String> BUKKIT_FREE_PACKAGES = List.of(
        "roads",
        "navigation",
        "domain/roads"
    );

    /** Individual files that must stay Bukkit-free (must exist - they are Phase 2a's extractions). */
    private static final List<String> BUKKIT_FREE_FILES = List.of(
        "util/BlockKey.java",
        "util/Polygon2D.java",
        "regions/DomainAccessEvaluator.java"
    );

    private static final Pattern BUKKIT_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?org\\.bukkit\\b");
    private static final Pattern BUKKIT_REFERENCE = Pattern.compile("\\borg\\.bukkit\\.");

    @Test
    void sourceRootIsWhereThisGuardExpectsIt() {
        assertTrue(Files.isDirectory(CORE_ROOT),
            "knk-core sources not found at " + CORE_ROOT.toAbsolutePath()
                + " - the guard would silently scan nothing; run tests from the knk-core module directory");
    }

    @Test
    void bukkitFreeSourcesDoNotImportOrReferenceOrgBukkit() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String file : BUKKIT_FREE_FILES) {
            Path path = CORE_ROOT.resolve(file);
            assertTrue(Files.isRegularFile(path), "expected Bukkit-free file is missing: " + path);
            sources.add(path);
        }
        for (String pkg : BUKKIT_FREE_PACKAGES) {
            Path dir = CORE_ROOT.resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue; // not built yet (later phases add roads/ and navigation/)
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isCommentLine(line)) {
                    continue; // Javadoc may explain *why* something is Bukkit-free
                }
                if (BUKKIT_IMPORT.matcher(line).find() || BUKKIT_REFERENCE.matcher(line).find()) {
                    violations.add(CORE_ROOT.relativize(source) + ":" + (i + 1) + ": " + line.trim());
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "org.bukkit must not be used in the Bukkit-free part of knk-core:\n  "
                + String.join("\n  ", violations));
    }

    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }
}
