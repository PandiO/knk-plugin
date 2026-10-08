package net.knightsandkings.knk.api.impl.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shared custom-enchantment lore pipeline, against the real lore repository. */
class CustomEnchantmentLoreTest {

    private final LocalEnchantmentRepositoryImpl repository = new LocalEnchantmentRepositoryImpl();

    // What ItemBlueprintBukkitMapper builds: description, spacer, Grade, then Origin.
    private static final List<String> BLUEPRINT_LORE = List.of("§7A fine blade", "", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)");

    @Test
    void apply_PutsTheEnchantmentAboveDescriptionGradeAndOrigin() {
        List<String> lore = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 2);

        assertEquals(List.of("§7Poison II", "", "§7A fine blade", "", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), lore);
    }

    @Test
    void aDarkGrayDescription_isNeverTakenForAnEnchantment_andKeepsItsColorAndOrder() {
        // Descriptions default to dark gray (§8) while enchantment lines are §7: detection ignores colors entirely, so
        // neither can be mistaken for the other, and re-applying/upgrading leaves the description lines untouched.
        List<String> darkGray = List.of("§8Forged in the last fire of a fallen dojo.", "§8Its edge never cools.", "", "§l§bGrade: ★★★");

        List<String> once = CustomEnchantmentLore.apply(repository, darkGray, "poison", 2);
        List<String> upgraded = CustomEnchantmentLore.apply(repository, once, "poison", 3);

        assertEquals(List.of("§7Poison II", "", "§8Forged in the last fire of a fallen dojo.", "§8Its edge never cools.", "", "§l§bGrade: ★★★"), once);
        assertEquals(List.of("§7Poison III", "", "§8Forged in the last fire of a fallen dojo.", "§8Its edge never cools.", "", "§l§bGrade: ★★★"), upgraded);
        assertEquals(java.util.Map.of("poison", 3), repository.getEnchantments(upgraded).join());
    }

    @Test
    void apply_SecondEnchantmentJoinsTheBlockAtTheTop() {
        List<String> once = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 2);
        List<String> twice = CustomEnchantmentLore.apply(repository, once, "wither", 1);

        assertEquals(List.of("§7Poison II", "§7Wither I", "", "§7A fine blade", "", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), twice);
    }

    @Test
    void apply_UpgradeReplacesInPlace() {
        List<String> once = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 1);
        List<String> upgraded = CustomEnchantmentLore.apply(repository, once, "poison", 3);

        assertEquals(List.of("§7Poison III", "", "§7A fine blade", "", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), upgraded);
    }

    @Test
    void apply_RepairsLoreWhereAnEnchantmentWasAppendedAtTheBottom() {
        // What KNG-5 books produced before this fix.
        List<String> broken = List.of("§7Poison II", "§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)", "§7Wither I");

        List<String> lore = CustomEnchantmentLore.apply(repository, broken, "freeze", 1);

        assertEquals(List.of("§7Poison II", "§7Wither I", "§7Freeze I", "", "§7A fine blade", "", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), lore);
    }

    @Test
    void remove_TheLastEnchantmentTakesItsSpacerWithIt() {
        List<String> enchanted = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 2);

        assertEquals(BLUEPRINT_LORE, CustomEnchantmentLore.remove(repository, enchanted, "poison"),
                "no blank line left at the top of the lore");
    }

    @Test
    void remove_OneOfTwoKeepsTheBlockAndTheSpacer() {
        List<String> two = CustomEnchantmentLore.apply(repository,
                CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 2), "wither", 1);

        assertEquals(List.of("§7Wither I", "", "§7A fine blade", "", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"),
                CustomEnchantmentLore.remove(repository, two, "poison"));
    }

    @Test
    void remove_WhatIsNotThereChangesNothing_andNoLoreStaysEmpty() {
        assertEquals(BLUEPRINT_LORE, CustomEnchantmentLore.remove(repository, BLUEPRINT_LORE, "poison"));
        assertEquals(List.of(), CustomEnchantmentLore.remove(repository, null, "poison"));
    }

    @Test
    void apply_OnItemWithoutLore() {
        assertEquals(List.of("§7Poison I"), CustomEnchantmentLore.apply(repository, null, "poison", 1));
        assertEquals(List.of("§7Poison I"), CustomEnchantmentLore.apply(repository, List.of(), "poison", 1));
    }

    @Test
    void enchantmentsFirst_NormalizesLoreWithoutEnchantments() {
        assertEquals(BLUEPRINT_LORE, CustomEnchantmentLore.enchantmentsFirst(repository, BLUEPRINT_LORE));
        assertEquals(List.of(), CustomEnchantmentLore.enchantmentsFirst(repository, null));
    }
}
