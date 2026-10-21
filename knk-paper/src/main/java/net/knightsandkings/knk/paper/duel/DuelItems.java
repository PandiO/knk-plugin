package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.paper.mapper.ItemGradeTag;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;
import net.knightsandkings.knk.paper.mapper.LootboxTokenTag;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the duel runtime needs to know about an item: whether it may be staked (DESIGN §7.6), the loan tag of kit
 * items ({@code knk_duel_loan}, §8.4), and the audit summary the API stores. A fake in tests.
 */
public interface DuelItems {

    /** PDC key of loaned kit items; the value is the match token. */
    NamespacedKey LOAN_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_duel_loan"));

    /** Why {@code item} can't be staked, or empty when it can. */
    Optional<String> stakeRefusal(ItemStack item);

    boolean isLoan(ItemStack item);

    /** Tag a granted kit stack as loaned for {@code token}. */
    void markLoan(ItemStack item, UUID token);

    /** The API's audit line for a stake or a spoil. */
    ItemSummary summary(ItemStack item, int ownerUserId);

    /** The real rules (Paper item meta). */
    static DuelItems paper(Set<String> blockedMaterials, boolean refuseFilledContainers) {
        Set<String> blocked = Set.copyOf(blockedMaterials);
        return new DuelItems() {
            @Override
            public Optional<String> stakeRefusal(ItemStack item) {
                if (item == null || item.isEmpty()) return Optional.of("That item is gone.");
                Material type = item.getType();
                if (blocked.contains(type.name())) return Optional.of("That item can't be staked.");
                if (type == Material.WRITTEN_BOOK) return Optional.of("Written books can't be staked.");
                if (isLoan(item)) return Optional.of("Loaned kit items can't be staked.");
                if (LootboxTokenTag.isToken(item)) return Optional.of("Lootboxes can't be staked.");
                if (refuseFilledContainers && hasContents(item)) {
                    return Optional.of("Empty a shulker box or bundle before staking it.");
                }
                return Optional.empty();
            }

            @Override
            public boolean isLoan(ItemStack item) {
                if (item == null || !item.hasItemMeta()) return false;
                return item.getItemMeta().getPersistentDataContainer().has(LOAN_KEY, PersistentDataType.STRING);
            }

            @Override
            public void markLoan(ItemStack item, UUID token) {
                ItemMeta meta = item.getItemMeta();
                if (meta == null) return;
                meta.getPersistentDataContainer().set(LOAN_KEY, PersistentDataType.STRING, token.toString());
                item.setItemMeta(meta);
            }

            @Override
            public ItemSummary summary(ItemStack item, int ownerUserId) {
                ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
                String name = meta != null && meta.hasDisplayName()
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(Objects.requireNonNull(meta.displayName()))
                        : null;
                String grade = ItemGradeTag.stars(item).map(stars -> stars + "*").orElse(null);
                return new ItemSummary(ownerUserId, item.getType().name(), item.getAmount(), name, grade,
                        ItemInstanceTag.read(item).orElse(null));
            }

            private boolean hasContents(ItemStack item) {
                if (!item.hasItemMeta()) return false;
                ItemMeta meta = item.getItemMeta();
                if (meta instanceof BundleMeta bundle) return bundle.hasItems();
                if (meta instanceof BlockStateMeta state && state.hasBlockState()
                        && state.getBlockState() instanceof ShulkerBox box) {
                    return Arrays.stream(box.getInventory().getContents()).anyMatch(s -> s != null && !s.isEmpty());
                }
                return false;
            }
        };
    }
}
