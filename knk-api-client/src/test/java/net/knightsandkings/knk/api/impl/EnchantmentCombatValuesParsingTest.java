package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.EnchantmentCombatValuesDto;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#151: the combat values in GET /EnchantmentDefinitions. */
class EnchantmentCombatValuesParsingTest {

    private static final String RESPONSE = """
            [
              {"id":1,"key":"minecraft:sharpness","displayName":"Sharpness","description":"","isCustom":false,"maxLevel":5,
               "minecraftEnchantmentRefId":4,"baseEnchantmentRef":{"id":4,"namespaceKey":"minecraft:sharpness"},
               "defaultForBlueprints":[],"abilityDefinition":null,
               "procChancePercent":null,"procChancePerLevelPercent":null,"cooldownEnabled":null,"cooldownSeconds":null,
               "effectDurationSeconds":null,"levelMultiplier":null},
              {"id":7,"key":"freeze","displayName":"Freeze","description":"x","isCustom":true,"maxLevel":3,
               "defaultForBlueprints":[{"id":2,"name":"Ice sword"}],
               "abilityDefinition":{"id":3,"enchantmentDefinitionId":7,"abilityKey":"freeze","runtimeConfigJson":"{}"},
               "procChancePercent":10,"procChancePerLevelPercent":5,"cooldownEnabled":true,"cooldownSeconds":20,
               "effectDurationSeconds":1.5,"levelMultiplier":0.5},
              {"id":8,"key":"poison","displayName":"Poison","isCustom":true,"maxLevel":3},
              {"id":9,"key":"flash_chaos","displayName":"Flash Chaos","isCustom":true,"maxLevel":1,
               "procChancePercent":null,"procChancePerLevelPercent":null,"cooldownEnabled":true,"cooldownSeconds":90,
               "effectDurationSeconds":5,"levelMultiplier":0,"effectLevel":2,"secondaryEffectDurationSeconds":12.5,
               "areaDamage":60,"gateDamage":45.5,"damageRadiusBlocks":3,"effectRadiusBlocks":5},
              {"id":10,"key":"cleaving","displayName":"Cleaving","isCustom":true,"maxLevel":4,"cooldownEnabled":true,
               "cooldownSeconds":60,"gateDamageBonusPerLevel":0.25,"strikeMultiplier":5,"strikeMultiplierPerLevel":1,
               "cooldownPerLevelSeconds":-10}
            ]
            """;

    @Test
    void keepsTheCustomDefinitionsWithTheirValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        List<EnchantmentCombatValuesDto> dtos = mapper.readValue(RESPONSE, new TypeReference<>() {});

        List<EnchantmentTuningOverride> overrides = EnchantmentDefinitionsQueryApiImpl.toOverrides(dtos);

        assertEquals(4, overrides.size());
        EnchantmentTuningOverride freeze = overrides.get(0);
        assertEquals("freeze", freeze.key());
        assertEquals(10.0d, freeze.procChancePercent());
        assertEquals(5.0d, freeze.procChancePerLevelPercent());
        assertEquals(Boolean.TRUE, freeze.cooldownEnabled());
        assertEquals(20, freeze.cooldownSeconds());
        assertEquals(1.5d, freeze.effectDurationSeconds());
        assertEquals(0.5d, freeze.levelMultiplier());

        EnchantmentTuningOverride poison = overrides.get(1);
        assertEquals("poison", poison.key());
        assertNull(poison.procChancePercent());
        assertTrue(poison.isEmpty());
        assertNull(freeze.effectLevel());
        assertNull(freeze.secondaryEffectDurationSeconds());

        // PandiO/knk-workspace#201: Flash Chaos's Slowness level and Nausea duration.
        EnchantmentTuningOverride flash = overrides.get(2);
        assertEquals(2, flash.effectLevel());
        assertEquals(12.5d, flash.secondaryEffectDurationSeconds());
        assertEquals(5.0d, flash.effectDurationSeconds());

        // PandiO/knk-workspace#223: Chaos / Flash Chaos area damage, gate damage and radii.
        assertEquals(60.0d, flash.areaDamage());
        assertEquals(45.5d, flash.gateDamage());
        assertEquals(3.0d, flash.damageRadiusBlocks());
        assertEquals(5.0d, flash.effectRadiusBlocks());
        assertNull(freeze.areaDamage());
        assertNull(freeze.effectRadiusBlocks());

        // PandiO/knk-workspace#259: Cleaving's gate bonus and charged strike.
        EnchantmentTuningOverride cleaving = overrides.get(3);
        assertEquals(0.25d, cleaving.gateDamageBonusPerLevel());
        assertEquals(5.0d, cleaving.strikeMultiplier());
        assertEquals(1.0d, cleaving.strikeMultiplierPerLevel());
        assertEquals(-10, cleaving.cooldownPerLevelSeconds());
        assertNull(flash.strikeMultiplier());
    }

    @Test
    void anEmptyResponseGivesNoOverrides() {
        assertTrue(EnchantmentDefinitionsQueryApiImpl.toOverrides(null).isEmpty());
    }
}
