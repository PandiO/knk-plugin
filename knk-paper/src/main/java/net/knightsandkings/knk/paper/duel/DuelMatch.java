package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaBattleground;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelResult;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.DuelStateMachine;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeContract;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One running match (arena duels DESIGN §4.4 {@code DuelMatchRuntime}): the two duelists, the arena it locked in
 * with, the stake contract, the P3 state machine, and what the runtime keeps per side (lobby arrival, the own-gear
 * restore snapshot, fight telemetry). Read by P5/P6 through {@link DuelService}; changed only by the service, on the
 * main thread.
 */
public final class DuelMatch {

    /** Health, food and effects taken when an own-gear duelist leaves for the lobby (DESIGN §8.4). */
    record StatSnapshot(double health, int foodLevel, float saturation, List<PotionEffect> effects) {}

    /** Fight telemetry for the review signals (P2 contract). */
    static final class Counters {
        double damageDealt;
        double damageTaken;
        int hits;
        int attackAttempts;
        Integer armorPoints;
        String mainHand;
        Boolean hadWeapon;
    }

    private final UUID token;
    private final long negotiationId;
    private final KnkArena arena;
    private final DuelArenaSettings settings;
    private final StakeContract contract;
    private final DuelStateMachine machine;
    private final Map<DuelSide, UUID> players = new EnumMap<>(DuelSide.class);
    private final Map<DuelSide, String> names = new EnumMap<>(DuelSide.class);
    private final Map<DuelSide, Integer> userIds = new EnumMap<>(DuelSide.class);
    final Map<DuelSide, Boolean> inArena = new EnumMap<>(DuelSide.class);
    final Map<DuelSide, StatSnapshot> stats = new EnumMap<>(DuelSide.class);
    final Map<DuelSide, Counters> counters = new EnumMap<>(DuelSide.class);
    final Map<DuelSide, List<ItemStack>> stakeStacks = new EnumMap<>(DuelSide.class);
    final Map<DuelSide, Location> spawnBlock = new EnumMap<>(DuelSide.class);
    final int offerCount;
    Integer matchId;
    KnkArenaBattleground battleground;
    long fightStartTick = -1;
    DuelSide spoilsFrom;
    Settlement settlement;
    boolean released;

    DuelMatch(UUID token, long negotiationId, KnkArena arena, DuelArenaSettings settings, StakeContract contract,
              DuelStateMachine machine, Party challenger, Party opponent, int offerCount) {
        this.token = Objects.requireNonNull(token, "token");
        this.negotiationId = negotiationId;
        this.arena = Objects.requireNonNull(arena, "arena");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.contract = Objects.requireNonNull(contract, "contract");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.offerCount = offerCount;
        put(DuelSide.CHALLENGER, challenger);
        put(DuelSide.OPPONENT, opponent);
    }

    /** A duelist as the match knows them. */
    record Party(UUID playerId, String name, int userId) {}

    private void put(DuelSide side, Party party) {
        players.put(side, party.playerId());
        names.put(side, party.name());
        userIds.put(side, party.userId());
        counters.put(side, new Counters());
        inArena.put(side, false);
    }

    // ==================== Read API (P5, P6) ====================

    /** The match's public token (the API's {@code publicToken}, the spool file name). */
    public UUID token() {
        return token;
    }

    /** The API's match id, or null until the lock-in was answered. */
    public Integer matchId() {
        return matchId;
    }

    public long negotiationId() {
        return negotiationId;
    }

    public KnkArena arena() {
        return arena;
    }

    public DuelArenaSettings settings() {
        return settings;
    }

    public StakeContract contract() {
        return contract;
    }

    public DuelPhase phase() {
        return machine.phase();
    }

    public GearMode gearMode() {
        return machine.gearMode();
    }

    public UUID playerOf(DuelSide side) {
        return players.get(side);
    }

    public String nameOf(DuelSide side) {
        return names.get(side);
    }

    public int userIdOf(DuelSide side) {
        return userIds.get(side);
    }

    public Optional<DuelSide> sideOf(UUID playerId) {
        for (Map.Entry<DuelSide, UUID> e : players.entrySet()) {
            if (e.getValue().equals(playerId)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** The side's kit (kit modes), or null. */
    public Integer kitOf(DuelSide side) {
        return machine.kitOf(side);
    }

    public boolean hasPickedKit(DuelSide side) {
        return machine.hasPickedKit(side);
    }

    /** The allocated battleground, or null before the countdown. */
    public KnkArenaBattleground battleground() {
        return battleground;
    }

    /** Seconds left in the current phase (boss bar). */
    public int secondsRemaining(long nowTick) {
        return machine.secondsRemaining(nowTick);
    }

    public boolean isWaitingForBattleground() {
        return machine.isWaitingForBattleground();
    }

    /** The result, or null while the match runs. */
    public DuelResult result() {
        return machine.result();
    }

    /** What the API did with the result (PAID / HELD / REFUNDED), or null before it answered. */
    public Settlement settlement() {
        return settlement;
    }

    /** The side's open draw offer expires at this tick, or -1. */
    public long drawOfferExpiresAt(DuelSide side, long nowTick) {
        return machine.drawOfferExpiresAt(side, nowTick);
    }

    /**
     * Copies of the side's stake stacks as they were taken at lock-in, in the contract's item order (the detail
     * menu's real icons, P6); empty before the lock-in took them.
     */
    public List<ItemStack> stakeStacksOf(DuelSide side) {
        List<ItemStack> stacks = stakeStacks.get(side);
        return stacks == null ? List.of() : stacks.stream().map(ItemStack::clone).toList();
    }

    /** The tick the fight started, or -1 before it. */
    public long fightStartTick() {
        return fightStartTick;
    }

    DuelStateMachine machine() {
        return machine;
    }
}
