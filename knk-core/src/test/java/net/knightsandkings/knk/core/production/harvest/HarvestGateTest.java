package net.knightsandkings.knk.core.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#134 (PRODUCTION_KINDS § 6.1, A16): who may harvest what in the yield areas. A qualified player is
 * allowed; an unqualified one is refused with the first unmet condition; everything outside the areas or not a target is
 * left alone; nothing is a harvest while the API's flag is off.
 */
class HarvestGateTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final HarvestGate.Tool IRON_PICK = new HarvestGate.Tool("IRON_PICKAXE", 0);

    private final Set<String> granted = new HashSet<>(Set.of("knk.harvest.quarry"));
    private final RequirementEvaluator requirements = (player, r) ->
            r.permission() != null && !granted.contains(r.permission()) ? "you need the permission " + r.permission() : null;
    private final HarvestCooldowns cooldowns = new HarvestCooldowns();

    private HarvestGate gate(HarvestCatalog catalog, HarvestGate.Regenerating regenerating, boolean denyAtEmptyPool) {
        return new HarvestGate(catalog, HarvestFixtures.index(catalog), requirements, cooldowns, regenerating, denyAtEmptyPool);
    }

    private HarvestGate quarry(double fill, boolean productionEnabled) {
        return gate(HarvestFixtures.catalog(List.of(HarvestFixtures.stone()),
                HarvestFixtures.cuboid(5, 4, HarvestFixtures.QUARRY, productionEnabled, fill)), HarvestGate.Regenerating.NONE, true);
    }

    @Test
    void aQualifiedPlayerMayHarvestATargetInTheArea() {
        HarvestGate.Decision d = quarry(1, true).block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0);
        assertEquals(HarvestGate.Kind.ALLOW, d.kind());
        assertEquals(7, d.rule().id());
        assertEquals(5, d.area().areaId());
        assertNull(d.reason());
    }

    @Test
    void outsideTheAreasOrNotATargetIsLeftAlone() {
        HarvestGate gate = quarry(1, true);
        assertEquals(HarvestGate.Kind.IGNORE, gate.block(PLAYER, "world", 30, 64, 3, "minecraft:stone", IRON_PICK, 0).kind());
        assertEquals(HarvestGate.Kind.IGNORE, gate.block(PLAYER, "world", 3, 64, 3, "minecraft:dirt", IRON_PICK, 0).kind());
        assertEquals(HarvestGate.Kind.IGNORE, gate.block(PLAYER, "nether", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).kind());
        assertEquals(HarvestGate.Kind.IGNORE, gate.block(PLAYER, "world", 3, 80, 3, "minecraft:stone", IRON_PICK, 0).kind());
    }

    @Test
    void withTheApiFlagOffNothingIsAHarvest() {
        HarvestCatalog off = new HarvestCatalog(false, null, List.of(HarvestFixtures.stone()),
                List.of(HarvestFixtures.cuboid(5, 4, HarvestFixtures.QUARRY, true, 1)));
        assertEquals(HarvestGate.Kind.IGNORE,
                gate(off, HarvestGate.Regenerating.NONE, false).block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).kind());
        assertEquals(HarvestGate.Kind.IGNORE, new HarvestGate(null, null, null, null, null, false)
                .block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).kind());
    }

    @Test
    void anUnqualifiedPlayerIsRefusedWithTheReason() {
        HarvestGate gate = quarry(1, true);
        granted.clear();
        assertEquals("you need the permission knk.harvest.quarry",
                gate.block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).reason());
        granted.add("knk.harvest.quarry");
        HarvestGate.Decision wood = gate.block(PLAYER, "world", 3, 64, 3, "minecraft:coal_ore",
                new HarvestGate.Tool("WOODEN_PICKAXE", 0), 0);
        assertEquals(HarvestGate.Kind.DENY, wood.kind());
        assertEquals("needs a stone tool or better", wood.reason());
        assertEquals("needs a pickaxe", gate.block(PLAYER, "world", 3, 64, 3, "minecraft:stone", HarvestGate.Tool.HAND, 0).reason());
    }

    @Test
    void aSwitchedOffStructureOrAnEmptyPoolRefuses() {
        assertEquals("production at Pit is switched off",
                quarry(1, false).block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).reason());
        assertEquals("Pit is exhausted for now; come back later",
                quarry(0, true).block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).reason());
        HarvestCatalog catalog = HarvestFixtures.catalog(List.of(HarvestFixtures.stone()),
                HarvestFixtures.cuboid(5, 4, HarvestFixtures.QUARRY, true, 0));
        assertEquals(HarvestGate.Kind.ALLOW, gate(catalog, HarvestGate.Regenerating.NONE, false) // the default: book it anyway
                .block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).kind());
        HarvestGate live = new HarvestGate(catalog, HarvestFixtures.index(catalog), requirements, cooldowns,
                HarvestGate.Regenerating.NONE, true, area -> 0.4); // the latest report answer wins over the catalogue
        assertEquals(HarvestGate.Kind.ALLOW, live.block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0).kind());
    }

    @Test
    void cropsMustBeRipeAndTheCooldownHolds() {
        HarvestGate gate = gate(HarvestFixtures.catalog(List.of(HarvestFixtures.wheat()),
                HarvestFixtures.cuboid(6, 2, HarvestFixtures.FARM, true, 1)), HarvestGate.Regenerating.NONE, false);
        assertEquals("it isn't ripe yet",
                gate.block(PLAYER, "world", 1, 61, 1, "minecraft:wheat[age=3]", HarvestGate.Tool.HAND, 0).reason());
        HarvestGate.Decision ripe = gate.block(PLAYER, "world", 1, 61, 1, "minecraft:wheat[age=7]", HarvestGate.Tool.HAND, 1000);
        assertEquals(HarvestGate.Kind.ALLOW, ripe.kind());
        cooldowns.started(PLAYER, 6, ripe.rule(), 1000);
        assertEquals("wait 10 s", gate.block(PLAYER, "world", 2, 61, 1, "minecraft:wheat[age=7]", HarvestGate.Tool.HAND, 1000).reason());
        assertEquals("wait 1 s", gate.block(PLAYER, "world", 2, 61, 1, "minecraft:wheat[age=7]", HarvestGate.Tool.HAND, 10_500).reason());
        assertEquals(HarvestGate.Kind.ALLOW,
                gate.block(PLAYER, "world", 2, 61, 1, "minecraft:wheat[age=7]", HarvestGate.Tool.HAND, 11_000).kind());
        assertEquals(HarvestGate.Kind.ALLOW, // another player has no cooldown
                gate.block(UUID.randomUUID(), "world", 2, 61, 1, "minecraft:wheat[age=7]", HarvestGate.Tool.HAND, 2000).kind());
    }

    @Test
    void aBlockWaitingToRegenerateCannotBeBroken() {
        HarvestCatalog catalog = HarvestFixtures.catalog(List.of(HarvestFixtures.stone()),
                HarvestFixtures.cuboid(5, 4, HarvestFixtures.QUARRY, true, 1));
        HarvestGate gate = gate(catalog, (w, x, y, z) -> x == 3 && y == 64 && z == 3, false);
        HarvestGate.Decision d = gate.block(PLAYER, "world", 3, 64, 3, "minecraft:bedrock", IRON_PICK, 0);
        assertEquals(HarvestGate.Kind.DENY, d.kind());
        assertNull(d.rule());
        assertEquals("this block is regenerating", d.reason());
    }

    @Test
    void overlappingAreasTryTheLowestIdAndTheFirstRuleThePlayerMeets() {
        HarvestRule strict = new HarvestRule(20, "strict", HarvestFixtures.QUARRY, HarvestRule.TargetKind.BLOCK,
                List.of("STONE"), 0, "knk.harvest.master", null, null, null, 0, 50, HarvestRule.Placeholder.AIR, null, 5);
        HarvestCatalog catalog = HarvestFixtures.catalog(List.of(strict, HarvestFixtures.stone()),
                HarvestFixtures.cuboid(9, 8, HarvestFixtures.QUARRY, true, 1), HarvestFixtures.cuboid(5, 4, HarvestFixtures.QUARRY, true, 1));
        HarvestGate.Decision d = gate(catalog, HarvestGate.Regenerating.NONE, false)
                .block(PLAYER, "world", 3, 64, 3, "minecraft:stone", IRON_PICK, 0);
        assertEquals(HarvestGate.Kind.ALLOW, d.kind());
        assertEquals(5, d.area().areaId()); // lowest id first
        assertEquals(7, d.rule().id());     // the strict rule needs a node the player lacks
    }

    @Test
    void entitiesFollowTheirRules() {
        HarvestGate gate = gate(HarvestFixtures.catalog(List.of(HarvestFixtures.cows()),
                HarvestFixtures.cuboid(6, 2, HarvestFixtures.FARM, true, 1)), HarvestGate.Regenerating.NONE, false);
        assertEquals(HarvestGate.Kind.ALLOW, gate.entity(PLAYER, "world", 4, 62, 4, "COW", HarvestGate.Tool.HAND, 0).kind());
        assertEquals(HarvestGate.Kind.IGNORE, gate.entity(PLAYER, "world", 4, 62, 4, "PIG", HarvestGate.Tool.HAND, 0).kind());
        assertEquals(HarvestGate.Kind.IGNORE, gate.block(PLAYER, "world", 4, 62, 4, "minecraft:cow", HarvestGate.Tool.HAND, 0).kind());
    }
}
