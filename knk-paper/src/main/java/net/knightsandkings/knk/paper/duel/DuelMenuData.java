package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.view.DuelHudView;
import net.knightsandkings.knk.core.duel.view.DuelMatchSnapshot;
import net.knightsandkings.knk.paper.menu.content.DuelMenuFeature;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link DuelMenuFeature.Data} over the runtime (P6): snapshots, the real stake stacks, queues and claims. */
public final class DuelMenuData implements DuelMenuFeature.Data {

    private final DuelService service;

    public DuelMenuData(DuelService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public List<DuelMatchSnapshot> matches() {
        return DuelSnapshots.all(service);
    }

    @Override
    public List<ItemStack> stakeStacks(UUID token, DuelSide side) {
        return service.match(token).map(m -> m.stakeStacksOf(side)).orElse(List.of());
    }

    @Override
    public DuelHudView.QueueInfo queue(KnkArena arena) {
        return DuelSnapshots.queueOf(service, arena.id(), arena.maxQueuedMatches());
    }

    @Override
    public int pendingClaims(UUID playerId) {
        return service.pendingClaims(playerId);
    }

    @Override
    public String claim(Player player) {
        if (service.isParticipating(player.getUniqueId())) {
            return ChatColor.RED + "You can collect them once your duel is over.";
        }
        if (service.pendingClaims(player.getUniqueId()) == 0) {
            return ChatColor.GRAY + "There is nothing to collect.";
        }
        DuelEscrowSpool.Delivery delivery = service.claim(player);
        return claimMessage(delivery);
    }

    @Override
    public Optional<String> busy(UUID playerId) {
        return service.isParticipating(playerId) ? Optional.of("in a duel") : Optional.empty();
    }

    /** "Collected: 12 stacks to your inventory, 3 to your ender chest." plus what still waits. */
    static String claimMessage(DuelEscrowSpool.Delivery delivery) {
        if (delivery == null || !delivery.gaveAnything()) {
            return delivery != null && delivery.stillOwed() > 0
                    ? ChatColor.YELLOW + "Make room in your inventory or ender chest, then collect again."
                    : ChatColor.GRAY + "There is nothing to collect.";
        }
        StringBuilder out = new StringBuilder(ChatColor.GREEN + "Collected: ");
        if (delivery.toInventory() > 0) out.append(stacks(delivery.toInventory())).append(" to your inventory");
        if (delivery.toEnderChest() > 0) {
            if (delivery.toInventory() > 0) out.append(", ");
            out.append(stacks(delivery.toEnderChest())).append(" to your ender chest");
        }
        out.append('.');
        if (delivery.stillOwed() > 0) {
            out.append(ChatColor.YELLOW).append(" ").append(stacks(delivery.stillOwed()))
                    .append(" still wait: make room, then collect again.");
        }
        return out.toString();
    }

    private static String stacks(int count) {
        return count + (count == 1 ? " stack" : " stacks");
    }
}
