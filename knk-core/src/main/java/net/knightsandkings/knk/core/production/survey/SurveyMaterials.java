package net.knightsandkings.knk.core.production.survey;

import java.util.Map;
import java.util.Set;

/**
 * Which blocks the survey counts as what (PRODUCTION_KINDS § 2.1, § 8), by material name so knk-core stays Bukkit-free.
 * A kind's survey profile can replace the block list ({@code materials}) and the entity list ({@code entityTypes}).
 */
public final class SurveyMaterials {

    /** Rock of a quarry or mine (PRODUCTION_KINDS § 2.1 Quarry). Ores are every {@code *_ORE} plus ancient debris. */
    public static final Set<String> ROCK = Set.of(
            "STONE", "GRANITE", "DIORITE", "ANDESITE", "DEEPSLATE", "TUFF", "CALCITE", "SANDSTONE", "RED_SANDSTONE",
            "COBBLESTONE", "COBBLED_DEEPSLATE", "MOSSY_COBBLESTONE", "BASALT", "SMOOTH_BASALT", "BLACKSTONE",
            "NETHERRACK", "END_STONE", "DRIPSTONE_BLOCK", "OBSIDIAN", "GRAVEL", "CLAY", "TERRACOTTA", "MAGMA_BLOCK",
            "AMETHYST_BLOCK", "BUDDING_AMETHYST");

    /** Crops (PRODUCTION_KINDS § 2.1 Farm) and the unit they count as; attached stems count as their stem. */
    public static final Map<String, String> CROPS = Map.ofEntries(
            Map.entry("WHEAT", "WHEAT"),
            Map.entry("CARROTS", "CARROTS"),
            Map.entry("POTATOES", "POTATOES"),
            Map.entry("BEETROOTS", "BEETROOTS"),
            Map.entry("MELON_STEM", "MELON_STEM"),
            Map.entry("ATTACHED_MELON_STEM", "MELON_STEM"),
            Map.entry("PUMPKIN_STEM", "PUMPKIN_STEM"),
            Map.entry("ATTACHED_PUMPKIN_STEM", "PUMPKIN_STEM"),
            Map.entry("MELON", "MELON"),
            Map.entry("PUMPKIN", "PUMPKIN"),
            Map.entry("SUGAR_CANE", "SUGAR_CANE"),
            Map.entry("COCOA", "COCOA"),
            Map.entry("NETHER_WART", "NETHER_WART"),
            Map.entry("SWEET_BERRY_BUSH", "SWEET_BERRY_BUSH"),
            Map.entry("TORCHFLOWER_CROP", "TORCHFLOWER_CROP"),
            Map.entry("PITCHER_CROP", "PITCHER_CROP"),
            Map.entry("CACTUS", "CACTUS"),
            Map.entry("BAMBOO", "BAMBOO"));

    /** Flowers (PRODUCTION_KINDS § 8.6, unit {@code FLOWER:<type>}). */
    public static final Set<String> FLOWERS = Set.of(
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP",
            "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "TORCHFLOWER",
            "CLOSED_EYEBLOSSOM", "OPEN_EYEBLOSSOM", "PINK_PETALS", "WILDFLOWERS", "SPORE_BLOSSOM", "CACTUS_FLOWER",
            "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PITCHER_PLANT");

    /** Two-block flowers: only the lower half counts. */
    public static final Set<String> TALL_FLOWERS = Set.of("SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PITCHER_PLANT");

    /** Pasture animals counted by default (PRODUCTION_KINDS § 8.7); hunters are deferred (§ 8.8, P-D10). */
    public static final Set<String> FARM_ANIMALS = Set.of(
            "COW", "MOOSHROOM", "SHEEP", "PIG", "CHICKEN", "RABBIT", "GOAT", "HORSE", "DONKEY", "MULE", "LLAMA",
            "CAMEL", "TURTLE", "BEE", "ARMADILLO", "FROG");

    private SurveyMaterials() {
    }

    public static boolean isOre(String material) {
        return material != null && (material.endsWith("_ORE") || material.equals("ANCIENT_DEBRIS"));
    }

    public static boolean isRockOrOre(String material) {
        return material != null && (ROCK.contains(material) || isOre(material));
    }

    /** Log species ({@code OAK}) of a log or nether stem, or null. Stripped logs are building material, not trees. */
    public static String logSpecies(String material) {
        if (material == null || material.startsWith("STRIPPED_")) {
            return null;
        }
        if (material.endsWith("_LOG")) {
            return material.substring(0, material.length() - 4);
        }
        if (material.equals("CRIMSON_STEM") || material.equals("WARPED_STEM")) {
            return material.substring(0, material.length() - 5);
        }
        return null;
    }

    public static boolean isLeaves(String material) {
        return material != null && (material.endsWith("_LEAVES") || material.equals("NETHER_WART_BLOCK")
                || material.equals("WARPED_WART_BLOCK"));
    }

    /** Sapling species ({@code OAK}), or null. */
    public static String saplingSpecies(String material) {
        if (material == null || material.startsWith("POTTED_")) {
            return null;
        }
        if (material.endsWith("_SAPLING")) {
            return material.substring(0, material.length() - 8);
        }
        return switch (material) {
            case "MANGROVE_PROPAGULE" -> "MANGROVE";
            case "CRIMSON_FUNGUS" -> "CRIMSON";
            case "WARPED_FUNGUS" -> "WARPED";
            default -> null;
        };
    }
}
