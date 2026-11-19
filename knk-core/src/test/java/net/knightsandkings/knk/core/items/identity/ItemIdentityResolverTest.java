package net.knightsandkings.knk.core.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution.Kind;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution.UnresolvedReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#141: instance tag → blueprint tag → vanilla match, and the retag rule. */
class ItemIdentityResolverTest {

    private static final MaterialBlueprintMap MAP = MaterialBlueprintMap.of(List.of(
            new MaterialBlueprintMap.Entry("minecraft:oak_log", 10, true),
            new MaterialBlueprintMap.Entry("BREAD", 20, false)));

    private static ItemIdentityFacts facts(String key, Long instance, Integer tag, boolean name, boolean lore, boolean other, boolean plain) {
        return new ItemIdentityFacts(key, instance, tag, name, lore, other, plain);
    }

    @Test
    void plainVanillaItem_resolvesToItsMaterialsBlueprint_withoutAnyChange() {
        ItemIdentityResolution r = ItemIdentityResolver.resolve(ItemIdentityFacts.plain("minecraft:oak_log"), MAP);
        assertEquals(Kind.VANILLA_MATCH, r.kind());
        assertEquals(10, r.blueprintId());
        assertFalse(r.stripBlueprintTag());
        assertEquals("vanilla_match", r.outcome());
    }

    @Test
    void vanillaStateLikeDamageOrEnchantments_stillResolvesByMaterial() {
        ItemIdentityResolution r = ItemIdentityResolver.resolve(facts("minecraft:bread", null, null, false, false, false, false), MAP);
        assertEquals(Kind.VANILLA_MATCH, r.kind());
        assertEquals(20, r.blueprintId());
    }

    @Test
    void instanceTag_winsOverEverything() {
        ItemIdentityResolution r = ItemIdentityResolver.resolve(facts("minecraft:diamond_sword", 77L, 5, true, true, true, false), MAP);
        assertEquals(Kind.INSTANCE, r.kind());
        assertEquals(77L, r.instanceId());
        assertEquals(5, r.blueprintId());
        assertEquals("instance_tag", r.outcome());
    }

    @Test
    void blueprintTag_onACustomisedItem_resolvesToThatBlueprint_evenWithNameAndLore() {
        ItemIdentityResolution r = ItemIdentityResolver.resolve(facts("minecraft:bread", null, 33, true, true, true, false), MAP);
        assertEquals(Kind.BLUEPRINT_TAG, r.kind());
        assertEquals(33, r.blueprintId());
        assertFalse(r.stripBlueprintTag());
        assertEquals("blueprint_tag", r.outcome());
    }

    @Test
    void retagRule_aPlainItemWithABlueprintTag_isNormalisedToTheVanillaMatch() {
        ItemIdentityResolution sameBlueprint = ItemIdentityResolver.resolve(facts("minecraft:oak_log", null, 10, false, false, false, true), MAP);
        assertTrue(sameBlueprint.stripBlueprintTag());
        assertEquals(10, sameBlueprint.blueprintId());
        assertEquals("normalized", sameBlueprint.outcome());

        ItemIdentityResolution otherBlueprint = ItemIdentityResolver.resolve(facts("minecraft:oak_log", null, 99, false, false, false, true), MAP);
        assertTrue(otherBlueprint.stripBlueprintTag());
        assertEquals(10, otherBlueprint.blueprintId());
    }

    @Test
    void retagRule_withoutAMatch_stillStripsButKeepsTheTaggedId() {
        ItemIdentityResolution r = ItemIdentityResolver.resolve(facts("minecraft:stone", null, 44, false, false, false, true), MAP);
        assertTrue(r.stripBlueprintTag());
        assertEquals(44, r.blueprintId());
    }

    @Test
    void untaggedCustomItems_areUnresolved_withTheReason() {
        assertEquals(UnresolvedReason.CUSTOM_NAME,
                ItemIdentityResolver.resolve(facts("minecraft:bread", null, null, true, true, false, false), MAP).reason());
        assertEquals(UnresolvedReason.CUSTOM_LORE,
                ItemIdentityResolver.resolve(facts("minecraft:bread", null, null, false, true, false, false), MAP).reason());
        ItemIdentityResolution other = ItemIdentityResolver.resolve(facts("minecraft:bread", null, null, false, false, true, false), MAP);
        assertEquals(UnresolvedReason.OTHER_DATA, other.reason());
        assertEquals("unresolved_other_data", other.outcome());
        assertNull(other.blueprintId());
        assertFalse(other.resolved());
    }

    @Test
    void materialWithoutABlueprint_isUnresolvedNoMatch() {
        ItemIdentityResolution r = ItemIdentityResolver.resolve(ItemIdentityFacts.plain("minecraft:stone"), MAP);
        assertEquals(UnresolvedReason.NO_MATCH, r.reason());
        assertEquals(UnresolvedReason.NO_MATCH, ItemIdentityResolver.resolve(ItemIdentityFacts.plain("minecraft:stone"), null).reason());
    }

    @Test
    void noMaterial_isUnresolved() {
        assertEquals(UnresolvedReason.NO_MATERIAL, ItemIdentityResolver.resolve(ItemIdentityFacts.plain(" "), MAP).reason());
        assertEquals(UnresolvedReason.NO_MATERIAL, ItemIdentityResolver.resolve(null, MAP).reason());
    }

    @Test
    void stampRule_onlyNonPlainItemsGetABlueprintTag() {
        assertFalse(ItemIdentityResolver.shouldStampBlueprintTag(true));
        assertTrue(ItemIdentityResolver.shouldStampBlueprintTag(false));
    }

    /**
     * "Stacking behaves exactly as in vanilla": two items that are equal apart from the blueprint tag always end up
     * with the same tag after stamping and normalising, so the tag never splits a stack.
     */
    @Test
    void stackingInvariant_tagIsAFunctionOfTheRestOfTheItem() {
        for (boolean plain : new boolean[]{true, false}) {
            Integer stampedTag = ItemIdentityResolver.shouldStampBlueprintTag(plain) ? 10 : null;
            Integer afterNormalising = stampedTag == null ? null
                    : ItemIdentityResolver.resolve(facts("minecraft:oak_log", null, stampedTag, false, false, false, plain), MAP)
                            .stripBlueprintTag() ? null : stampedTag;
            Integer legacyTagged = ItemIdentityResolver.resolve(facts("minecraft:oak_log", null, 10, false, false, false, plain), MAP)
                    .stripBlueprintTag() ? null : 10;
            assertEquals(plain ? null : 10, afterNormalising);
            assertEquals(plain ? null : 10, legacyTagged);
        }
    }
}
