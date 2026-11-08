package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.economy.KillRewardSettings;
import net.knightsandkings.knk.core.survival.kits.KitSelection;
import net.knightsandkings.knk.core.survival.kits.KitSelection.KitOption;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxPool;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxSettings;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxVariant;
import net.knightsandkings.knk.core.survival.purchase.PurchaseRules;
import net.knightsandkings.knk.core.survival.end.EndConditionSettings;
import net.knightsandkings.knk.core.survival.scaling.WaveScalingSettings;
import net.knightsandkings.knk.core.survival.spawn.MonsterMix;
import net.knightsandkings.knk.core.survival.spawn.SpawnZone;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveProfile;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveSchedule;
import net.knightsandkings.knk.core.survival.stuck.StuckSettings;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The frozen survival configuration of one match (DESIGN §3 step 2, §7 "snapshot config at match start"): the API's
 * {@code ConfigurationDto} mapped onto the core records, with every default resolved. Immutable; built once by
 * {@link SurvivalConfigurationMapper} and kept for the match.
 *
 * @param lobbyId the lobby the configuration was resolved for (its all-captured override), empty = scenario only
 */
public record SurvivalConfiguration(
        int scenarioId,
        String scenarioName,
        OptionalInt lobbyId,
        boolean enabled,
        int minPlayers,
        int humanTeamId,
        int npcTeamId,
        boolean allowRecapture,
        long intermissionMillis,
        EndConditionSettings endConditions,
        List<ObjectiveSpec> objectives,
        List<GateSpec> gates,
        StuckSettings stuck,
        DifficultySpec difficulty,
        MonsterMix monsterMix,
        Map<String, SpecialWaveProfile> specialProfiles,
        SpecialWaveSchedule specialSchedule,
        Map<String, SpecialProfileLabel> specialProfileLabels,
        List<ZoneSpec> zones,
        int favorNearPlayersPercent,
        int nearZoneCount,
        KillRewardSettings killRewards,
        EquipmentSpec equipment,
        List<PurchasePointSpec> purchasePoints,
        List<MysteryBoxPointSpec> mysteryBoxPoints,
        Readiness readiness,
        String generatedAt
) {

    public SurvivalConfiguration {
        Objects.requireNonNull(endConditions, "endConditions");
        Objects.requireNonNull(stuck, "stuck");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(monsterMix, "monsterMix");
        Objects.requireNonNull(readiness, "readiness");
        scenarioName = scenarioName == null ? "" : scenarioName;
        lobbyId = lobbyId == null ? OptionalInt.empty() : lobbyId;
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        gates = gates == null ? List.of() : List.copyOf(gates);
        specialProfiles = specialProfiles == null ? Map.of() : Map.copyOf(specialProfiles);
        specialSchedule = specialSchedule == null ? SpecialWaveSchedule.none() : specialSchedule;
        specialProfileLabels = specialProfileLabels == null ? Map.of() : Map.copyOf(specialProfileLabels);
        zones = zones == null ? List.of() : List.copyOf(zones);
        killRewards = killRewards == null ? KillRewardSettings.defaults() : killRewards;
        equipment = equipment == null ? EquipmentSpec.ownInventory() : equipment;
        purchasePoints = purchasePoints == null ? List.of() : List.copyOf(purchasePoints);
        mysteryBoxPoints = mysteryBoxPoints == null ? List.of() : List.copyOf(mysteryBoxPoints);
        generatedAt = generatedAt == null ? "" : generatedAt;
        if (minPlayers < 1) minPlayers = 1;
        if (intermissionMillis < 0) intermissionMillis = 0;
    }

    /** An objective of the scenario as survival sees it. */
    /**
     * What a special profile is called (link S8b, DESIGN §7): the API's {@code key} (checkpoint {@code specialProfileKey})
     * and display {@code name} ("Special round: <name>"); the profile DTO carries no description.
     */
    public record SpecialProfileLabel(String key, String name) {
        public SpecialProfileLabel {
            key = key == null ? "" : key;
            name = name == null ? "" : name;
        }
    }

    public record ObjectiveSpec(int id, String name, boolean instaLose, OptionalInt gateStructureId,
                                Optional<WorldPoint> capture, TargetOverrideMode override) {
        public ObjectiveSpec {
            name = name == null ? ("objective " + id) : name;
            gateStructureId = gateStructureId == null ? OptionalInt.empty() : gateStructureId;
            capture = capture == null ? Optional.empty() : capture;
            override = override == null ? TargetOverrideMode.STANDARD : override;
        }

        public String targetId() {
            return "objective:" + id;
        }
    }

    /** A gate structure of the scenario. */
    public record GateSpec(int gateStructureId, String name, boolean objectiveGate, TargetOverrideMode override) {
        public GateSpec {
            name = name == null ? ("gate " + gateStructureId) : name;
            override = override == null ? TargetOverrideMode.STANDARD : override;
        }

        public String targetId() {
            return "gate:" + gateStructureId;
        }
    }

    /** The chosen difficulty profile. */
    public record DifficultySpec(int id, String name, WaveScalingSettings scaling, double repairRatePerSecond,
                                 double gateDamageFactor, double captureContributionWeight, long spawnIntervalMillis,
                                 OptionalDouble aggroRadius, OptionalDouble leashDistance) {
        public DifficultySpec {
            Objects.requireNonNull(scaling, "scaling");
            name = name == null ? ("profile " + id) : name;
            aggroRadius = aggroRadius == null ? OptionalDouble.empty() : aggroRadius;
            leashDistance = leashDistance == null ? OptionalDouble.empty() : leashDistance;
            if (!(gateDamageFactor >= 0) || !Double.isFinite(gateDamageFactor)) gateDamageFactor = 1.0;
            if (!(captureContributionWeight >= 0) || !Double.isFinite(captureContributionWeight)) captureContributionWeight = 1.0;
            if (spawnIntervalMillis < 0) spawnIntervalMillis = 0;
        }
    }

    /** A point in a world (the DTO's location with its world name). */
    public record WorldPoint(String world, double x, double y, double z, float yaw) {
        public WorldPoint {
            world = world == null ? "" : world;
        }

        public SurvivalPoint point() {
            return new SurvivalPoint(x, y, z);
        }
    }

    /**
     * A spawnzone: the core {@link SpawnZone} (id, weight, prerequisites, anchor) plus what the spawner needs.
     *
     * @param spawnPoints the zone's {@code Spawn} points in order; empty = spawn within {@code areaRadius} of the anchor
     * @param lanePoints  the zone's ordered {@code Lane} points (the optional lane override, DESIGN §6)
     */
    public record ZoneSpec(SpawnZone zone, String name, String world, OptionalDouble areaRadius, int minWave, OptionalInt maxWave,
                           boolean forNormalWaves, boolean forSpecialWaves, boolean laneAutoReroute,
                           List<WorldPoint> spawnPoints, List<WorldPoint> lanePoints) {
        public ZoneSpec {
            Objects.requireNonNull(zone, "zone");
            name = name == null ? zone.id() : name;
            world = world == null ? "" : world;
            areaRadius = areaRadius == null ? OptionalDouble.empty() : areaRadius;
            maxWave = maxWave == null ? OptionalInt.empty() : maxWave;
            spawnPoints = spawnPoints == null ? List.of() : List.copyOf(spawnPoints);
            lanePoints = lanePoints == null ? List.of() : List.copyOf(lanePoints);
            if (minWave < 1) minWave = 1;
        }

        /** Whether the zone takes spawns of this round (wave range, S4-14). */
        public boolean acceptsWave(int round, boolean special) {
            if (round < minWave) return false;
            if (maxWave.isPresent() && round > maxWave.getAsInt()) return false;
            return special ? forSpecialWaves : forNormalWaves;
        }

        /** Usable = at least one spawn point or an area radius (S4-14). */
        public boolean usable() {
            return !spawnPoints.isEmpty() || (areaRadius.isPresent() && areaRadius.getAsDouble() > 0);
        }
    }

    /** The scenario's equipment mode (DESIGN §8; S23, S36, S38; S4-12), link S7b. */
    public enum EquipmentMode { OWN_INVENTORY, KITS, MATCH_PROGRESSION }

    /**
     * Equipment settings: the mode, the selected kits with the default (kits mode) and the baseline loadout kit
     * (match-progression mode; empty = empty inventory).
     */
    public record EquipmentSpec(EquipmentMode mode, List<KitOption> kits, OptionalInt defaultKitId, OptionalInt baselineKitId) {
        public EquipmentSpec {
            mode = mode == null ? EquipmentMode.OWN_INVENTORY : mode;
            kits = kits == null ? List.of() : List.copyOf(kits);
            defaultKitId = defaultKitId == null ? OptionalInt.empty() : defaultKitId;
            baselineKitId = baselineKitId == null ? OptionalInt.empty() : baselineKitId;
        }

        public static EquipmentSpec ownInventory() {
            return new EquipmentSpec(EquipmentMode.OWN_INVENTORY, List.of(), OptionalInt.empty(), OptionalInt.empty());
        }

        /** A fresh kit selection for a lobby (kits mode). */
        public KitSelection newSelection() {
            return new KitSelection(kits, defaultKitId);
        }
    }

    /** A purchase point (DESIGN §8, S4-16): a location, its interaction range and its offers. */
    public record PurchasePointSpec(int id, String name, Optional<WorldPoint> location, double interactionRange, List<OfferSpec> offers) {
        public PurchasePointSpec {
            name = name == null || name.isBlank() ? ("purchase point " + id) : name;
            location = location == null ? Optional.empty() : location;
            if (!(interactionRange > 0) || Double.isNaN(interactionRange)) interactionRange = PurchaseRules.DEFAULT_INTERACTION_RANGE;
            offers = offers == null ? List.of() : List.copyOf(offers);
        }
    }

    /** One offer: a blueprint × quantity at a match-currency price. */
    public record OfferSpec(int id, int itemBlueprintId, String itemBlueprintName, int quantity, long price) {
        public OfferSpec {
            itemBlueprintName = itemBlueprintName == null || itemBlueprintName.isBlank() ? ("blueprint " + itemBlueprintId) : itemBlueprintName;
            if (quantity < 1) quantity = 1;
            if (price < 0) price = 0;
        }
    }

    /**
     * A Mystery Box point (DESIGN §8a; S24, S34, S35): the block at {@code location} (default end portal frame), the roll
     * price, the claim timeout and the pool as served (entries by id for the reel) plus the core pool for its validation.
     */
    public record MysteryBoxPointSpec(int id, String name, Optional<WorldPoint> location, double interactionRange, String blockType,
                                      long rollPrice, long claimTimeoutMillis, MysteryBoxVariant variant,
                                      List<BoxEntrySpec> entries, MysteryBoxPool pool) {
        public static final String DEFAULT_BLOCK_TYPE = "minecraft:end_portal_frame";

        public MysteryBoxPointSpec {
            name = name == null || name.isBlank() ? ("mystery box " + id) : name;
            location = location == null ? Optional.empty() : location;
            if (!(interactionRange > 0) || Double.isNaN(interactionRange)) interactionRange = PurchaseRules.DEFAULT_INTERACTION_RANGE;
            blockType = blockType == null || blockType.isBlank() ? DEFAULT_BLOCK_TYPE : blockType.trim().toLowerCase(java.util.Locale.ROOT);
            if (rollPrice < 0) rollPrice = MysteryBoxSettings.DEFAULT_ROLL_PRICE;
            if (claimTimeoutMillis <= 0) claimTimeoutMillis = MysteryBoxSettings.DEFAULT_CLAIM_TIMEOUT_MILLIS;
            variant = variant == null ? MysteryBoxVariant.ITEMS : variant;
            entries = entries == null ? List.of() : List.copyOf(entries);
            Objects.requireNonNull(pool, "pool");
        }

        public Optional<BoxEntrySpec> entry(int entryId) {
            return entries.stream().filter(e -> e.id() == entryId).findFirst();
        }
    }

    /**
     * A pool entry as served (S7a snapshots the same shape on a roll): an item blueprint with optional enchantment chances,
     * or an enchanted book's enchantment and level. {@code basisPoints} is the entry's own chance (S1-15).
     */
    public record BoxEntrySpec(int id, OptionalInt itemBlueprintId, String itemBlueprintName, OptionalInt enchantmentDefinitionId,
                               String enchantmentKey, int enchantmentLevel, int basisPoints, List<BoxEnchantmentSpec> enchantments) {
        public BoxEntrySpec {
            itemBlueprintId = itemBlueprintId == null ? OptionalInt.empty() : itemBlueprintId;
            enchantmentDefinitionId = enchantmentDefinitionId == null ? OptionalInt.empty() : enchantmentDefinitionId;
            itemBlueprintName = itemBlueprintName == null ? "" : itemBlueprintName;
            enchantmentKey = enchantmentKey == null ? "" : enchantmentKey;
            if (enchantmentLevel < 1) enchantmentLevel = 1;
            if (basisPoints < 0) basisPoints = 0;
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        }

        public boolean isBook() {
            return itemBlueprintId.isEmpty() && enchantmentDefinitionId.isPresent();
        }

        /** What the reel and the status show. */
        public String describe() {
            if (isBook()) return "book " + (enchantmentKey.isBlank() ? "#" + enchantmentDefinitionId.getAsInt() : enchantmentKey) + " " + enchantmentLevel;
            return itemBlueprintName.isBlank() ? "blueprint #" + itemBlueprintId.orElse(-1) : itemBlueprintName;
        }
    }

    /** An item entry's enchantment chance (independent hit, S1-15). */
    public record BoxEnchantmentSpec(int enchantmentDefinitionId, String enchantmentKey, int level, int basisPoints) {
        public BoxEnchantmentSpec {
            enchantmentKey = enchantmentKey == null ? "" : enchantmentKey;
            if (level < 1) level = 1;
            if (basisPoints < 0) basisPoints = 0;
        }
    }

    /** The API's readiness verdict for this configuration. */
    public record Readiness(boolean ready, List<String> errors, List<String> warnings) {
        public Readiness {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public Optional<ObjectiveSpec> objective(int id) {
        return objectives.stream().filter(o -> o.id() == id).findFirst();
    }

    public Optional<GateSpec> gate(int gateStructureId) {
        return gates.stream().filter(g -> g.gateStructureId() == gateStructureId).findFirst();
    }

    /** The objective an objective gate belongs to (empty for an area gate). */
    public Optional<ObjectiveSpec> objectiveOfGate(int gateStructureId) {
        return objectives.stream().filter(o -> o.gateStructureId().isPresent() && o.gateStructureId().getAsInt() == gateStructureId).findFirst();
    }

    /** The core spawn zones (for the frontier). */
    /** The special profile's key for the checkpoint: the API key, else the configuration id. */
    public String specialProfileKey(String profileId) {
        SpecialProfileLabel label = specialProfileLabels.get(profileId);
        return label == null || label.key().isBlank() ? profileId : label.key();
    }

    /** The special profile's display name: the API name, else its key, else the id. */
    public String specialProfileName(String profileId) {
        SpecialProfileLabel label = specialProfileLabels.get(profileId);
        if (label == null) return profileId;
        if (!label.name().isBlank()) return label.name();
        return label.key().isBlank() ? profileId : label.key();
    }

    public List<SpawnZone> spawnZones() {
        return zones.stream().map(ZoneSpec::zone).toList();
    }

    public Optional<ZoneSpec> zone(String zoneId) {
        return zones.stream().filter(z -> z.zone().id().equals(zoneId)).findFirst();
    }

    public Optional<PurchasePointSpec> purchasePoint(int id) {
        return purchasePoints.stream().filter(p -> p.id() == id).findFirst();
    }

    public Optional<MysteryBoxPointSpec> mysteryBoxPoint(int id) {
        return mysteryBoxPoints.stream().filter(p -> p.id() == id).findFirst();
    }
}
