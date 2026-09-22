package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuDefinitionValidatorTest {

    private static final Map<String, Class<?>> DECLARED_TYPES = Map.of("player", FakePlayer.class);

    @Test
    void validMenuWithResolvableChainsPassesCleanly() {
        RuntimeMenu menu = menuWithItemBinding("$player.getName$", "OnDirty");

        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(menu, DECLARED_TYPES));
    }

    @Test
    void unresolvableGetterHopIsRejectedLoudlyAtLoadTime() {
        RuntimeMenu menu = menuWithItemBinding("$player.getBalance$", "OnDirty");

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class,
                () -> MenuDefinitionValidator.validate(menu, DECLARED_TYPES));

        assertTrue(ex.getMessage().contains("getBalance"));
        assertTrue(ex.getMessage().contains(menu.key()));
    }

    @Test
    void unknownRootVariableIsRejected() {
        RuntimeMenu menu = menuWithItemBinding("$server.getTps$", "Static");

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class,
                () -> MenuDefinitionValidator.validate(menu, DECLARED_TYPES));

        assertTrue(ex.getMessage().contains("server"));
    }

    @Test
    void literalExpressionWithNoPlaceholderNeedsNoValidation() {
        RuntimeMenu menu = menuWithItemBinding("Click to close", "Static");

        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(menu, DECLARED_TYPES));
    }

    @Test
    void multiHopChainValidatesEachHopAgainstTheReturnTypeOfThePreviousOne() {
        RuntimeMenu menu = menuWithItemBinding("$player.getFriend.getName$", "Static");

        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(menu, DECLARED_TYPES));
    }

    @Test
    void sectionLevelBindingsAreValidatedToo() {
        RuntimeMenuItem item = new RuntimeMenuItem(1, 0, null, null, 1, null, null,
                MenuDisplayMode.NORMAL, null, null, List.of(), List.of(), List.of());
        List<KnkVariableBinding> sectionBindings = List.of(
                new KnkVariableBinding(1, "Name", 0, "$player.getBogusMethod$", "Static", null));
        RuntimeMenuSection section = new RuntimeMenuSection(1, "Header", MenuSectionKind.STATIC_BUTTONS, 0, 0,
                9, 1, MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT,
                MenuOverflowMode.HIDE, MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, false,
                List.of(item), sectionBindings);
        RuntimeMenu menu = new RuntimeMenu("test.menu", "Test Menu", 3, MenuGrowth.STATIC, null, List.of(section));

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class,
                () -> MenuDefinitionValidator.validate(menu, DECLARED_TYPES));
        assertTrue(ex.getMessage().contains("getBogusMethod"));
    }

    private static RuntimeMenu menuWithItemBinding(String expression, String refreshPolicy) {
        List<KnkVariableBinding> itemBindings = List.of(
                new KnkVariableBinding(1, "Name", 0, expression, refreshPolicy, null));
        RuntimeMenuItem item = new RuntimeMenuItem(1, 0, null, null, 1, null, null,
                MenuDisplayMode.NORMAL, null, null, itemBindings, List.of(), List.of());
        RuntimeMenuSection section = new RuntimeMenuSection(1, "Content", MenuSectionKind.CONTENT_GRID, 0, 0,
                9, 1, MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT,
                MenuOverflowMode.HIDE, MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, false,
                List.of(item), List.of());
        return new RuntimeMenu("test.menu", "Test Menu", 3, MenuGrowth.STATIC, null, List.of(section));
    }

    /** A tiny local stand-in for {@code org.bukkit.entity.Player} so this test stays Bukkit-free. */
    public static final class FakePlayer {
        public String getName() {
            return "Steve";
        }

        public FakePlayer getFriend() {
            return this;
        }
    }
}
