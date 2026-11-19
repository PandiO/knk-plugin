package net.knightsandkings.knk.core.siege.weapons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * W70 and SIEGE_WEAPONS § 4.3: a spot's size class is the largest class whose footprint fits the selection, measured
 * as width across the facing × length along it × height, and is worked out again whenever the facing changes. A
 * selection smaller than the small class in that facing has no class and is refused (A03).
 */
public final class SpotSizing {

    private SpotSizing() {
    }

    /** The largest class that fits, or empty when even the small class doesn't. */
    public static Optional<SpotSizeClass> classFor(SpotBounds bounds, SpotFacing facing) {
        SpotSizeClass best = null;
        for (SpotSizeClass sizeClass : SpotSizeClass.values()) {
            if (sizeClass.fits(bounds.widthAcross(facing), bounds.lengthAlong(facing), bounds.sizeY())) {
                best = sizeClass;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The facings in which the selection holds at least the small class (for the refusal's hint). */
    public static List<SpotFacing> facingsThatFit(SpotBounds bounds) {
        List<SpotFacing> facings = new ArrayList<>();
        for (SpotFacing facing : SpotFacing.values()) {
            if (classFor(bounds, facing).isPresent()) facings.add(facing);
        }
        return facings;
    }

    /**
     * Why the selection is refused in this facing, or empty when it holds a class. Names the measured footprint, the
     * small class's minimum and, when turning would help, the facings that fit.
     */
    public static Optional<String> refusal(SpotBounds bounds, SpotFacing facing) {
        if (classFor(bounds, facing).isPresent()) return Optional.empty();
        StringBuilder text = new StringBuilder("Too small for a weapon facing ").append(facing.displayName().toLowerCase(Locale.ROOT))
                .append(": the selection is ").append(bounds.widthAcross(facing)).append(" wide × ")
                .append(bounds.lengthAlong(facing)).append(" long × ").append(bounds.sizeY())
                .append(" high, the small class needs at least ").append(SpotSizeClass.SMALL.describe()).append('.');
        List<SpotFacing> fit = facingsThatFit(bounds);
        if (!fit.isEmpty()) {
            text.append(" It fits facing ").append(String.join(" or ",
                    fit.stream().map(f -> f.displayName().toLowerCase(Locale.ROOT)).toList())).append('.');
        }
        return Optional.of(text.toString());
    }
}
