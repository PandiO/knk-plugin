package net.knightsandkings.knk.paper.commands.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** KNG-107: every command the shared node list gates exists in plugin.yml, and no open command is gated. */
class CommandNodesTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyGatedCommandIsAPluginCommand() throws Exception {
        // Gradle runs the tests from the module directory.
        try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/plugin.yml"))) {
            Map<String, Object> yaml = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, Object> commands = (Map<String, Object>) yaml.get("commands");
            for (String name : CommandNodes.commands()) {
                assertTrue(commands.containsKey(name), name + " is not in plugin.yml");
                assertFalse(CommandNodes.anyOf(name).isEmpty(), name);
            }
        }
    }

    @Test
    void openCommandsAreNotGated() {
        for (String open : java.util.List.of("msg", "reply", "tpa", "tpaccept", "gate", "gatedoor", "knk", "afk", "leaderboard", "stats")) {
            assertTrue(CommandNodes.anyOf(open).isEmpty(), open);
        }
    }
}
