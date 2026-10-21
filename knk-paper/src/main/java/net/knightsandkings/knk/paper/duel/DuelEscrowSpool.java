package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelEscrowPlan;
import net.knightsandkings.knk.core.duel.DuelEscrowState;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.paper.minigame.MinigamePlayerVault;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The duel item escrow (arena duels DESIGN §6.5 step 2, §7.2–7.5, §8.5): one file per match,
 * {@code duel-escrow/<matchToken>.yml}, holding both players' staked stacks, drop-mode spoils, and per player the
 * stacks still owed. Coins never pass through here; they are the API's (§7.1).
 * <p>
 * <b>Write first.</b> Every change is written (temp file, then an atomic move) before the world changes: the stakes
 * before they leave the inventories, the spoils before the death drops are cleared. A write that fails changes
 * nothing, and the caller must then not take the items. Delivery is the one exception: the items are handed over
 * first and the file is written right after, in the same tick - a crash in between also loses the player's
 * unsaved inventory, so the next start delivers them again instead of losing them.
 * <p>
 * <b>States</b> follow {@link DuelEscrowState} (checked before every write): {@code LOCKING} (items taken, the API
 * not answered yet) → {@code ESCROWED} (the match exists) → {@code HELD} (a result held for review) →
 * {@code SETTLED} / {@code REFUNDED} (who is owed what is decided) → {@code CLAIM_PENDING} (something didn't fit
 * the inventory or the ender chest and waits for {@code /duel claim}). A file whose items are all delivered is
 * deleted.
 * <p>
 * <b>Spoils</b> are kept per side (the side that lost them). A refund gives each side its own spoils back (a draw
 * where both died, a voided result); a payout gives every spoil to the winner.
 * <p>
 * Main thread only. Items use the vault's codec (Paper's {@code serializeItemsAsBytes}).
 */
public final class DuelEscrowSpool {

    private static final int FORMAT_VERSION = 1;

    /** One duelist as the spool remembers them. */
    public record Party(UUID playerId, int userId, String name) {
        public Party {
            Objects.requireNonNull(playerId, "playerId");
            name = name == null ? playerId.toString() : name;
        }
    }

    /** What one {@link #deliver} handed over. */
    public record Delivery(int toInventory, int toEnderChest, int stillOwed) {
        public static final Delivery NOTHING = new Delivery(0, 0, 0);

        public boolean gaveAnything() {
            return toInventory + toEnderChest > 0;
        }

        Delivery plus(Delivery other) {
            return new Delivery(toInventory + other.toInventory, toEnderChest + other.toEnderChest,
                    stillOwed + other.stillOwed);
        }
    }

    /** A spool file in memory. */
    public static final class Entry {
        private final UUID token;
        private final int arenaId;
        private final Party challenger;
        private final Party opponent;
        private final Instant createdAt;
        private DuelEscrowState state;
        private Integer matchId;
        private final Map<DuelSide, List<ItemStack>> stakes = new EnumMap<>(DuelSide.class);
        private final Map<DuelSide, List<ItemStack>> spoils = new EnumMap<>(DuelSide.class);
        private final Map<UUID, List<ItemStack>> owed = new LinkedHashMap<>();

        public Entry(UUID token, int arenaId, Party challenger, Party opponent, List<ItemStack> challengerStake,
                     List<ItemStack> opponentStake) {
            this(token, arenaId, challenger, opponent, Instant.now(), DuelEscrowState.LOCKING, null);
            stakes.put(DuelSide.CHALLENGER, copy(challengerStake));
            stakes.put(DuelSide.OPPONENT, copy(opponentStake));
        }

        private Entry(UUID token, int arenaId, Party challenger, Party opponent, Instant createdAt,
                      DuelEscrowState state, Integer matchId) {
            this.token = Objects.requireNonNull(token, "token");
            this.arenaId = arenaId;
            this.challenger = Objects.requireNonNull(challenger, "challenger");
            this.opponent = Objects.requireNonNull(opponent, "opponent");
            this.createdAt = createdAt;
            this.state = state;
            this.matchId = matchId;
        }

