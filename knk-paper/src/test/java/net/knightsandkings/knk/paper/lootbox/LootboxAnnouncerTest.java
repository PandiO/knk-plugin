package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 3: announcement templates with a component in place of {@code {item}}. */
class LootboxAnnouncerTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void render_fillsTextPlaceholders_andTheItemComponent() {
        Component rendered = LootboxAnnouncer.render(LootboxAnnouncer.DEFAULT_DROP_TEMPLATE,
                Map.of("{player}", "Steve", "{box}", "&dLegendary Weapons Lootbox ★★★★★"), Component.text("Flaming Samurai"));

        assertEquals("Steve found Flaming Samurai in a Legendary Weapons Lootbox ★★★★★!", plain(rendered));
    }

    @Test
    void itemName_fallsBackToTheClaimsNameAndShowsAStackSize() {
        KnkLootboxClaimResult bread = new KnkLootboxClaimResult(1, false, 9, 12, 4, 2, "Rare Food Lootbox", null, 5, "&eBread",
                2, 2, 16, false, List.of(), false, null, null);

        assertEquals("16x Bread", plain(LootboxAnnouncer.itemName(null, bread)));
    }

    @Test
    void spawnBroadcast_onlyFromTheConfiguredGrade() {
        List<Component> broadcasts = new ArrayList<>();
        LootboxAnnouncer announcer = new LootboxAnnouncer(broadcasts::add);
        KnkLootboxRuntimeConfig config = new KnkLootboxRuntimeConfig(true, 15, 10, 5, 5, null, null, null, List.of(), List.of(), List.of());
        KnkLootboxSpawn five = new KnkLootboxSpawn(1, UUID.randomUUID(), 3, "Weapons Lootbox", "Weapons", 5, "Legendary", 5,
                "Legendary Weapons Lootbox", 1, "spawn", "world", 0, 64, 0, "Active", Instant.EPOCH, Instant.EPOCH);
        KnkLootboxSpawn four = new KnkLootboxSpawn(2, UUID.randomUUID(), 3, "Weapons Lootbox", "Weapons", 4, "Epic", 4,
                "Epic Weapons Lootbox", 1, "spawn", "world", 0, 64, 0, "Active", Instant.EPOCH, Instant.EPOCH);

        announcer.spawned(four, LootboxSettings.defaults(), config);
        announcer.spawned(five, LootboxSettings.defaults(), config);

        assertEquals(1, broadcasts.size());
        assertTrue(plain(broadcasts.get(0)).contains("Legendary Weapons Lootbox ★★★★★ appeared in spawn"));
    }
}
