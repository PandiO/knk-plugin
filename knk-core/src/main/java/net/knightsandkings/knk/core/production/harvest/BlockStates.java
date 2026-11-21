package net.knightsandkings.knk.core.production.harvest;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a block data string ({@code minecraft:wheat[age=7]}) without Bukkit: its material name and its age. */
public final class BlockStates {

    private static final Pattern AGE = Pattern.compile("[\\[,]age=(\\d+)");

    private BlockStates() {
    }

    /** {@code minecraft:wheat[age=7]} → {@code WHEAT}; null stays null. */
    public static String material(String blockData) {
        if (blockData == null) {
            return null;
        }
        String s = blockData;
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            s = s.substring(0, bracket);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        return s.trim().toUpperCase(Locale.ROOT);
    }

    /** The {@code age} property, or -1 when the block has none. */
    public static int age(String blockData) {
        if (blockData == null) {
            return -1;
        }
        Matcher m = AGE.matcher(blockData);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The same block with {@code age=0} (a replanted crop), or the block unchanged when it has no age. */
    public static String withAgeZero(String blockData) {
        if (blockData == null || age(blockData) < 0) {
            return blockData;
        }
        return blockData.replaceFirst("([\\[,])age=\\d+", "$1age=0");
    }
}
