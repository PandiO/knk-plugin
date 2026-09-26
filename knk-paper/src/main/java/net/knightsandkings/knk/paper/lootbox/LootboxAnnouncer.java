package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Lootbox chat (docs/specs/lootboxes/DESIGN.md §3.4 "Messages / UX"): the finder's "You opened …" line, the rare-drop
 * broadcast and the spawn broadcast, each with the item's name hoverable (Adventure {@code showItem} through
 * {@link ItemStack#displayName()}). The API decides whether a drop is announced ({@code announce} on the claim), so a
 * claim is broadcast at most once; the templates come from the runtime config.
 */
public final class LootboxAnnouncer {

    static final String DEFAULT_DROP_TEMPLATE = "&6{player} &efound {item} &ein a {box}!";
    static final String DEFAULT_SPAWN_TEMPLATE = "&eA {box} &eappeared in &6{area}&e!";
    static final String OPENED_TEMPLATE = "&aYou opened a {box} &aand found {item}&a!";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Consumer<Component> broadcaster;

    public LootboxAnnouncer(Consumer<Component> broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** The finder's line, a chest-open sound, and for an announced drop the broadcast and a toast sound. */
    public void opened(Player finder, KnkLootboxClaimResult claim, ItemStack item, LootboxSettings settings, KnkLootboxRuntimeConfig config) {
        String box = settings.coloredLabel(claim.boxLabel(), claim.boxStars());
        Component itemText = itemName(item, claim);
        finder.sendMessage(render(OPENED_TEMPLATE, Map.of("{box}", box), itemText));
        finder.playSound(finder.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.0f);
        if (claim.announce()) {
            broadcaster.accept(render(templateOr(config.dropAnnouncementTemplate(), DEFAULT_DROP_TEMPLATE),
                    Map.of("{player}", finder.getName(), "{box}", box), itemText));
            finder.playSound(finder.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    /** A spawn broadcast, for boxes of at least {@code announceSpawnMinBoxStars} (off while boxes are ★1-5 by default). */
    public void spawned(KnkLootboxSpawn spawn, LootboxSettings settings, KnkLootboxRuntimeConfig config) {
        if (spawn.boxStars() < config.announceSpawnMinBoxStars()) {
            return;
        }
        String area = spawn.spawnAreaName() != null ? spawn.spawnAreaName() : spawn.world();
        broadcaster.accept(render(templateOr(config.spawnAnnouncementTemplate(), DEFAULT_SPAWN_TEMPLATE),
                Map.of("{box}", settings.coloredLabel(spawn.boxLabel(), spawn.boxStars()), "{area}", area), null));
    }

    /**
     * {@code template} with its text placeholders replaced (their values may carry {@code &} colours), parsed as
     * {@code &}-legacy text, then {@code {item}} replaced by the item component.
     */
    static Component render(String template, Map<String, String> placeholders, Component item) {
        String text = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        Component component = LEGACY.deserialize(text);
        if (item != null) {
            component = component.replaceText(TextReplacementConfig.builder().matchLiteral("{item}").replacement(item).build());
        }
        return component;
    }

    /** The item's hoverable name ({@code 16x Bread} for a stack), or the claim's item name when there is no stack. */
    public static Component itemName(ItemStack item, KnkLootboxClaimResult claim) {
        Component name = item != null ? item.displayName() : null;
        if (name == null) {
            name = LEGACY.deserialize(claim.itemName() != null ? claim.itemName() : "an item");
        }
        int amount = item != null ? item.getAmount() : claim.quantity();
        return amount > 1 ? Component.text(amount + "x ").append(name) : name;
    }

    private static String templateOr(String template, String fallback) {
        return template == null || template.isBlank() ? fallback : template;
    }
}
