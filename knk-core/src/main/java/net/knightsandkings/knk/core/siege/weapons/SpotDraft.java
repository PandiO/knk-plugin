package net.knightsandkings.knk.core.siege.weapons;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One weapon spot as the in-game spot task holds it before it is created (SIEGE_WEAPONS § 4, A02-A03): the bounds and
 * the WorldGuard region they came from, the facing (pre-filled from the admin's look direction, W5, until the admin
 * overrides it) and the size class worked out from both (W70). Immutable; every change returns a new draft.
 *
 * @param wgRegionId       the WorldGuard region the bounds came from, or null for a WorldEdit selection
 * @param facingOverridden true once the admin set the facing; a new capture then keeps it instead of the look direction
 */
public record SpotDraft(SpotBounds bounds, String wgRegionId, SpotFacing facing, boolean facingOverridden,
                        SpotSiteCheck.Result site) {

    /** A fresh capture: the facing follows the admin's look direction unless they set one before. */
    public static SpotDraft capture(SpotBounds bounds, String wgRegionId, float lookYaw, SpotFacing overrideOrNull,
                                    SpotSiteCheck.Result site) {
        return overrideOrNull != null
                ? new SpotDraft(bounds, wgRegionId, overrideOrNull, true, site)
                : new SpotDraft(bounds, wgRegionId, SpotFacing.fromYaw(lookYaw), false, site);
    }

    /** W5 override: the size class is worked out again for the new facing (W70). */
    public SpotDraft withFacing(SpotFacing newFacing) {
        return new SpotDraft(bounds, wgRegionId, newFacing, true, site);
    }

    public Optional<SpotSizeClass> sizeClass() {
        return SpotSizing.classFor(bounds, facing);
    }

    /** Why the spot can't be created in this facing (A03), or empty. */
    public Optional<String> refusal() {
        return SpotSizing.refusal(bounds, facing);
    }

    /** The lines the task shows after a capture or a facing change. */
    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add("Bounds: " + bounds.describe() + (wgRegionId != null ? " (region " + wgRegionId + ")" : ""));
        lines.add("Facing: " + facing.displayName() + (facingOverridden ? " (set by you)" : " (your look direction)"));
        lines.add("Footprint: " + bounds.widthAcross(facing) + " wide × " + bounds.lengthAlong(facing) + " long × "
                + bounds.sizeY() + " high");
        sizeClass().ifPresent(c -> lines.add("Size class: " + c.apiName() + " (needs " + c.describe() + ")"));
        return lines;
    }
}
