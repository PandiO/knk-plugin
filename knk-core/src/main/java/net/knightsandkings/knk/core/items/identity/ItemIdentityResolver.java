package net.knightsandkings.knk.core.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution.UnresolvedReason;

/**
 * The one item resolver (production DESIGN §8, PandiO/knk-workspace#141): <b>instance tag → blueprint tag → vanilla
 * match</b>. Items that don't resolve (custom-named, custom-lored, another plugin's data, a material without a
 * blueprint) are reported and left alone.
 *
 * <h2>Retag rule (agent decision on #141): the plain vanilla item is the canonical form</h2>
 * A blueprint tag is only ever on an item that differs from the plain vanilla item of its material. The plain item
 * itself carries no tag and resolves through the vanilla match. So the tag is never the only difference between two
 * stacks, and tagged and untagged stacks of the same item can't exist side by side: stacking is exactly vanilla's.
 * <ol>
 *   <li>{@link #shouldStampBlueprintTag}: the assembler stamps a blueprint item only when it is not plain.</li>
 *   <li>{@link #resolve}: an item that is plain apart from a blueprint tag (an older build, or a blueprint edited
 *       to plain since) is normalised ({@link ItemIdentityResolution#stripBlueprintTag()}): the tag is removed and the
 *       item resolves through its material's vanilla match, so it merges with plain stacks again. A blueprint whose
 *       items look exactly like the vanilla item is, for identity, the vanilla item.</li>
 * </ol>
 */
public final class ItemIdentityResolver {

    private ItemIdentityResolver() {
    }

    /** Whether a blueprint item should carry {@code knk_blueprint}: only when it differs from the plain vanilla item. */
    public static boolean shouldStampBlueprintTag(boolean plain) {
        return !plain;
    }

    public static ItemIdentityResolution resolve(ItemIdentityFacts facts, MaterialBlueprintMap map) {
        if (facts == null || MaterialBlueprintMap.normalize(facts.materialKey()) == null) {
            return ItemIdentityResolution.unresolved(UnresolvedReason.NO_MATERIAL);
        }
        MaterialBlueprintMap effective = map != null ? map : MaterialBlueprintMap.EMPTY;

        // 1. Instance tag: a unique item.
        if (facts.instanceId() != null) {
            return ItemIdentityResolution.instance(facts.instanceId(), facts.blueprintTag());
        }

        Integer vanillaMatch = effective.blueprintIdOf(facts.materialKey());

        // 2. Blueprint tag; a plain item never keeps one (retag rule): it is normalised to the plain vanilla item.
        if (facts.blueprintTag() != null && facts.blueprintTag() > 0) {
            if (facts.plainWithoutBlueprintTag()) {
                return ItemIdentityResolution.blueprint(vanillaMatch != null ? vanillaMatch : facts.blueprintTag(), true);
            }
            return ItemIdentityResolution.blueprint(facts.blueprintTag(), false);
        }

        // 3. Vanilla match, only for an item no one customised.
        if (facts.customName()) {
            return ItemIdentityResolution.unresolved(UnresolvedReason.CUSTOM_NAME);
        }
        if (facts.customLore()) {
            return ItemIdentityResolution.unresolved(UnresolvedReason.CUSTOM_LORE);
        }
        if (facts.otherData()) {
            return ItemIdentityResolution.unresolved(UnresolvedReason.OTHER_DATA);
        }
        return vanillaMatch != null
                ? ItemIdentityResolution.vanilla(vanillaMatch)
                : ItemIdentityResolution.unresolved(UnresolvedReason.NO_MATCH);
    }
}
