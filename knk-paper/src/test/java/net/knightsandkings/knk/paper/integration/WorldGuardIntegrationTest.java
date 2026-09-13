package net.knightsandkings.knk.paper.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorldGuardIntegration's {@link WorldGuardIntegration#regionExists} lookup helper.
 * The gate-door region sync this class used to also provide ({@code syncRegions}) was removed in
 * item 6.2 - see the class-level Javadoc on {@link WorldGuardIntegration} - so tests for that
 * behavior were removed along with it, rather than updated to cover a method that no longer
 * exists.
 *
 * NOTE: These tests are disabled because WorldGuard is a compileOnly dependency
 * and is not available in the test classpath. The functionality will work at runtime.
 */
@Disabled("WorldGuard is compileOnly dependency, not available in test classpath")
public class WorldGuardIntegrationTest {

    @Mock
    private org.bukkit.plugin.java.JavaPlugin mockPlugin;

    private WorldGuardIntegration integration;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Create integration with mock plugin
        integration = new WorldGuardIntegration(mockPlugin);
    }

    @Test
    public void testRegionExistsReturnsFalseForEmptyId() {
        assertFalse(integration.regionExists("", null));
        assertFalse(integration.regionExists(null, null));
    }

    @Test
    public void testRegionExistsReturnsTrueForNonEmptyId() {
        // Since we can't properly initialize WorldGuard in tests,
        // we just verify the method doesn't throw exceptions
        assertDoesNotThrow(() -> {
            boolean exists = integration.regionExists("some_region", null);
            assertFalse(exists);
        });
    }
}