        public UUID token() {
            return token;
        }

        public int arenaId() {
            return arenaId;
        }

        public DuelEscrowState state() {
            return state;
        }

        public Integer matchId() {
            return matchId;
        }

        public Party party(DuelSide side) {
            return side == DuelSide.CHALLENGER ? challenger : opponent;
        }

        public Optional<DuelSide> sideOf(UUID playerId) {
            if (challenger.playerId().equals(playerId)) return Optional.of(DuelSide.CHALLENGER);
            if (opponent.playerId().equals(playerId)) return Optional.of(DuelSide.OPPONENT);
            return Optional.empty();
        }

        public Instant createdAt() {
            return createdAt;
        }

        /** The side's staked stacks still in escrow (empty once settled). */
        public List<ItemStack> stakeOf(DuelSide side) {
            return copy(stakes.getOrDefault(side, List.of()));
        }

        /** The spoils taken from {@code side} (drop mode), still in escrow. */
        public List<ItemStack> spoilsFrom(DuelSide side) {
            return copy(spoils.getOrDefault(side, List.of()));
        }

        /** The stacks owed to {@code playerId} and not yet delivered. */
        public List<ItemStack> owedTo(UUID playerId) {
            return copy(owed.getOrDefault(playerId, List.of()));
        }

        public boolean owesAnything() {
            return owed.values().stream().anyMatch(list -> !list.isEmpty());
        }

        /** Still holds stakes or spoils nobody has been given yet. */
        boolean holdsEscrow() {
            return stakes.values().stream().anyMatch(list -> !list.isEmpty())
                    || spoils.values().stream().anyMatch(list -> !list.isEmpty());
        }

        Entry snapshot() {
            Entry copy = new Entry(token, arenaId, challenger, opponent, createdAt, state, matchId);
            stakes.forEach((side, items) -> copy.stakes.put(side, copy(items)));
            spoils.forEach((side, items) -> copy.spoils.put(side, copy(items)));
            owed.forEach((id, items) -> copy.owed.put(id, copy(items)));
            return copy;
        }

        void restoreFrom(Entry other) {
            state = other.state;
            matchId = other.matchId;
            stakes.clear();
            spoils.clear();
            owed.clear();
            other.stakes.forEach((side, items) -> stakes.put(side, copy(items)));
            other.spoils.forEach((side, items) -> spoils.put(side, copy(items)));
            other.owed.forEach((id, items) -> owed.put(id, copy(items)));
        }
    }

    private final File directory;
    private final Logger logger;
    private final MinigamePlayerVault.ItemCodec items;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public DuelEscrowSpool(File directory, Logger logger) {
        this(directory, logger, MinigamePlayerVault.ItemCodec.PAPER);
    }

