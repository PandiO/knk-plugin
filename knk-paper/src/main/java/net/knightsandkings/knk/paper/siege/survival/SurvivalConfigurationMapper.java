package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.api.dto.LocationDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.ReadinessIssueDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.DifficultyDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.EquipmentDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxEntryDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxEntryEnchantmentDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxPointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxPoolDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PurchaseOfferDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PurchasePointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SelectedKitDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.GateDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MonsterTypeDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ObjectiveDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PerPlayerScalingDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ReadinessDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ScalingCurveDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SpawnZoneDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SpawnZonePointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SpecialWaveEntryDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SpecialWaveProfileDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.StatWeightsDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.StuckDto;
import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.economy.KillRewardSettings;
import net.knightsandkings.knk.core.survival.kits.KitSelection.KitOption;
import net.knightsandkings.knk.core.survival.mysterybox.BasisPoints;
import net.knightsandkings.knk.core.survival.mysterybox.EnchantmentChance;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxEntry;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxPool;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxSettings;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxVariant;
import net.knightsandkings.knk.core.survival.purchase.PurchaseRules;
import net.knightsandkings.knk.core.survival.end.AllCapturedOverride;
import net.knightsandkings.knk.core.survival.end.EndConditionSettings;
import net.knightsandkings.knk.core.survival.scaling.PerPlayerScaling;
import net.knightsandkings.knk.core.survival.scaling.ScaledStat;
import net.knightsandkings.knk.core.survival.scaling.ScalingCurve;
import net.knightsandkings.knk.core.survival.scaling.ScalingPreset;
import net.knightsandkings.knk.core.survival.scaling.StatWeights;
import net.knightsandkings.knk.core.survival.scaling.WaveScalingSettings;
import net.knightsandkings.knk.core.survival.spawn.MonsterMix;
import net.knightsandkings.knk.core.survival.spawn.MonsterType;
import net.knightsandkings.knk.core.survival.spawn.PrerequisitePolicy;
import net.knightsandkings.knk.core.survival.spawn.SpawnZone;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveEntry;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveProfile;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveSchedule;
import net.knightsandkings.knk.core.survival.stuck.StuckSettings;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.BoxEnchantmentSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.BoxEntrySpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.DifficultySpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.EquipmentMode;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.EquipmentSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.MysteryBoxPointSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.OfferSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.PurchasePointSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.GateSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ObjectiveSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.Readiness;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.WorldPoint;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ZoneSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Maps the API's frozen configuration (link S4, {@code ConfigurationDto}) onto the core survival records (link S1)
 * as the S4 notes describe: {@code stuck} → {@link StuckSettings}, {@code endConditions} →
 * {@link EndConditionSettings}, the difficulty's curves/weights/per-player/caps → {@link WaveScalingSettings},
 * {@code monsterTypes} → {@link MonsterMix} (ids = upper-case entity type keys), {@code specialWaves} →
 * {@link SpecialWaveProfile}/{@link SpecialWaveEntry}, {@code spawn.zones} → {@link SpawnZone} (ids as strings),
 * {@code killRewards} → {@link KillRewardSettings}. Missing fields fall back to the design defaults; the API's
 * PascalCase enum names map to the core constants. Bukkit-free and pure.
 */
public final class SurvivalConfigurationMapper {

    private SurvivalConfigurationMapper() {
    }

