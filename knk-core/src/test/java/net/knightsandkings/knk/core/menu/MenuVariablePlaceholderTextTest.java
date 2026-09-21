package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuVariablePlaceholderTextTest {

    @Test
    void resolvesNameFromTheSingleNameBinding() {
        List<KnkVariableBinding> bindings = List.of(
                new KnkVariableBinding(1, "Name", 0, "$player.getName$", "OnDirty", null)
        );

        assertEquals("$player.getName$", MenuVariablePlaceholderText.resolveName(bindings));
    }

    @Test
    void resolvesLoreLinesInSortOrder() {
        List<KnkVariableBinding> bindings = List.of(
                new KnkVariableBinding(1, "Lore", 1, "second line", "Static", null),
                new KnkVariableBinding(2, "Lore", 0, "first line", "Static", null),
                new KnkVariableBinding(3, "Name", 0, "irrelevant", "Static", null)
        );

        assertEquals(List.of("first line", "second line"), MenuVariablePlaceholderText.resolveLore(bindings));
    }

    @Test
    void missingBindingsResolveToNullNameAndEmptyLore() {
        assertNull(MenuVariablePlaceholderText.resolveName(List.of()));
        assertTrue(MenuVariablePlaceholderText.resolveLore(List.of()).isEmpty());
        assertNull(MenuVariablePlaceholderText.resolveName(null));
        assertTrue(MenuVariablePlaceholderText.resolveLore(null).isEmpty());
    }
}