    /** Tests: items without a server. */
    DuelEscrowSpool(File directory, Logger logger, MinigamePlayerVault.ItemCodec items) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.items = Objects.requireNonNull(items, "items");
    }

    // ==================== Reading ====================

    /** Startup: read every spool file (DESIGN §7.3). An unreadable file is left in place and logged. */
    public List<Entry> loadAll() {
        entries.clear();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    Entry entry = read(file);
                    entries.put(entry.token(), entry);
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "[Duel] Escrow file " + file.getName()
                            + " is unreadable; left in place for a manual check", e);
                }
            }
        }
        return List.copyOf(entries.values());
    }

    public Optional<Entry> find(UUID token) {
        return Optional.ofNullable(entries.get(token));
    }

    public Collection<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Files that still hold escrow or owe items to {@code playerId}. */
    public List<Entry> entriesOf(UUID playerId) {
        return entries.values().stream().filter(e -> e.sideOf(playerId).isPresent()).toList();
    }

    /** How many stacks wait for {@code playerId} (delivered on join or by {@code /duel claim}). */
    public int owedCount(UUID playerId) {
        return entries.values().stream().mapToInt(e -> e.owed.getOrDefault(playerId, List.of()).size()).sum();
    }

    // ==================== Lock-in ====================

    /**
     * Lock-in step 2: write the file in {@code LOCKING} with both stakes. False when it can't be written: nothing
     * may be taken from the players then.
     */
    public boolean open(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.containsKey(entry.token())) return false;
        try {
            write(entry);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "[Duel] Could not write the escrow file of match " + entry.token()
                    + "; the lock-in is refused", e);
            deleteFile(entry.token());
            return false;
        }
        entries.put(entry.token(), entry);
        return true;
    }

    /** Lock-in step 4: the API created the match. */
    public boolean escrowed(UUID token, int matchId) {
        return update(token, entry -> {
            if (entry.state == DuelEscrowState.ESCROWED && Objects.equals(entry.matchId, matchId)) return false;
            requireMove(entry, DuelEscrowState.ESCROWED);
            entry.state = DuelEscrowState.ESCROWED;
            entry.matchId = matchId;
            return true;
        });
    }

    /** The API id once it is known (a lock-in answered after a timeout, recovery). */
    public boolean setMatchId(UUID token, int matchId) {
        return update(token, entry -> {
            if (Objects.equals(entry.matchId, matchId)) return false;
            entry.matchId = matchId;
            return true;
        });
    }

    // ==================== Drop-mode spoils ====================

    /**
     * Drop mode (DESIGN §8.5): the loser's drops, or a forfeiting quitter's inventory, become spoils. Written before
     * the caller clears the drops; false = not written, so the caller must leave the items where they are.
     */
    public boolean addSpoils(UUID token, DuelSide from, List<ItemStack> stacks) {
        List<ItemStack> taken = nonEmpty(stacks);
        if (taken.isEmpty()) return true;
        return update(token, entry -> {
            if (entry.state.isFinal()) {
                throw new IllegalStateException("match " + token + " is already settled (" + entry.state + ")");
            }
            entry.spoils.computeIfAbsent(from, side -> new ArrayList<>()).addAll(taken);
            return true;
        });
    }

    // ==================== Settlement ====================

    /**
     * Apply a result (DESIGN §7.4–7.5) or a recovery decision (§7.3): who is owed which stacks, and the next state.
     * A plan that keeps everything moves the file to {@code HELD}. Idempotent: a file already settled stays as it
     * is (true).
     */
    public boolean settle(UUID token, DuelEscrowPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return update(token, entry -> {
            if (entry.state.isFinal()) return false;
            if (plan.keepsEverything()) {
                if (entry.state == DuelEscrowState.HELD) return false;
                requireMove(entry, DuelEscrowState.HELD);
                entry.state = DuelEscrowState.HELD;
                return true;
            }
            requireMove(entry, plan.next());
            owe(entry, plan.challengerStakeTo(), entry.stakes.remove(DuelSide.CHALLENGER));
            owe(entry, plan.opponentStakeTo(), entry.stakes.remove(DuelSide.OPPONENT));
            if (plan.next() == DuelEscrowState.REFUNDED) {
                for (DuelSide side : DuelSide.values()) owe(entry, side, entry.spoils.remove(side));
            } else {
                for (DuelSide side : DuelSide.values()) owe(entry, plan.spoilsTo(), entry.spoils.remove(side));
            }
            entry.state = plan.next();
            return true;
        });
    }

    /**
     * Staff destroyed a voided match's undelivered items ({@code UndeliveredItems = Destroy}, P2 contract): nothing
     * is owed any more; the file goes.
     */
    public boolean destroy(UUID token, String why) {
        Entry entry = entries.get(token);
        if (entry == null) return false;
        logger.info("[Duel] Escrow of match " + token + " destroyed: " + why);
        entries.remove(token);
        deleteFile(token);
        return true;
    }

    // ==================== Delivery ====================

    /**
     * Hand over what is owed to {@code player} (DESIGN §7.2): inventory first, then the ender chest; what fits in
     * neither stays owed and the file moves to {@code CLAIM_PENDING} ({@code /duel claim} tries again). Main thread;
     * the player must be online and alive.
     */
    public Delivery deliver(Player player) {
        UUID id = player.getUniqueId();
        Delivery total = Delivery.NOTHING;
        for (Entry entry : List.copyOf(entries.values())) {
            List<ItemStack> due = entry.owed.get(id);
            if (due == null || due.isEmpty() || !entry.state.isFinal()) continue;
            Entry before = entry.snapshot();
            int toInventory = 0;
            int toEnderChest = 0;
            List<ItemStack> left = new ArrayList<>();
            for (ItemStack stack : due) {
                ItemStack rest = give(player.getInventory(), stack);
                if (rest == null) {
                    toInventory++;
                    continue;
                }
                ItemStack restAfterChest = give(player.getEnderChest(), rest);
                if (restAfterChest == null) {
                    toEnderChest++;
                } else {
                    left.add(restAfterChest);
                }
            }
            if (left.isEmpty()) {
                entry.owed.remove(id);
            } else {
                entry.owed.put(id, left);
                if (entry.state != DuelEscrowState.CLAIM_PENDING && entry.state.canMoveTo(DuelEscrowState.CLAIM_PENDING)) {
                    entry.state = DuelEscrowState.CLAIM_PENDING;
                }
            }
            total = total.plus(new Delivery(toInventory, toEnderChest, left.size()));
            persistAfterDelivery(entry, before);
        }
        if (total.toInventory() + total.toEnderChest() + total.stillOwed() > 0) {
            player.updateInventory();
        }
        return total;
    }

    /** Whether a file of {@code token} is {@code HELD} for review. */
    public boolean isHeld(UUID token) {
        Entry entry = entries.get(token);
        return entry != null && entry.state == DuelEscrowState.HELD;
    }

    // ==================== Internals ====================

    @FunctionalInterface
    private interface Change {
        /** @return true when the entry changed and must be written */
        boolean apply(Entry entry);
    }

    /** Change an entry and write it; on a failed write the entry is put back as it was. */
    private boolean update(UUID token, Change change) {
        Entry entry = entries.get(token);
        if (entry == null) {
            logger.warning("[Duel] No escrow file for match " + token);
            return false;
        }
        Entry before = entry.snapshot();
        boolean changed;
        try {
            changed = change.apply(entry);
        } catch (IllegalStateException e) {
            entry.restoreFrom(before);
            logger.log(Level.WARNING, "[Duel] Escrow change refused for match " + token + ": " + e.getMessage());
            return false;
        }
        if (!changed) return true;
        try {
            persist(entry);
            return true;
        } catch (IOException | RuntimeException e) {
            entry.restoreFrom(before);
            logger.log(Level.SEVERE, "[Duel] Could not write the escrow file of match " + token, e);
            return false;
        }
    }

    private void persistAfterDelivery(Entry entry, Entry before) {
        try {
            persist(entry);
        } catch (IOException | RuntimeException e) {
            // The items are already handed over; keeping the old file would deliver them twice on the next join.
            logger.log(Level.SEVERE, "[Duel] Delivered items of match " + entry.token()
                    + " but could not update its escrow file; it is removed to avoid a second delivery. Owed before: "
                    + before.owed.keySet(), e);
            entries.remove(entry.token());
            deleteFile(entry.token());
        }
    }

    /** Write, or delete a file that has nothing left to hand out. */
    private void persist(Entry entry) throws IOException {
        if (entry.state.isFinal() && !entry.owesAnything() && !entry.holdsEscrow()) {
            entries.remove(entry.token());
            deleteFile(entry.token());
            return;
        }
        write(entry);
    }

    private static void requireMove(Entry entry, DuelEscrowState next) {
        if (!entry.state.canMoveTo(next)) {
            throw new IllegalStateException(entry.state + " can't move to " + next);
        }
    }

    private static void owe(Entry entry, DuelSide to, List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) return;
        if (to == null) throw new IllegalStateException("a plan must name who gets the items");
        UUID playerId = entry.party(to).playerId();
        entry.owed.computeIfAbsent(playerId, id -> new ArrayList<>()).addAll(stacks);
    }

    /** {@code addItem}'s leftover, or null when everything fit. */
    private static ItemStack give(Inventory inventory, ItemStack stack) {
        if (inventory == null) return stack;
        Map<Integer, ItemStack> rest = inventory.addItem(stack.clone());
        if (rest == null || rest.isEmpty()) return null;
        return rest.values().iterator().next();
    }

    private void write(Entry entry) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", FORMAT_VERSION);
        yaml.set("token", entry.token().toString());
        yaml.set("state", entry.state.name());
        yaml.set("matchId", entry.matchId);
        yaml.set("arenaId", entry.arenaId);
        yaml.set("createdAt", entry.createdAt.toString());
        writeParty(yaml, "challenger", entry.challenger);
        writeParty(yaml, "opponent", entry.opponent);
        for (DuelSide side : DuelSide.values()) {
            yaml.set("stakes." + side.name(), encode(entry.stakes.get(side)));
            yaml.set("spoils." + side.name(), encode(entry.spoils.get(side)));
        }
        entry.owed.forEach((id, stacks) -> {
            if (!stacks.isEmpty()) yaml.set("owed." + id, encode(stacks));
        });
        File target = file(entry.token());
        File temp = new File(directory, entry.token() + ".yml.tmp");
        yaml.save(temp);
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Entry read(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        UUID token = UUID.fromString(Objects.requireNonNull(yaml.getString("token"), "token"));
        Instant createdAt;
        try {
            createdAt = Instant.parse(yaml.getString("createdAt", Instant.EPOCH.toString()));
        } catch (RuntimeException e) {
            createdAt = Instant.EPOCH;
        }
        Entry entry = new Entry(token, yaml.getInt("arenaId"), readParty(yaml, "challenger"), readParty(yaml, "opponent"),
                createdAt, DuelEscrowState.valueOf(yaml.getString("state", DuelEscrowState.LOCKING.name())),
                yaml.contains("matchId") && yaml.get("matchId") != null ? yaml.getInt("matchId") : null);
        for (DuelSide side : DuelSide.values()) {
            List<ItemStack> stake = decode(yaml.getString("stakes." + side.name()));
            if (!stake.isEmpty()) entry.stakes.put(side, stake);
            List<ItemStack> spoil = decode(yaml.getString("spoils." + side.name()));
            if (!spoil.isEmpty()) entry.spoils.put(side, spoil);
        }
        ConfigurationSection owed = yaml.getConfigurationSection("owed");
        if (owed != null) {
            for (String key : owed.getKeys(false)) {
                List<ItemStack> stacks = decode(owed.getString(key));
                if (!stacks.isEmpty()) entry.owed.put(UUID.fromString(key), stacks);
            }
        }
        return entry;
    }

    private static void writeParty(YamlConfiguration yaml, String key, Party party) {
        yaml.set(key + ".uuid", party.playerId().toString());
        yaml.set(key + ".userId", party.userId());
        yaml.set(key + ".name", party.name());
    }

    private static Party readParty(YamlConfiguration yaml, String key) {
        return new Party(UUID.fromString(Objects.requireNonNull(yaml.getString(key + ".uuid"), key)),
                yaml.getInt(key + ".userId"), yaml.getString(key + ".name"));
    }

    private String encode(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(items.encode(stacks.toArray(ItemStack[]::new)));
    }

    private List<ItemStack> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        return nonEmpty(List.of(items.decode(Base64.getDecoder().decode(encoded))));
    }

    private File file(UUID token) {
        return new File(directory, token + ".yml");
    }

    private void deleteFile(UUID token) {
        File file = file(token);
        if (file.exists() && !file.delete()) {
            logger.warning("[Duel] Could not delete escrow file " + file.getName());
        }
    }

    private static List<ItemStack> nonEmpty(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        if (stacks == null) return result;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                result.add(stack.clone());
            }
        }
        return result;
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>(stacks == null ? 0 : stacks.size());
        if (stacks != null) for (ItemStack stack : stacks) result.add(stack == null ? null : stack.clone());
        return result;
    }
}