    public static SurvivalConfiguration map(ConfigurationDto dto) {
        Objects.requireNonNull(dto, "dto");
        int scenarioId = orDefault(dto.siegeScenarioId(), 0);
        List<ObjectiveSpec> objectives = objectives(dto.objectives());
        Set<String> objectiveIds = new LinkedHashSet<>();
        objectives.forEach(o -> objectiveIds.add(String.valueOf(o.id())));
        List<GateSpec> gates = gates(dto.gates(), objectives);
        DifficultyDto difficultyDto = dto.difficulty() != null ? dto.difficulty() : defaultProfile(dto.difficultyProfiles());
        return new SurvivalConfiguration(
            scenarioId,
            dto.siegeScenarioName(),
            dto.siegeLobbyId() == null ? OptionalInt.empty() : OptionalInt.of(dto.siegeLobbyId()),
            !Boolean.FALSE.equals(dto.enabled()),
            orDefault(dto.minPlayers(), 1),
            orDefault(dto.humanTeamId(), -1),
            orDefault(dto.npcTeamId(), -1),
            Boolean.TRUE.equals(dto.allowRecapture()),
            orDefault(dto.intermissionMillis(), 10_000),
            endConditions(dto),
            objectives,
            gates,
            stuck(dto.stuck()),
            difficulty(difficultyDto),
            monsterMix(dto.monsterTypes()),
            specialProfiles(dto),
            specialSchedule(dto),
            specialProfileLabels(dto),
            zones(dto),
            dto.spawn() == null ? 0 : clamp(orDefault(dto.spawn().favorNearPlayersPercent(), 0), 0, 100),
            dto.spawn() == null ? 1 : Math.max(1, orDefault(dto.spawn().nearZoneCount(), 1)),
            killRewards(dto),
            equipment(dto.equipment()),
            purchasePoints(dto.purchasePoints()),
            mysteryBoxPoints(dto.mysteryBoxPoints()),
            readiness(dto.readiness()),
            dto.generatedAt() == null ? "" : dto.generatedAt().toString());
    }

    // ---- sections ----

    static EndConditionSettings endConditions(ConfigurationDto dto) {
        if (dto.endConditions() == null) return EndConditionSettings.defaults();
        boolean scenario = Boolean.TRUE.equals(dto.endConditions().scenarioAllCaptured());
        AllCapturedOverride override = parseOverride(dto.endConditions().lobbyOverride());
        return new EndConditionSettings(scenario, override);
    }

