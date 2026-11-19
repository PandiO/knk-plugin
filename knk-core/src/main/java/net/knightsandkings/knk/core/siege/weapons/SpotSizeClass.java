package net.knightsandkings.knk.core.siege.weapons;

/**
 * The three spot size classes (SIEGE_WEAPONS § 3, W3): the smallest footprint a weapon of the class needs, as width
 * across its facing × length along it × height. A larger spot holds a smaller weapon. The sizes are the spec's
 * estimates from the reference designs and get confirmed when the schematics are built; the API's
 * {@code SiegeWeaponSpotSizing} uses the same numbers.
 */
public enum SpotSizeClass {
    SMALL("Small", 7, 9, 5),
    MEDIUM("Medium", 7, 9, 8),
    LARGE("Large", 9, 15, 16);

    private final String apiName;
    private final int width;
    private final int length;
    private final int height;

    SpotSizeClass(String apiName, int width, int length, int height) {
        this.apiName = apiName;
        this.width = width;
        this.length = length;
        this.height = height;
    }

    /** The value the API reads and writes ({@code "Small"} ...). */
    public String apiName() {
        return apiName;
    }

    /** Blocks across the facing. */
    public int width() {
        return width;
    }

    /** Blocks along the facing. */
    public int length() {
        return length;
    }

    public int height() {
        return height;
    }

    /** Whether a footprint of this width, length and height holds a weapon of this class. */
    public boolean fits(int widthAcross, int lengthAlong, int height) {
        return widthAcross >= width && lengthAlong >= length && height >= this.height;
    }

    /** "7 wide × 9 long × 5 high". */
    public String describe() {
        return width + " wide × " + length + " long × " + height + " high";
    }
}
