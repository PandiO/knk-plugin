package net.knightsandkings.knk.api.impl.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shared custom-enchantment lore pipeline, against the real lore repository. */
class CustomEnchantmentLoreTest {

    private final LocalEnchantmentRepositoryImpl repository = new LocalEnchantmentRepositoryImpl();

    // What ItemBlueprintBukkitMapper builds: description, then Grade, then Origin.
    private static final List<String> BLUEPRINT_LORE = List.of("§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)");

    @Test
    void apply_PutsTheEnchantmentAboveDescriptionGradeAndOrigin() {
        List<String> lore = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 2);

        assertEquals(List.of("§7Poison II", "§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), lore);
    }

    @Test
    void apply_SecondEnchantmentJoinsTheBlockAtTheTop() {
        List<String> once = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 2);
        List<String> twice = CustomEnchantmentLore.apply(repository, once, "wither", 1);

        assertEquals(List.of("§7Poison II", "§7Wither I", "§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), twice);
    }

    @Test
    void apply_UpgradeReplacesInPlace() {
        List<String> once = CustomEnchantmentLore.apply(repository, BLUEPRINT_LORE, "poison", 1);
        List<String> upgraded = CustomEnchantmentLore.apply(repository, once, "poison", 3);

        assertEquals(List.of("§7Poison III", "§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), upgraded);
    }

    @Test
    void apply_RepairsLoreWhereAnEnchantmentWasAppendedAtTheBottom() {
        // What KNG-5 books produced before this fix.
        List<String> broken = List.of("§7Poison II", "§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)", "§7Wither I");

        List<String> lore = CustomEnchantmentLore.apply(repository, broken, "freeze", 1);

        assertEquals(List.of("§7Poison II", "§7Wither I", "§7Freeze I", "§7A fine blade", "§l§bGrade: ★★★", "§7Origin: Cinix (Town)"), lore);
    }

    @Test
    void apply_OnItemWithoutLore() {
        assertEquals(List.of("§7Poison I"), CustomEnchantmentLore.apply(repository, null, "poison", 1));
        assertEquals(List.of("§7Poison I"), CustomEnchantmentLore.apply(repository, List.of(), "poison", 1));
    }

    @Test
    void enchantmentsFirst_LeavesLoreWithoutEnchantmentsAlone() {
        assertEquals(BLUEPRINT_LORE, CustomEnchantmentLore.enchantmentsFirst(repository, BLUEPRINT_LORE));
        assertEquals(List.of(), CustomEnchantmentLore.enchantmentsFirst(repository, null));
    }
}
