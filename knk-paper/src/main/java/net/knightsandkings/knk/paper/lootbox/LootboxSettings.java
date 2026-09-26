package net.knightsandkings.knk.paper.lootbox;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code lootboxes:} section of config.yml (docs/specs/lootboxes/DESIGN.md §3.4). Everything the API decides
 * (limits, odds, areas, templates) lives in the web app instead; this is only how this server places, shows and
 * opens boxes.
 *
 * @param serverId            sent with every spawn so the drop log shows which server made it
 * @param claimMaxDistance    blocks between the player and the box
 * @param refuseWhenFull      {@code full-inventory: refuse} (no API call with a full inventory) vs {@code drop-owned}
 * @param forbiddenGround     material names a box may not stand on (liquids and leaves are always refused)
 * @param gradeColors         box stars → legacy colour code for the label
 */
public record LootboxSettings(
        boolean enabled,
        String serverId,
        int runtimeRefreshSeconds,
        int schedulerTickSeconds,
        double claimMaxDistance,
        boolean refuseWhenFull,
        boolean staffModeCanClaim,
        int maxAttemptsPerTick,
        Set<String> forbiddenGround,
        boolean label,
        boolean rotate,
        int particlesRadius,
        Map<Integer, String> gradeColors
) {
    static final List<String> DEFAULT_FORBIDDEN_GROUND = List.of("WATER", "LAVA", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW");

    private static final Map<Integer, String> DEFAULT_GRADE_COLORS = Map.of(
            1, "&9", 2, "&9", 3, "&b", 4, "&b", 5, "&d", 6, "&6", 7, "&6", 8, "&c", 9, "&c", 10, "&4");

    public LootboxSettings {
        forbiddenGround = forbiddenGround == null ? Set.of() : Set.copyOf(forbiddenGround);
        gradeColors = gradeColors == null ? Map.of() : Map.copyOf(gradeColors);
    }

    public static LootboxSettings defaults() {
        return from(null);
    }

    /** Reads the section; a missing section or key takes the DESIGN.md §3.4 default. */
    public static LootboxSettings from(ConfigurationSection section) {
        if (section == null) {
            return new LootboxSettings(true, "default", 60, 20, 5, true, false, 4,
                    Set.copyOf(DEFAULT_FORBIDDEN_GROUND), true, true, 15, DEFAULT_GRADE_COLORS);
        }

        List<String> ground = section.isList("surface.forbidden-ground")
                ? section.getStringList("surface.forbidden-ground")
                : DEFAULT_FORBIDDEN_GROUND;

        Map<Integer, String> colors = new HashMap<>(DEFAULT_GRADE_COLORS);
        ConfigurationSection colorSection = section.getConfigurationSection("display.grade-colors");
        if (colorSection != null) {
            for (String key : colorSection.getKeys(false)) {
                try {
                    colors.put(Integer.parseInt(key.trim()), colorSection.getString(key, "&f"));
                } catch (NumberFormatException ignored) {
                    // Not a star count: ignore the entry.
                }
            }
        }

        String fullInventory = section.getString("full-inventory", "refuse");
        String serverId = section.getString("server-id", "default");
        return new LootboxSettings(
                section.getBoolean("enabled", true),
                serverId == null || serverId.isBlank() ? "default" : serverId.trim(),
                Math.max(10, section.getInt("runtime-refresh-seconds", 60)),
                Math.max(1, section.getInt("scheduler-tick-seconds", 20)),
                Math.max(1, section.getDouble("claim-max-distance", 5)),
                !"drop-owned".equalsIgnoreCase(fullInventory == null ? "" : fullInventory.trim()),
                section.getBoolean("staff-mode-can-claim", false),
                Math.max(1, section.getInt("surface.max-attempts-per-tick", 4)),
                ground.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet()),
                section.getBoolean("display.label", true),
                section.getBoolean("display.rotate", true),
                Math.max(0, section.getInt("display.particles-radius", 15)),
                colors);
    }

    /** The label colour for a box of {@code stars} (white when unconfigured). */
    public String colorFor(int stars) {
        return gradeColors.getOrDefault(stars, "&f");
    }

    /** {@code <colour><boxLabel> ★★★★★}: the box's floating label and its name in messages. */
    public String coloredLabel(String boxLabel, int stars) {
        String label = boxLabel == null || boxLabel.isBlank() ? "Lootbox" : boxLabel;
        return colorFor(stars) + label + " " + "★".repeat(Math.max(0, Math.min(stars, 10)));
    }
}
