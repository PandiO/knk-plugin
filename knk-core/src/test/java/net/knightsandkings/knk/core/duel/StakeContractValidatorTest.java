package net.knightsandkings.knk.core.duel;

import net.knightsandkings.knk.core.duel.StakeViolation.Code;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static net.knightsandkings.knk.core.duel.DuelTestSupport.KIT_A;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.coins;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.friendly;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.item;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.settings;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.withModes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StakeContractValidatorTest {

    private static List<Code> codes(StakeContract contract, DuelArenaSettings settings) {
        return StakeContractValidator.validate(contract, settings, s -> s.material().equals("BARRIER"))
                .stream().map(StakeViolation::code).toList();
    }

    @Test
    void aFriendlyContractIsValid() {
        assertTrue(codes(friendly(), settings(3)).isEmpty());
        assertTrue(friendly().isFriendly());
        assertFalse(coins(100, 100).isFriendly());
    }

    @Test
    void dropModeIsValueMovingWithoutStakes() {
        StakeContract drop = new StakeContract(GearMode.OWN_GEAR_DROP, null, Stake.NONE, Stake.NONE);
        assertTrue(drop.isValueMoving());
        StakeContract kit = new StakeContract(GearMode.FIXED_KIT, KIT_A, Stake.NONE, Stake.NONE);
        assertTrue(kit.isFriendly());
    }

    @Test
    void gearModeMustBeAllowed() {
        DuelArenaSettings keepOnly = withModes(3, Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP);
        StakeContract drop = new StakeContract(GearMode.OWN_GEAR_DROP, null, Stake.NONE, Stake.NONE);
        assertEquals(List.of(Code.GEAR_MODE_NOT_ALLOWED), codes(drop, keepOnly));
    }

    @Test
    void fixedKitMustBeNamedAndEligible() {
        assertEquals(List.of(Code.FIXED_KIT_MISSING),
                codes(new StakeContract(GearMode.FIXED_KIT, null, Stake.NONE, Stake.NONE), settings(3)));
        assertEquals(List.of(Code.FIXED_KIT_NOT_ELIGIBLE),
                codes(new StakeContract(GearMode.FIXED_KIT, 999, Stake.NONE, Stake.NONE), settings(3)));
        assertEquals(List.of(Code.KIT_NOT_EXPECTED),
                codes(new StakeContract(GearMode.OWN_GEAR_KEEP, KIT_A, Stake.NONE, Stake.NONE), settings(3)));
        assertTrue(codes(new StakeContract(GearMode.FIXED_KIT, KIT_A, Stake.NONE, Stake.NONE), settings(3)).isEmpty());
    }

    @Test
    void kitModesNeedEligibleKits() {
        DuelArenaSettings noKits = new DuelArenaSettings(1, 3, 60, true, true, 10_000, 9, 50, 5,
                Set.of(GearMode.KIT_CHOICE), GearMode.KIT_CHOICE, List.of(), 30, 10, 300, 180, 5, 3);
        assertEquals(List.of(Code.NO_ELIGIBLE_KITS),
                codes(new StakeContract(GearMode.KIT_CHOICE, null, Stake.NONE, Stake.NONE), noKits));
    }

    @Test
    void coinBounds() {
        assertEquals(List.of(Code.NEGATIVE_COINS), codes(coins(-1, 0), settings(3)));
        assertEquals(List.of(Code.COINS_OVER_MAX), codes(coins(10_001, 10_000), settings(3)));
        assertTrue(codes(coins(10_000, 10_000), settings(3)).isEmpty());
        DuelArenaSettings noCoins = new DuelArenaSettings(1, 3, 60, false, true, 10_000, 9, 50, 5,
                Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, List.of(), 30, 10, 300, 180, 5, 3);
        assertEquals(List.of(Code.COINS_NOT_ALLOWED, Code.COINS_NOT_ALLOWED), codes(coins(5, 5), noCoins));
    }

    @Test
    void stakeRatio() {
        // 10,000 vs 0 is the one-sided pot the ratio exists to stop (DESIGN §10.2).
        List<StakeViolation> oneSided = StakeContractValidator.validate(coins(10_000, 0), settings(3), null);
        assertEquals(Code.STAKE_RATIO, oneSided.getFirst().code());
        assertEquals(DuelSide.OPPONENT, oneSided.getFirst().side());
        assertEquals("5000", oneSided.getFirst().detail());
        assertEquals(List.of(Code.STAKE_RATIO), codes(coins(10_000, 4_999), settings(3)));
        assertTrue(codes(coins(10_000, 5_000), settings(3)).isEmpty());
        assertTrue(codes(coins(4_999, 10_000), settings(3)).contains(Code.STAKE_RATIO));
        assertEquals(34, StakeContractValidator.minimumMatchingStake(67, 50), "rounded up");
    }

    @Test
    void stakeRatioIsOffAtZero() {
        DuelArenaSettings off = new DuelArenaSettings(1, 3, 60, true, true, 10_000, 9, 0, 5,
                Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, List.of(), 30, 10, 300, 180, 5, 3);
        assertTrue(codes(coins(10_000, 0), off).isEmpty());
    }

    @Test
    void itemsAreCollateralForTheRatio() {
        // DESIGN §6.2: "I stake my diamond sword, you stake 500 coins".
        StakeContract swordFor500 = new StakeContract(GearMode.OWN_GEAR_DROP, null,
                new Stake(0, List.of(item("s", "DIAMOND_SWORD"))), Stake.coins(500));
        assertTrue(codes(swordFor500, settings(3)).isEmpty());
    }

    @Test
    void itemLimits() {
        List<StakeItem> ten = IntStream.range(0, 10).mapToObj(i -> item("i" + i, "DIRT")).toList();
        assertEquals(List.of(Code.TOO_MANY_STACKS),
                codes(new StakeContract(GearMode.OWN_GEAR_KEEP, null, new Stake(0, ten), Stake.NONE), settings(3)));
        assertEquals(List.of(Code.DUPLICATE_ITEM), codes(new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("x", "DIRT"), item("x", "DIRT"))), Stake.NONE), settings(3)));
        assertEquals(List.of(Code.ITEM_BLOCKED), codes(new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("x", "BARRIER"))), Stake.NONE), settings(3)));
        DuelArenaSettings noItems = new DuelArenaSettings(1, 3, 60, true, false, 10_000, 9, 50, 5,
                Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, List.of(), 30, 10, 300, 180, 5, 3);
        assertEquals(List.of(Code.ITEMS_NOT_ALLOWED), codes(new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("x", "DIRT"))), Stake.NONE), noItems));
    }

    @Test
    void quickContractUsesTheDefaultGearMode() {
        DuelArenaSettings fixed = withModes(3, Set.of(GearMode.FIXED_KIT), GearMode.FIXED_KIT);
        StakeContract quick = StakeContract.quick(fixed);
        assertEquals(GearMode.FIXED_KIT, quick.gearMode());
        assertEquals(KIT_A, quick.fixedKitId());
        assertTrue(codes(quick, fixed).isEmpty());
    }

    @Test
    void settingsValidationAndFee() {
        assertThrows(IllegalArgumentException.class, () -> withModes(0, Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP));
        assertThrows(IllegalArgumentException.class, () -> withModes(7, Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP));
        assertThrows(IllegalArgumentException.class, () -> withModes(3, Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_DROP));
        assertEquals(49, settings(3).feeFor(999), "5% rounded down");
        assertEquals(0, settings(3).feeFor(0));
        DuelArenaSettings defaults = DuelArenaSettings.defaults(2);
        assertEquals(3, defaults.maxOffers());
        assertEquals(300, defaults.fightTimeLimitSeconds());
    }
}
