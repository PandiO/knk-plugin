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
 * @param tokenMaterial       material name of lootbox token items (Phase 5); not a fuel, a crafting ingredient or
 *                            anything usable, and its identity is the PDC token, never the material or name
 * @param tokenModel          {@code display.model: token} shows a world box as the token item it becomes when picked
 *                            up (DESIGN.md §3.8); {@code type} shows the type's display material (its category icon)
 * @param opening             how a token opens (DESIGN.md §3.9)
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
        Map<Integer, String> gradeColors,
        String tokenMaterial,
        boolean tokenModel,
        Opening opening
) {
    /**
     * {@code opening:} - {@code style: wheel} spins a reel in a chest menu that stops on the rolled item (the item
     * goes into the inventory when it stops, or at once when the menu is closed early); {@code instant} gives it
     * straight away. {@code publicEffects}: particles and sounds at the player that others nearby see and hear.
     */
    public record Opening(boolean wheel, int reelSteps, int slowestStepTicks, int showResultTicks, boolean publicEffects) {
        public static final Opening DEFAULT = new Opening(true, 32, 8, 50, true);
    }

    public static final String DEFAULT_TOKEN_MATERIAL = "ENDER_CHEST";
    static final List<String> DEFAULT_FORBIDDEN_GROUND = List.of("WATER", "LAVA", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW");

    private static final Map<Integer, String> DEFAULT_GRADE_COLORS = Map.of(
            1, "&9", 2, "&9", 3, "&b", 4, "&b", 5, "&d", 6, "&6", 7, "&6", 8, "&c", 9, "&c", 10, "&4");

    public LootboxSettings {
        forbiddenGround = forbiddenGround == null ? Set.of() : Set.copyOf(forbiddenGround);
        gradeColors = gradeColors == null ? Map.of() : Map.copyOf(gradeColors);
        tokenMaterial = tokenMaterial == null || tokenMaterial.isBlank() ? DEFAULT_TOKEN_MATERIAL : tokenMaterial.trim().toUpperCase(Locale.ROOT);
        opening = opening == null ? Opening.DEFAULT : opening;
    }

    public static LootboxSettings defaults() {
        return from(null);
    }

    /** Reads the section; a missing section or key takes the DESIGN.md §3.4 default. */
    public static LootboxSettings from(ConfigurationSection section) {
        if (section == null) {
            return new LootboxSettings(true, "default", 60, 20, 5, true, false, 4,
                    Set.copyOf(DEFAULT_FORBIDDEN_GROUND), true, true, 15, DEFAULT_GRADE_COLORS, DEFAULT_TOKEN_MATERIAL, true, Opening.DEFAULT);
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
                colors,
                section.getString("token.material", DEFAULT_TOKEN_MATERIAL),
                !"type".equalsIgnoreCase(section.getString("display.model", "token").trim()),
                new Opening(
                        !"instant".equalsIgnoreCase(section.getString("opening.style", "wheel").trim()),
                        Math.max(5, Math.min(200, section.getInt("opening.reel-steps", Opening.DEFAULT.reelSteps()))),
                        Math.max(1, Math.min(40, section.getInt("opening.slowest-step-ticks", Opening.DEFAULT.slowestStepTicks()))),
                        Math.max(0, Math.min(200, section.getInt("opening.show-result-ticks", Opening.DEFAULT.showResultTicks()))),
                        section.getBoolean("opening.public-effects", Opening.DEFAULT.publicEffects())));
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
