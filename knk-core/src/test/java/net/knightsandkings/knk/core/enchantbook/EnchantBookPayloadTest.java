package net.knightsandkings.knk.core.enchantbook;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EnchantBookPayloadTest {

    @Test
    void encodeDecode_RoundTripsVanillaAndCustom() {
        EnchantBookPayload vanilla = EnchantBookPayload.vanilla("minecraft:sharpness", 3);
        EnchantBookPayload custom = EnchantBookPayload.custom("poison", 2);

        assertEquals("vanilla;minecraft:sharpness;3", vanilla.encode());
        assertEquals("custom;poison;2", custom.encode());
        assertEquals(Optional.of(vanilla), EnchantBookPayload.decode(vanilla.encode()));
        assertEquals(Optional.of(custom), EnchantBookPayload.decode(custom.encode()));
    }

    @Test
    void constructor_NormalizesKey() {
        assertEquals("minecraft:sharpness", EnchantBookPayload.vanilla("  Minecraft:Sharpness ", 1).enchantmentKey());
    }

    @Test
    void constructor_RejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> EnchantBookPayload.custom("poison", 0));
        assertThrows(IllegalArgumentException.class, () -> EnchantBookPayload.custom(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> EnchantBookPayload.custom("a;b", 1));
        assertThrows(NullPointerException.class, () -> new EnchantBookPayload(null, "poison", 1));
    }

    @Test
    void decode_IsLenient() {
        assertTrue(EnchantBookPayload.decode(null).isEmpty());
        assertTrue(EnchantBookPayload.decode("").isEmpty());
        assertTrue(EnchantBookPayload.decode("custom;poison").isEmpty());
        assertTrue(EnchantBookPayload.decode("custom;poison;2;extra").isEmpty());
        assertTrue(EnchantBookPayload.decode("magic;poison;2").isEmpty());
        assertTrue(EnchantBookPayload.decode("custom;poison;two").isEmpty());
        assertTrue(EnchantBookPayload.decode("custom;;2").isEmpty());
        assertTrue(EnchantBookPayload.decode("custom;poison;0").isEmpty());
        assertEquals(Optional.of(EnchantBookPayload.custom("poison", 2)), EnchantBookPayload.decode(" CUSTOM ; poison ; 2 "));
    }
}
