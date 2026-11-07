package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.economy.KillBlow;
import net.knightsandkings.knk.paper.siege.survival.kits.SurvivalKitPicker;
import net.knightsandkings.knk.paper.siege.survival.mysterybox.MysteryBoxStations;
import net.knightsandkings.knk.paper.siege.survival.purchase.PurchaseShop;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Optional;

/**
 * The phase 4 interactions of a survival match (link S7b): right-clicks on purchase-point blocks and Mystery Box blocks,
 * the claim right-click on a shown item, the clicks in the shop and kit-picker menus, and the player hits on match
 * actors that decide the headshot/critical kill bonus (DESIGN §8). Ghosts are refused here and cancelled by the ghost
 * listener at {@code HIGHEST} anyway (S20).
 */
public final class SurvivalInteractionListener implements Listener {

    private final SurvivalMatchRuntime runtime;

    public SurvivalInteractionListener(SurvivalMatchRuntime runtime) {
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return; // one event per click
        Player player = event.getPlayer();
        Optional<SurvivalMatch> match = runtime.matchOfPlayer(player.getUniqueId());
        if (match.isEmpty()) return;
        Optional<PurchaseShop> shop = runtime.shopOf(match.get());
        if (shop.isPresent()) {
            var point = shop.get().pointAt(event.getClickedBlock());
            if (point.isPresent()) {
                event.setCancelled(true);
                if (runtime.isGhost(player.getUniqueId())) {
                    player.sendActionBar(net.knightsandkings.knk.paper.siege.SiegeMessages.bad("Ghosts cannot buy anything."));
                    return;
                }
                tell(player, shop.get().open(player, point.get()));
                return;
            }
        }
        Optional<MysteryBoxStations> boxes = runtime.boxesOf(match.get());
        if (boxes.isPresent()) {
            var station = boxes.get().stationAt(event.getClickedBlock());
            if (station.isPresent()) {
                event.setCancelled(true);
                tell(player, boxes.get().roll(player, station.get()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        claim(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        claim(event.getPlayer(), event.getRightClicked(), event);
    }

    private void claim(Player player, Entity target, org.bukkit.event.Cancellable event) {
        Optional<SurvivalMatch> match = runtime.matchOfPlayer(player.getUniqueId());
        if (match.isEmpty()) return;
        Optional<MysteryBoxStations> boxes = runtime.boxesOf(match.get());
        if (boxes.isEmpty()) return;
        var station = boxes.get().stationOf(target);
        if (station.isEmpty()) return;
        event.setCancelled(true);
        tell(player, boxes.get().claim(player, station.get()));
    }

    /** Menu clicks: every click in a shop or kit picker is cancelled; a click on a listing acts. */
    @EventHandler(priority = EventPriority.LOW)
    public void onMenuClick(InventoryClickEvent event) {
        Object holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof PurchaseShop.Holder shopHolder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
                runtime.matchOfPlayer(player.getUniqueId()).flatMap(runtime::shopOf)
                    .ifPresent(shop -> shop.click(player, shopHolder, event.getSlot()));
            }
        } else if (holder instanceof SurvivalKitPicker.Holder kitHolder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
                runtime.kitPicker().ifPresent(picker -> picker.click(player, kitHolder, event.getSlot()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMenuDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof PurchaseShop.Holder || holder instanceof SurvivalKitPicker.Holder) event.setCancelled(true);
    }

    /**
     * A player (or their projectile) hit a match actor: the hit is remembered as the possible killing blow with Paper's
     * critical flag and the head-band headshot rule ({@link KillBlow#isHeadshot}: projectile height against the mob's
     * feet and eye height).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHitsActor(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        Entity damager = event.getDamager();
        Projectile projectile = damager instanceof Projectile p ? p : null;
        if (projectile != null && projectile.getShooter() instanceof Entity shooter) damager = shooter;
        if (!(damager instanceof Player attacker)) return;
        Optional<SurvivalMatch> match = runtime.matchOfActor(mob);
        if (match.isEmpty()) return;
        boolean headshot = projectile != null
            && KillBlow.isHeadshot(projectile.getLocation().getY(), mob.getLocation().getY(), mob.getEyeHeight());
        boolean critical = event.isCritical();
        match.get().onMobHitByPlayer(mob, new KillBlow(attacker.getUniqueId(), critical, headshot));
    }

    private static void tell(Player player, Component message) {
        if (message != null) player.sendActionBar(message);
    }
}