    static AllCapturedOverride parseOverride(String value) {
        if (value == null) return AllCapturedOverride.INHERIT;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on" -> AllCapturedOverride.ON;
            case "off" -> AllCapturedOverride.OFF;
            default -> AllCapturedOverride.INHERIT;
        };
    }

    static List<ObjectiveSpec> objectives(List<ObjectiveDto> dtos) {
        if (dtos == null) return List.of();
        List<ObjectiveSpec> out = new ArrayList<>();
        for (ObjectiveDto o : dtos) {
            if (o == null || o.id() == null) continue;
            out.add(new ObjectiveSpec(o.id(), o.name(), Boolean.TRUE.equals(o.instaLose()),
                o.gateStructureId() == null ? OptionalInt.empty() : OptionalInt.of(o.gateStructureId()),
                point(o.captureLocation()), TargetOverrideMode.parse(o.targetOverride())));
        }
        return out;
    }

    static List<GateSpec> gates(List<GateDto> dtos, List<ObjectiveSpec> objectives) {
        if (dtos == null) return List.of();
        List<GateSpec> out = new ArrayList<>();
        for (GateDto g : dtos) {
            if (g == null || g.gateStructureId() == null) continue;
            boolean objectiveGate = g.isObjectiveGate() != null ? g.isObjectiveGate()
                : objectives.stream().anyMatch(o -> o.gateStructureId().isPresent() && o.gateStructureId().getAsInt() == g.gateStructureId());
            out.add(new GateSpec(g.gateStructureId(), g.name(), objectiveGate, TargetOverrideMode.parse(g.targetOverride())));
        }
        return out;
    }

    static StuckSettings stuck(StuckDto dto) {
        if (dto == null) return StuckSettings.defaults();
        StuckSettings defaults = StuckSettings.defaults();
        long after = dto.stuckAfterMillis() == null ? defaults.stuckAfterMillis() : dto.stuckAfterMillis();
        int last = dto.lastEnemiesCount() == null ? defaults.lastEnemiesCount() : dto.lastEnemiesCount();
        int limit = dto.respawnLimit() == null ? defaults.respawnLimit() : dto.respawnLimit();
        double distance = dto.meaningfulDistance() == null ? defaults.meaningfulDistance() : dto.meaningfulDistance();
        StuckSettings settings = new StuckSettings(after, last, limit, defaults.roundBackstopMillis(), distance);
        // null = backstop off (S4 notes); the DTO sends the configured value otherwise.
        return dto.roundBackstopMillis() == null ? settings.withoutBackstop() : settings.withBackstop(dto.roundBackstopMillis());
    }

    static DifficultyDto defaultProfile(List<DifficultyDto> profiles) {
        if (profiles == null || profiles.isEmpty()) return null;
        return profiles.stream().filter(p -> Boolean.TRUE.equals(p.isDefault())).findFirst().orElse(profiles.get(0));
    }

    static DifficultySpec difficulty(DifficultyDto dto) {
        if (dto == null) {
            // No profile authored: the S4 profile defaults (balance placeholders, S4-7).
            return new DifficultySpec(0, "default", new WaveScalingSettings(ScalingCurve.linear(6, 2),
                ScalingCurve.linear(1, 0.1), defaultWeights(), new PerPlayerScaling(0.25, false), 40, 8, 0),
                50, 1, 1, 500, OptionalDouble.empty(), OptionalDouble.empty());
        }
        ScalingCurve count = curve(dto.countCurve(), ScalingCurve.linear(6, 2));
        ScalingCurve stat = curve(dto.statCurve(), ScalingCurve.linear(1, 0.1));
        StatWeights weights = weights(dto.statWeights());
        PerPlayerScaling perPlayer = perPlayer(dto.perPlayer());
        int maxAlive = Math.max(0, orDefault(dto.maxAlive(), 40));
        double statCap = dto.statCap() == null || !(dto.statCap() >= 1) ? 8 : dto.statCap();
        int countCap = Math.max(0, orDefault(dto.countCap(), 0));
        WaveScalingSettings scaling = new WaveScalingSettings(count, stat, weights, perPlayer, maxAlive, statCap, countCap);
        return new DifficultySpec(orDefault(dto.id(), 0), dto.name(), scaling,
            orDefault(dto.repairRatePerSecond(), 50.0), orDefault(dto.gateDamageFactor(), 1.0),
            orDefault(dto.captureContributionWeight(), 1.0), orDefault(dto.spawnIntervalMillis(), 500),
            optional(dto.aggroRadius()), optional(dto.leashDistance()));
    }

    static ScalingCurve curve(ScalingCurveDto dto, ScalingCurve fallback) {
        if (dto == null) return fallback;
        ScalingPreset preset = parsePreset(dto.preset(), fallback.preset());
        double base = orDefault(dto.base(), fallback.base());
        double step = orDefault(dto.step(), fallback.step());
        double multiplier = orDefault(dto.multiplier(), fallback.multiplier() <= 0 ? 1.1 : fallback.multiplier());
        int switchRound = Math.max(1, orDefault(dto.switchRound(), Math.max(1, fallback.switchRound())));
        try {
            return new ScalingCurve(preset, base, step, multiplier, switchRound);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static ScalingPreset parsePreset(String value, ScalingPreset fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "linear" -> ScalingPreset.LINEAR;
            case "logarithmic" -> ScalingPreset.LOGARITHMIC;
            case "exponential" -> ScalingPreset.EXPONENTIAL;
            case "hybrid" -> ScalingPreset.HYBRID;
            default -> fallback;
        };
    }

    static StatWeights defaultWeights() {
        return StatWeights.uniform().with(ScaledStat.WALKING_SPEED, 0.3).with(ScaledStat.ATTACK_SPEED, 0.5);
    }

    static StatWeights weights(StatWeightsDto dto) {
        StatWeights defaults = defaultWeights();
        if (dto == null) return defaults;
        StatWeights w = defaults;
        if (dto.health() != null) w = w.with(ScaledStat.HEALTH, Math.max(0, dto.health()));
        if (dto.walkingSpeed() != null) w = w.with(ScaledStat.WALKING_SPEED, Math.max(0, dto.walkingSpeed()));
        if (dto.meleeDamage() != null) w = w.with(ScaledStat.MELEE_DAMAGE, Math.max(0, dto.meleeDamage()));
        if (dto.rangedDamage() != null) w = w.with(ScaledStat.RANGED_DAMAGE, Math.max(0, dto.rangedDamage()));
        if (dto.attackSpeed() != null) w = w.with(ScaledStat.ATTACK_SPEED, Math.max(0, dto.attackSpeed()));
        return w;
    }

    static PerPlayerScaling perPlayer(PerPlayerScalingDto dto) {
        if (dto == null) return new PerPlayerScaling(0.25, false);
        return new PerPlayerScaling(Math.max(0, orDefault(dto.factor(), 0.25)), Boolean.TRUE.equals(dto.applyToStats()));
    }

    static MonsterMix monsterMix(List<MonsterTypeDto> dtos) {
        return new MonsterMix(monsterTypes(dtos));
    }

    static List<MonsterType> monsterTypes(List<MonsterTypeDto> dtos) {
        if (dtos == null) return List.of();
        List<MonsterType> out = new ArrayList<>();
        for (MonsterTypeDto m : dtos) {
            if (m == null || m.entityType() == null || m.entityType().isBlank()) continue;
            String id = m.entityType().trim().toUpperCase(Locale.ROOT);
            double weight = m.weight() == null || !(m.weight() > 0) ? MonsterType.DEFAULT_WEIGHT : m.weight();
            int unlock = Math.max(1, orDefault(m.unlockRound(), MonsterType.DEFAULT_UNLOCK_ROUND));
            out.add(new MonsterType(id, weight, unlock));
        }
        return out;
    }

    static Map<String, SpecialWaveProfile> specialProfiles(ConfigurationDto dto) {
        Map<String, SpecialWaveProfile> out = new LinkedHashMap<>();
        if (dto.specialWaves() == null || dto.specialWaves().profiles() == null) return out;
        for (SpecialWaveProfileDto p : dto.specialWaves().profiles()) {
            if (p == null || p.id() == null) continue;
            String id = String.valueOf(p.id());
            OptionalInt countOverride = p.countOverride() == null ? OptionalInt.empty() : OptionalInt.of(Math.max(1, p.countOverride()));
            double multiplier = p.countMultiplier() == null || !(p.countMultiplier() > 0) ? 1.0 : p.countMultiplier();
            try {
                out.put(id, new SpecialWaveProfile(id, new MonsterMix(monsterTypes(p.monsterTypes())), countOverride,
                    multiplier, Boolean.TRUE.equals(p.additive())));
            } catch (IllegalArgumentException ignored) {
                // an unusable profile is left out; the schedule's readiness names it (special.unknown-profile)
            }
        }
        return out;
    }

    /** Link S8b: the key and name of every special profile, by its configuration id (the planner's profile id). */
    static Map<String, SurvivalConfiguration.SpecialProfileLabel> specialProfileLabels(ConfigurationDto dto) {
        Map<String, SurvivalConfiguration.SpecialProfileLabel> out = new LinkedHashMap<>();
        if (dto.specialWaves() == null || dto.specialWaves().profiles() == null) return out;
        for (SpecialWaveProfileDto p : dto.specialWaves().profiles()) {
            if (p == null || p.id() == null) continue;
            out.put(String.valueOf(p.id()), new SurvivalConfiguration.SpecialProfileLabel(p.key(), p.name()));
        }
        return out;
    }

    static SpecialWaveSchedule specialSchedule(ConfigurationDto dto) {
        if (dto.specialWaves() == null || dto.specialWaves().entries() == null) return SpecialWaveSchedule.none();
        List<SpecialWaveEntry> entries = new ArrayList<>();
        for (SpecialWaveEntryDto e : dto.specialWaves().entries()) {
            if (e == null || e.survivalSpecialWaveProfileId() == null) continue;
            String profileId = String.valueOf(e.survivalSpecialWaveProfileId());
            String kind = e.kind() == null ? "recurring" : e.kind().trim().toLowerCase(Locale.ROOT);
            try {
                if ("explicit".equals(kind)) {
                    if (e.wave() == null) continue;
                    entries.add(new SpecialWaveEntry.Explicit(e.wave(), profileId));
                } else {
                    if (e.interval() == null) continue;
                    int start = e.startWave() == null ? e.interval() : e.startWave();
                    entries.add(new SpecialWaveEntry.Recurring(e.interval(), start, profileId, orDefault(e.priority(), 1)));
                }
            } catch (IllegalArgumentException ignored) {
                // skipped: readiness (special.entry-invalid) reports it on the API side
            }
        }
        return new SpecialWaveSchedule(entries);
    }

    static List<ZoneSpec> zones(ConfigurationDto dto) {
        if (dto.spawn() == null || dto.spawn().zones() == null) return List.of();
        List<ZoneSpec> out = new ArrayList<>();
        for (SpawnZoneDto z : dto.spawn().zones()) {
            if (z == null || z.id() == null) continue;
            Optional<WorldPoint> anchor = point(z.anchorLocation());
            List<WorldPoint> spawns = new ArrayList<>();
            List<WorldPoint> lanes = new ArrayList<>();
            if (z.points() != null) {
                List<SpawnZonePointDto> sorted = new ArrayList<>(z.points());
                sorted.removeIf(Objects::isNull);
                sorted.sort(Comparator.comparingInt(p -> orDefault(p.sortOrder(), 0)));
                for (SpawnZonePointDto p : sorted) {
                    Optional<WorldPoint> at = point(p.location());
                    if (at.isEmpty()) continue;
                    if (p.kind() != null && p.kind().trim().equalsIgnoreCase("lane")) lanes.add(at.get());
                    else spawns.add(at.get());
                }
            }
            WorldPoint anchorPoint = anchor.orElseGet(() -> spawns.isEmpty() ? null : spawns.get(0));
            if (anchorPoint == null) continue; // a zone without any position can't be used (spawn.zone-unusable)
            Set<String> prerequisites = new LinkedHashSet<>();
            if (z.prerequisiteObjectiveIds() != null) z.prerequisiteObjectiveIds().forEach(id -> { if (id != null) prerequisites.add(String.valueOf(id)); });
            PrerequisitePolicy policy = z.prerequisitePolicy() != null && z.prerequisitePolicy().trim().equalsIgnoreCase("any")
                ? PrerequisitePolicy.ANY : PrerequisitePolicy.ALL;
            double weight = z.weight() == null || !(z.weight() > 0) ? 1.0 : z.weight();
            SpawnZone zone = new SpawnZone(String.valueOf(z.id()), weight, prerequisites, policy, anchorPoint.point());
            String world = anchorPoint.world().isEmpty() && !spawns.isEmpty() ? spawns.get(0).world() : anchorPoint.world();
            out.add(new ZoneSpec(zone, z.name(), world, optional(z.areaRadius()), orDefault(z.minWave(), 1),
                z.maxWave() == null ? OptionalInt.empty() : OptionalInt.of(z.maxWave()),
                !Boolean.FALSE.equals(z.forNormalWaves()), !Boolean.FALSE.equals(z.forSpecialWaves()),
                Boolean.TRUE.equals(z.laneAutoReroute()), spawns, lanes));
        }
        return out;
    }

    static KillRewardSettings killRewards(ConfigurationDto dto) {
        if (dto.killRewards() == null) return KillRewardSettings.defaults();
        KillRewardSettings d = KillRewardSettings.defaults();
        BigDecimal coins = dto.killRewards().coinsPerKill() == null ? d.coinsPerKill() : dto.killRewards().coinsPerKill();
        BigDecimal xp = dto.killRewards().xpPerKill() == null ? d.xpPerKill() : dto.killRewards().xpPerKill();
        long currency = dto.killRewards().matchCurrencyPerKill() == null ? d.matchCurrencyPerKill() : dto.killRewards().matchCurrencyPerKill();
        BigDecimal bonus = dto.killRewards().bonusMultiplier() == null ? d.bonusMultiplier() : dto.killRewards().bonusMultiplier();
        try {
            return new KillRewardSettings(coins, xp, currency, bonus);
        } catch (IllegalArgumentException e) {
            return d;
        }
    }

    // ---- phase 4 (link S7b): equipment, purchase points, Mystery Box points ----

    static EquipmentSpec equipment(EquipmentDto dto) {
        if (dto == null) return EquipmentSpec.ownInventory();
        List<KitOption> kits = new ArrayList<>();
        if (dto.selectedKits() != null) {
            List<SelectedKitDto> sorted = new ArrayList<>(dto.selectedKits());
            sorted.sort(Comparator.comparingInt(k -> orDefault(k.sortOrder(), 0)));
            for (SelectedKitDto k : sorted) {
                if (k == null || k.kitId() == null) continue;
                kits.add(new KitOption(k.kitId(), k.kitName(), orDefault(k.sortOrder(), 0)));
            }
        }
        return new EquipmentSpec(parseEquipmentMode(dto.mode()), kits,
            dto.defaultKitId() == null ? OptionalInt.empty() : OptionalInt.of(dto.defaultKitId()),
            dto.baselineLoadoutKitId() == null ? OptionalInt.empty() : OptionalInt.of(dto.baselineLoadoutKitId()));
    }

    static EquipmentMode parseEquipmentMode(String value) {
        if (value == null) return EquipmentMode.OWN_INVENTORY;
        String v = value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return switch (v) {
            case "kits", "kit" -> EquipmentMode.KITS;
            case "matchprogression", "progression" -> EquipmentMode.MATCH_PROGRESSION;
            default -> EquipmentMode.OWN_INVENTORY;
        };
    }

    static List<PurchasePointSpec> purchasePoints(List<PurchasePointDto> dtos) {
        if (dtos == null) return List.of();
        List<PurchasePointSpec> out = new ArrayList<>();
        List<PurchasePointDto> sorted = new ArrayList<>(dtos);
        sorted.removeIf(Objects::isNull);
        sorted.sort(Comparator.comparingInt(p -> orDefault(p.sortOrder(), 0)));
        for (PurchasePointDto p : sorted) {
            List<OfferSpec> offers = new ArrayList<>();
            if (p.offers() != null) {
                List<PurchaseOfferDto> sortedOffers = new ArrayList<>(p.offers());
                sortedOffers.removeIf(Objects::isNull);
                sortedOffers.sort(Comparator.comparingInt(o -> orDefault(o.sortOrder(), 0)));
                for (PurchaseOfferDto o : sortedOffers) {
                    if (o.itemBlueprintId() == null) continue;
                    offers.add(new OfferSpec(orDefault(o.id(), 0), o.itemBlueprintId(), o.itemBlueprintName(),
                        orDefault(o.quantity(), 1), orDefault(o.price(), 0)));
                }
            }
            out.add(new PurchasePointSpec(orDefault(p.id(), 0), p.name(), point(p.location()),
                orDefault(p.interactionRange(), PurchaseRules.DEFAULT_INTERACTION_RANGE), offers));
        }
        return out;
    }

    static List<MysteryBoxPointSpec> mysteryBoxPoints(List<MysteryBoxPointDto> dtos) {
        if (dtos == null) return List.of();
        List<MysteryBoxPointSpec> out = new ArrayList<>();
        for (MysteryBoxPointDto p : dtos) {
            if (p == null) continue;
            MysteryBoxPoolDto poolDto = p.pool();
            MysteryBoxVariant variant = parseVariant(poolDto == null ? null : poolDto.variant());
            List<BoxEntrySpec> entries = new ArrayList<>();
            List<MysteryBoxEntry> coreEntries = new ArrayList<>();
            if (poolDto != null && poolDto.entries() != null) {
                List<MysteryBoxEntryDto> sorted = new ArrayList<>(poolDto.entries());
                sorted.removeIf(Objects::isNull);
                sorted.sort(Comparator.comparingInt(e -> orDefault(e.sortOrder(), 0)));
                for (MysteryBoxEntryDto e : sorted) {
                    int bp = basisPoints(e.chanceBasisPoints(), e.chancePercent());
                    List<BoxEnchantmentSpec> chances = new ArrayList<>();
                    List<EnchantmentChance> coreChances = new ArrayList<>();
                    if (e.enchantments() != null) {
                        for (MysteryBoxEntryEnchantmentDto c : e.enchantments()) {
                            if (c == null || c.enchantmentDefinitionId() == null) continue;
                            int cbp = basisPoints(c.chanceBasisPoints(), c.chancePercent());
                            chances.add(new BoxEnchantmentSpec(c.enchantmentDefinitionId(), c.enchantmentKey(), orDefault(c.level(), 1), cbp));
                            if (cbp > 0) {
                                coreChances.add(new EnchantmentChance(String.valueOf(c.enchantmentDefinitionId()), Math.max(1, orDefault(c.level(), 1)),
                                    Math.min(BasisPoints.WHOLE, cbp)));
                            }
                        }
                    }
                    entries.add(new BoxEntrySpec(orDefault(e.id(), 0),
                        e.itemBlueprintId() == null ? OptionalInt.empty() : OptionalInt.of(e.itemBlueprintId()), e.itemBlueprintName(),
                        e.enchantmentDefinitionId() == null ? OptionalInt.empty() : OptionalInt.of(e.enchantmentDefinitionId()),
                        e.enchantmentKey(), orDefault(e.enchantmentLevel(), 1), bp, chances));
                    if (bp > 0) {
                        if (variant == MysteryBoxVariant.ENCHANTED_BOOKS && e.enchantmentDefinitionId() != null) {
                            coreEntries.add(new MysteryBoxEntry.BookEntry(String.valueOf(e.enchantmentDefinitionId()),
                                Math.max(1, orDefault(e.enchantmentLevel(), 1)), bp));
                        } else if (e.itemBlueprintId() != null) {
                            coreEntries.add(new MysteryBoxEntry.ItemEntry(String.valueOf(e.itemBlueprintId()), bp, coreChances));
                        }
                    }
                }
            }
            MysteryBoxPool pool = new MysteryBoxPool(variant, coreEntries);
            out.add(new MysteryBoxPointSpec(orDefault(p.id(), 0), p.name(), point(p.location()),
                orDefault(p.interactionRange(), PurchaseRules.DEFAULT_INTERACTION_RANGE), p.blockType(),
                p.rollPrice() == null ? MysteryBoxSettings.DEFAULT_ROLL_PRICE : p.rollPrice(),
                p.claimTimeoutMillis() == null ? MysteryBoxSettings.DEFAULT_CLAIM_TIMEOUT_MILLIS : p.claimTimeoutMillis(),
                variant, entries, pool));
        }
        return out;
    }

    static MysteryBoxVariant parseVariant(String value) {
        if (value == null) return MysteryBoxVariant.ITEMS;
        String v = value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return v.startsWith("enchant") || v.equals("books") ? MysteryBoxVariant.ENCHANTED_BOOKS : MysteryBoxVariant.ITEMS;
    }

    /** The served basis points win (S4-11); a percent-only entry is converted (S1-15), an unconvertible one counts 0. */
    static int basisPoints(Integer basisPoints, BigDecimal percent) {
        if (basisPoints != null) return Math.max(0, basisPoints);
        if (percent == null) return 0;
        try {
            return BasisPoints.fromPercent(percent.doubleValue());
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    static Readiness readiness(ReadinessDto dto) {
        if (dto == null) return new Readiness(true, List.of(), List.of());
        return new Readiness(!Boolean.FALSE.equals(dto.isReady()), issues(dto.errors()), issues(dto.warnings()));
    }

    private static List<String> issues(List<ReadinessIssueDto> issues) {
        if (issues == null) return List.of();
        List<String> out = new ArrayList<>();
        for (ReadinessIssueDto i : issues) {
            if (i == null) continue;
            String code = i.code() == null ? "?" : i.code();
            out.add(i.message() == null ? code : code + ": " + i.message());
        }
        return out;
    }

    static Optional<WorldPoint> point(LocationDto dto) {
        if (dto == null || dto.x() == null || dto.y() == null || dto.z() == null) return Optional.empty();
        return Optional.of(new WorldPoint(dto.world(), dto.x(), dto.y(), dto.z(), dto.yaw() == null ? 0f : dto.yaw()));
    }

    // ---- helpers ----

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static double orDefault(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static OptionalDouble optional(Double value) {
        return value == null || !Double.isFinite(value) || value <= 0 ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
