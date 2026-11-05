package net.knightsandkings.knk.paper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jar's plugin.yml and config.yml must parse: a YAML slip in either one only shows up when Paper refuses to load
 * the plugin (plugin.yml) or silently drops the defaults (config.yml). Both happened on a feature branch once.
 */
class BundledResourcesTest {

    private static Reader resource(String name) {
        InputStream in = BundledResourcesTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(in, name + " is on the classpath");
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    /**
     * The plugin.yml that goes into the jar, after processResources expanded it. The classpath's plugin.yml is
     * src/test/resources' test copy (version 1.0.0-TEST), which shadows the main one.
     */
    private static Reader builtPluginYml() throws java.io.IOException {
        java.nio.file.Path built = java.nio.file.Path.of("build/resources/main/plugin.yml");
        assertTrue(java.nio.file.Files.isRegularFile(built), built + " exists (processResources ran)");
        return java.nio.file.Files.newBufferedReader(built, StandardCharsets.UTF_8);
    }

    @Test
    void pluginYmlIsAValidPluginDescription() throws Exception {
        try (Reader reader = builtPluginYml()) {
            PluginDescriptionFile description = assertDoesNotThrow(() -> new PluginDescriptionFile(reader));

            Set<String> declared = description.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());
            for (Permission permission : description.getPermissions()) {
                for (String child : permission.getChildren().keySet()) {
                    if (!child.endsWith("*")) {
                        assertTrue(declared.contains(child),
                            permission.getName() + " lists child " + child + ", which plugin.yml doesn't declare");
                    }
                }
            }
        }
    }

    @Test
    void pluginYmlVersionIsStampedAtBuildTime() throws Exception {
        // PandiO/knk-workspace#136: processResources expands ${pluginVersion} to "<base>+<commit>", or "dev".
        try (Reader reader = builtPluginYml()) {
            String version = new PluginDescriptionFile(reader).getVersion();
            assertTrue(version.equals("dev") || version.matches("\\d+\\.\\d+\\.\\d+\\+[0-9A-Za-z.-]+"),
                "plugin.yml version is '" + version + "'");
        }
    }

    @Test
    void configYmlParses() throws Exception {
        try (Reader reader = resource("config.yml")) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            for (int read; (read = reader.read(buffer)) != -1; ) {
                text.append(buffer, 0, read);
            }
            YamlConfiguration configuration = new YamlConfiguration();
            assertDoesNotThrow(() -> configuration.loadFromString(text.toString()));
            ConfigurationSection api = configuration.getConfigurationSection("api");
            assertNotNull(api, "config.yml has an api section");
        }
    }
}
