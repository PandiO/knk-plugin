package net.knightsandkings.knk.core.items.identity;

/**
 * The blueprint (and, for a unique item, the instance) an item resolves to, how, and whether the item must be
 * normalised ({@link #stripBlueprintTag}).
 */
public record ItemIdentityResolution(Kind kind, Integer blueprintId, Long instanceId, UnresolvedReason reason, boolean stripBlueprintTag) {

    public enum Kind {
        /** {@code knk_item_instance}: a unique item; its blueprint is the instance's (from the tag when present). */
        INSTANCE,
        /** {@code knk_blueprint}: an item a blueprint made that differs from the plain vanilla item. */
        BLUEPRINT_TAG,
        /** No tag; a vanilla item (state such as damage or enchantments allowed) resolved by its material. */
        VANILLA_MATCH,
        /** Left alone and reported. */
        UNRESOLVED
    }

    public enum UnresolvedReason {
        /** Untagged with a custom name (renamed, or another plugin's item). */
        CUSTOM_NAME,
        /** Untagged with lore (custom-lored, or another plugin's item). */
        CUSTOM_LORE,
        /** Untagged with another plugin's (or an untagged K&amp;K) persistent data. */
        OTHER_DATA,
        /** A vanilla item whose material has no blueprint yet (shadow generation hasn't run for it). */
        NO_MATCH,
        /** No material (air or unknown). */
        NO_MATERIAL
    }

    static ItemIdentityResolution instance(Long instanceId, Integer blueprintTag) {
        return new ItemIdentityResolution(Kind.INSTANCE, blueprintTag, instanceId, null, false);
    }

    static ItemIdentityResolution blueprint(int blueprintId, boolean strip) {
        return new ItemIdentityResolution(strip ? Kind.VANILLA_MATCH : Kind.BLUEPRINT_TAG, blueprintId, null, null, strip);
    }

    static ItemIdentityResolution vanilla(int blueprintId) {
        return new ItemIdentityResolution(Kind.VANILLA_MATCH, blueprintId, null, null, false);
    }

    static ItemIdentityResolution unresolved(UnresolvedReason reason) {
        return new ItemIdentityResolution(Kind.UNRESOLVED, null, null, reason, false);
    }

    public boolean resolved() {
        return kind != Kind.UNRESOLVED;
    }

    /**
     * The outcome name in the conversion counts: {@code instance_tag}, {@code blueprint_tag}, {@code vanilla_match},
     * {@code normalized} (a stale blueprint tag was removed) or {@code unresolved_<reason>}.
     */
    public String outcome() {
        if (stripBlueprintTag) {
            return "normalized";
        }
        return switch (kind) {
            case INSTANCE -> "instance_tag";
            case BLUEPRINT_TAG -> "blueprint_tag";
            case VANILLA_MATCH -> "vanilla_match";
            case UNRESOLVED -> "unresolved_" + reason.name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
