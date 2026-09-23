package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link RuntimeMenuItem#isVisibleTo}/{@link RuntimeMenuItem#isActionAllowedFor}
 * (IMPLEMENTATION_PLAN.md Phase 4): the two permission checks DESIGN_REVIEW.md §2.4
 * requires to be independent - "usually the same node, but not always" (e.g. a
 * preview visible to everyone, actionable only by the owner). Kept Bukkit-free by
 * taking a {@code Predicate<String>} rather than a live {@code Permissible} -
 * knk-paper supplies {@code player::hasPermission} at the call site.
 */
class RuntimeMenuItemTest {

    private static RuntimeMenuItem item(String visibilityPermission, String actionPermission) {
        return new RuntimeMenuItem(1, 0, null, null, 1, null, null, MenuDisplayMode.NORMAL,
                visibilityPermission, actionPermission, List.of(), List.of(), List.of());
    }

    @Test
    void nullVisibilityPermissionIsAlwaysVisible() {
        RuntimeMenuItem item = item(null, null);

        assertTrue(item.isVisibleTo(node -> false));
    }

    @Test
    void blankVisibilityPermissionIsAlwaysVisible() {
        RuntimeMenuItem item = item("   ", null);

        assertTrue(item.isVisibleTo(node -> false));
    }

    @Test
    void nonNullVisibilityPermissionDelegatesToChecker() {
        RuntimeMenuItem item = item("knk.menu.example.visible", null);

        assertFalse(item.isVisibleTo(node -> false));
        assertTrue(item.isVisibleTo(node -> node.equals("knk.menu.example.visible")));
    }

    @Test
    void nullActionPermissionIsAlwaysAllowed() {
        RuntimeMenuItem item = item(null, null);

        assertTrue(item.isActionAllowedFor(node -> false));
    }

    @Test
    void nonNullActionPermissionDelegatesToChecker() {
        RuntimeMenuItem item = item(null, "knk.menu.example.act");

        assertFalse(item.isActionAllowedFor(node -> false));
        assertTrue(item.isActionAllowedFor(node -> node.equals("knk.menu.example.act")));
    }

    /**
     * DESIGN_REVIEW.md §2.4's exact example: a preview visible to everyone but
     * actionable only by the owner - the two checks must be independent, not
     * both driven by one property.
     */
    @Test
    void visibilityAndActionPermissionAreIndependent() {
        RuntimeMenuItem previewVisibleToAllActionableByOwnerOnly = item(null, "knk.menu.example.owner");
        Predicate<String> nonOwner = node -> false;

        assertTrue(previewVisibleToAllActionableByOwnerOnly.isVisibleTo(nonOwner));
        assertFalse(previewVisibleToAllActionableByOwnerOnly.isActionAllowedFor(nonOwner));
    }
}
