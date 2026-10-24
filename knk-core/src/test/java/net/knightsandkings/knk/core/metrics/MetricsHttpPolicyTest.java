package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetricsHttpPolicyTest {

    @Test
    void loopbackNeedsNoToken() {
        assertNull(MetricsHttpPolicy.problem("127.0.0.1", ""));
        assertNull(MetricsHttpPolicy.problem("::1", null));
    }

    @Test
    void anyOtherAddressNeedsAToken() {
        assertNotNull(MetricsHttpPolicy.problem("172.17.0.1", ""));
        assertNotNull(MetricsHttpPolicy.problem("0.0.0.0", "  "));
        assertNull(MetricsHttpPolicy.problem("0.0.0.0", "secret"));
    }

    @Test
    void theBearerTokenMustMatchExactly() {
        assertTrue(MetricsHttpPolicy.isAuthorized("Bearer secret", "secret"));
        assertTrue(MetricsHttpPolicy.isAuthorized("bearer secret", "secret"));
        assertFalse(MetricsHttpPolicy.isAuthorized("Bearer secre", "secret"));
        assertFalse(MetricsHttpPolicy.isAuthorized("secret", "secret"));
        assertFalse(MetricsHttpPolicy.isAuthorized(null, "secret"));
        assertTrue(MetricsHttpPolicy.isAuthorized(null, ""));
    }
}
