package net.knightsandkings.knk.core.duel;

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
 * core/duel must stay Bukkit-free (plan Phase 3). A package-local copy of the
 * {@code ArchitectureGuardTest} scan, so this phase adds files only.
 */
class DuelPackageBukkitFreeTest {
    private static final Path DUEL = Path.of("src", "main", "java", "net", "knightsandkings", "knk", "core", "duel");
    private static final Pattern BUKKIT = Pattern.compile("\\b(org\\.bukkit|io\\.papermc|net\\.kyori)\\.");

    @Test
    void noBukkitInCoreDuel() throws IOException {
        assertTrue(Files.isDirectory(DUEL), "not found: " + DUEL.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(DUEL)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) continue;
                    if (BUKKIT.matcher(line).find()) violations.add(source.getFileName() + ":" + (i + 1) + ": " + line);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Bukkit/Paper references in core/duel:\n" + String.join("\n", violations));
    }
}
