package net.knightsandkings.knk.core.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-render layout validation (ARCHITECTURE_DESIGN.md §5.1, reconciliation
 * bug #8): confirms every section actually fits within the menu's declared
 * bounds and that no two sections' occupied slots collide. Unlike bug #8 -
 * where a check existed but the guard never actually fired - this validator
 * is exercised directly by unit tests asserting it *does* reject an
 * out-of-bounds/colliding layout, not just that it compiles.
 */
final class MenuLayoutValidator {

    private MenuLayoutValidator() {
    }

    static void validate(RuntimeMenu menu) {
        List<String> errors = new ArrayList<>();
        int totalSlots = menu.totalSlots();

        if (menu.height() < 1 || menu.height() > 6) {
            errors.add("menu height " + menu.height() + " is out of bounds [1, 6]");
        }

        Map<Integer, Integer> slotOwnerBySectionId = new HashMap<>();

        for (RuntimeMenuSection section : menu.sections()) {
            if (section.width() < 1 || section.height() < 1) {
                errors.add("section '" + section.name() + "' has non-positive dimensions ("
                        + section.width() + "x" + section.height() + ")");
                continue;
            }

            if (!MenuSlotCalculator.fitsExactly(
                    section.displaySlot(), totalSlots, section.width(), section.height(),
                    section.alignVertical(), section.alignHorizontal())) {
                errors.add("section '" + section.name() + "' (displaySlot=" + section.displaySlot()
                        + ", " + section.width() + "x" + section.height() + ", align="
                        + section.alignVertical() + "/" + section.alignHorizontal()
                        + ") doesn't fit within the menu's " + totalSlots + " slots");
            }

            Set<Integer> footprint = new LinkedHashSet<>(MenuSlotCalculator.calculateSlots(
                    section.displaySlot(), totalSlots, section.width(), section.height(),
                    section.alignVertical(), section.alignHorizontal()));

            for (RuntimeMenuItem item : section.items()) {
                if (item.slotOverride() == null) {
                    continue;
                }
                int slot = item.slotOverride();
                if (slot < 0 || slot >= totalSlots) {
                    errors.add("item (id " + item.id() + ") in section '" + section.name()
                            + "' has slotOverride " + slot + " outside the menu's " + totalSlots + " slots");
                } else {
                    footprint.add(slot);
                }
            }

            for (int slot : footprint) {
                Integer existingOwner = slotOwnerBySectionId.get(slot);
                if (existingOwner != null && !existingOwner.equals(section.id())) {
                    errors.add("slot " + slot + " is occupied by both section id " + existingOwner
                            + " and section '" + section.name() + "' (id " + section.id() + ")");
                } else {
                    slotOwnerBySectionId.put(slot, section.id());
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new MenuAssemblyException(
                    "Menu '" + menu.key() + "' failed layout validation:\n - " + String.join("\n - ", errors)
            );
        }
    }
}
