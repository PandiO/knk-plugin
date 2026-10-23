package net.knightsandkings.knk.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture guard for the Bukkit-free part of knk-core (road navigation plan §0.2 / Phase 2a
 * item 6). knk-core has {@code paper-api} on its compile classpath, so the compiler would happily
 * accept an {@code org.bukkit} import in the road builder, the router or the shared helpers they
 * use; this test is what stops it. Plain source scan - no ArchUnit on the classpath.
 *
 * <p>Also the destination engine's handle rule (destination engine DESIGN §6): only the engine package
 * constructs {@code AllowedDestination}, so nothing can navigate somewhere the player's list does not
 * contain without writing code that reads as a bypass. The sibling modules are scanned too when they are
 * checked out next to this one.
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
        "domain/roads",
        "catalog"
    );

    /** Individual files that must stay Bukkit-free (must exist - they are Phase 2a's extractions). */
    private static final List<String> BUKKIT_FREE_FILES = List.of(
        "util/BlockKey.java",
        "util/Polygon2D.java",
        "regions/DomainAccessEvaluator.java"
    );

    private static final Pattern BUKKIT_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?org\\.bukkit\\b");
    private static final Pattern BUKKIT_REFERENCE = Pattern.compile("\\borg\\.bukkit\\.");

    /** The engine package, the only place that may construct the handle (its tests included). */
    private static final String ENGINE_PACKAGE = "net.knightsandkings.knk.core.navigation.destinations";
    private static final Path ENGINE_DIR = CORE_ROOT.resolve("navigation").resolve("destinations");
    /** Every source set of the plugin's modules, relative to {@code knk-core}; a missing one is skipped. */
    private static final List<Path> ALL_SOURCE_ROOTS = List.of(
        Path.of("src", "main", "java"),
        Path.of("src", "test", "java"),
        Path.of("..", "knk-api-client", "src", "main", "java"),
        Path.of("..", "knk-api-client", "src", "test", "java"),
        Path.of("..", "knk-paper", "src", "main", "java"),
        Path.of("..", "knk-paper", "src", "test", "java")
    );
    /** {@code new AllowedDestination(}, {@code AllowedDestination::new}, or reaching the constructor by reflection. */
    private static final Pattern HANDLE_CONSTRUCTION = Pattern.compile(
        "\\bnew\\s+AllowedDestination\\s*\\(|\\bAllowedDestination\\s*::\\s*new\\b|AllowedDestination\\.class\\s*\\.\\s*getDeclaredConstructor\\b");
    /** A class elsewhere declaring the engine package would see the package-private constructor (split package). */
    private static final Pattern ENGINE_PACKAGE_DECLARATION = Pattern.compile(
        "^\\s*package\\s+" + Pattern.quote(ENGINE_PACKAGE) + "\\s*;");

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

    @Test
    void onlyTheDestinationEngineConstructsAllowedDestination() throws IOException {
        assertTrue(Files.isDirectory(ENGINE_DIR), "the engine package is missing at " + ENGINE_DIR.toAbsolutePath());
        for (Constructor<?> constructor : AllowedDestination.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers()),
                "AllowedDestination's constructor must be package-private: " + constructor);
        }

        List<String> violations = new ArrayList<>();
        for (Path root : ALL_SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue; // a sibling module not checked out next to knk-core
            }
            boolean coreModule = !root.startsWith("..");
            Path enginePackage = root.resolve(ENGINE_PACKAGE.replace('.', '/')).normalize();
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(root)) {
                sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path source : sources) {
                boolean insideEngine = coreModule && source.normalize().startsWith(enginePackage);
                List<String> lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isCommentLine(line)) {
                        continue;
                    }
                    if (!insideEngine && HANDLE_CONSTRUCTION.matcher(line).find()) {
                        violations.add(root.relativize(source) + ":" + (i + 1) + ": " + line.trim());
                    }
                    if (!insideEngine && ENGINE_PACKAGE_DECLARATION.matcher(line).find()) {
                        violations.add(root.relativize(source) + ":" + (i + 1) + ": declares the engine package from outside it");
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "AllowedDestination is minted by the destination engine only (destination engine DESIGN §6):\n  "
                + String.join("\n  ", violations));
    }

    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }
}
