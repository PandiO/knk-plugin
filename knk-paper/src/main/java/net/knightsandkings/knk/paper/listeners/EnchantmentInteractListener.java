package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentType;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.enchantment.EnchantmentPermissions;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EnchantmentInteractListener implements Listener {
    private static final String DEFAULT_COOLDOWN_MESSAGE = "&c%seconds% seconds remaining";

    private final EnchantmentRepository enchantmentRepository;
    private final EnchantmentExecutor enchantmentExecutor;
    private final CooldownManager cooldownManager;
    private final boolean disableForCreative;
    private final String cooldownMessageTemplate;
    private final EnchantmentPermissions permissions;

    public EnchantmentInteractListener(
            EnchantmentRepository enchantmentRepository,
            EnchantmentExecutor enchantmentExecutor,
            CooldownManager cooldownManager,
            boolean disableForCreative,
            String cooldownMessageTemplate
    ) {
        this(enchantmentRepository, enchantmentExecutor, cooldownManager, disableForCreative, cooldownMessageTemplate,
                EnchantmentPermissions.bukkitOnly());
    }

    /** @param permissions who may use each ability: Bukkit or a rank grant (PandiO/knk-workspace#193) */
    public EnchantmentInteractListener(
            EnchantmentRepository enchantmentRepository,
            EnchantmentExecutor enchantmentExecutor,
            CooldownManager cooldownManager,
            boolean disableForCreative,
            String cooldownMessageTemplate,
            EnchantmentPermissions permissions
    ) {
        this.permissions = permissions != null ? permissions : EnchantmentPermissions.bukkitOnly();
        this.enchantmentRepository = enchantmentRepository;
        this.enchantmentExecutor = enchantmentExecutor;
        this.cooldownManager = cooldownManager;
        this.disableForCreative = disableForCreative;
        this.cooldownMessageTemplate = cooldownMessageTemplate == null || cooldownMessageTemplate.isBlank()
                ? DEFAULT_COOLDOWN_MESSAGE
                : cooldownMessageTemplate;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isRightClick(event.getAction())) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (disableForCreative && player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack itemInHand = event.getItem();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            itemInHand = player.getInventory().getItemInMainHand();
        }
        if (itemInHand == null || itemInHand.getType().isAir()) {
            return;
        }

        ItemMeta itemMeta = itemInHand.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        Map<String, Integer> enchantments = enchantmentRepository.getEnchantments(lore).join();
        if (enchantments.isEmpty()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Map<String, Integer> activatableSupportEnchantments = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            String enchantmentId = entry.getKey();
            int level = entry.getValue() == null ? 0 : entry.getValue();
            if (level < 1) {
                continue;
            }

            var definition = EnchantmentRegistry.getInstance().getById(enchantmentId).orElse(null);
            if (definition == null || definition.type() != EnchantmentType.SUPPORT) {
                continue;
            }

            if (!permissions.canUse(player, enchantmentId)) {
                continue;
            }

            long remainingCooldownMs = cooldownManager.getRemainingCooldown(playerId, enchantmentId).join();
            if (remainingCooldownMs > 0L) {
                sendCooldownMessage(player, remainingCooldownMs);
                continue;
            }

            activatableSupportEnchantments.put(enchantmentId, level);
        }

        if (activatableSupportEnchantments.isEmpty()) {
            return;
        }

        boolean triggered = enchantmentExecutor.executeOnInteract(activatableSupportEnchantments, playerId).join();
        if (triggered) {
            event.setCancelled(true);
        }
    }

    private void sendCooldownMessage(Player player, long remainingCooldownMs) {
        long remainingSeconds = Math.max(1L, (long) Math.ceil(remainingCooldownMs / 1000.0d));
        String message = cooldownMessageTemplate.replace("%seconds%", Long.toString(remainingSeconds));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }
}
