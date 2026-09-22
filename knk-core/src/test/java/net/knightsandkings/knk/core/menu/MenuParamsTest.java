package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuParamsTest {

    @Test
    void parsesAFlatJsonObjectIntoAStringMap() {
        assertEquals(Map.of("node", "knk.menu.example"), MenuParams.parse("{\"node\":\"knk.menu.example\"}"));
    }

    @Test
    void nullBlankOrEmptyObjectParsesToAnEmptyMap() {
        assertTrue(MenuParams.parse(null).isEmpty());
        assertTrue(MenuParams.parse("").isEmpty());
        assertTrue(MenuParams.parse("  ").isEmpty());
        assertTrue(MenuParams.parse("{}").isEmpty());
    }

    @Test
    void malformedJsonIsTreatedAsEmptyParamsRatherThanThrowing() {
        assertTrue(MenuParams.parse("{not valid json").isEmpty());
    }
}
