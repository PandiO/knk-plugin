package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.StakeItem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The item references of offers (arena duels DESIGN §4.4 {@code ItemRef}, D3): items never move while players
 * negotiate. The proposal editor (P5) registers a copy of each stack a player offers and puts the returned
 * {@link StakeItem} into the contract; lock-in finds the real stack again by {@code isSimilar} + amount (the slot it
 * came from first) and takes exactly that amount. Main thread.
 */
public final class DuelStakeItems {

    /** One offered stack: who owns it, a copy, the slot it was in (a hint), and its coin value for the ratio rule. */
    public record Ref(UUID owner, ItemStack copy, int slotHint, long value) {
        public Ref {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(copy, "copy");
            copy = copy.clone();
        }
    }

    /** Where a referenced stack was found at lock-in. */
    public record Found(int slot, ItemStack taken) {}

    private final Map<String, Ref> refs = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();

    /**
     * Register {@code stack} (from {@code slot} of {@code owner}'s inventory) for an offer.
     *
     * @param value the stack's coin value (P3: the blueprint's base price × amount; 0 when unknown)
     */
    public StakeItem register(UUID owner, ItemStack stack, int slot, long value) {
        String refId = owner + ":" + ids.incrementAndGet();
        refs.put(refId, new Ref(owner, stack, slot, value));
        return new StakeItem(refId, stack.getType().name(), stack.getAmount());
    }

    public Optional<Ref> get(String refId) {
        return Optional.ofNullable(refs.get(refId));
    }

    /** P3's {@code itemValue} for the offer book and the validator. */
    public long valueOf(StakeItem item) {
        Ref ref = refs.get(item.refId());
        return ref == null ? 0 : ref.value();
    }

    /** The player left or their negotiations closed: drop their references. */
    public void forget(UUID owner) {
        refs.values().removeIf(ref -> ref.owner().equals(owner));
    }

    /**
     * Find every stake item of {@code owner} in their inventory without changing anything: each needs its own slot
     * with a similar stack of at least the offered amount. Empty when one is missing.
     */
    public Optional<List<Found>> locate(Player owner, List<StakeItem> stake) {
        PlayerInventory inventory = owner.getInventory();
        ItemStack[] contents = inventory.getContents();
        int[] left = new int[contents.length];
        for (int i = 0; i < contents.length; i++) left[i] = contents[i] == null ? 0 : contents[i].getAmount();
        List<Found> found = new ArrayList<>();
        for (StakeItem item : stake) {
            Ref ref = refs.get(item.refId());
            if (ref == null || !ref.owner().equals(owner.getUniqueId())) return Optional.empty();
            int slot = slotOf(contents, left, ref, item.amount());
            if (slot < 0) return Optional.empty();
            left[slot] -= item.amount();
            ItemStack taken = contents[slot].clone();
            taken.setAmount(item.amount());
            found.add(new Found(slot, taken));
        }
        return Optional.of(found);
    }

    /** Take what {@link #locate} found (same tick). */
    public static void take(Player owner, List<Found> found) {
        PlayerInventory inventory = owner.getInventory();
        for (Found f : found) {
            ItemStack current = inventory.getItem(f.slot());
            if (current == null) continue;
            int rest = current.getAmount() - f.taken().getAmount();
            if (rest <= 0) {
                inventory.setItem(f.slot(), null);
            } else {
                current.setAmount(rest);
                inventory.setItem(f.slot(), current);
            }
        }
        owner.updateInventory();
    }

    private static int slotOf(ItemStack[] contents, int[] left, Ref ref, int amount) {
        int hint = ref.slotHint();
        if (hint >= 0 && hint < contents.length && matches(contents[hint], left[hint], ref, amount)) return hint;
        for (int i = 0; i < contents.length; i++) {
            if (matches(contents[i], left[i], ref, amount)) return i;
        }
        return -1;
    }

    private static boolean matches(ItemStack stack, int left, Ref ref, int amount) {
        return stack != null && left >= amount && stack.isSimilar(ref.copy());
    }
}
