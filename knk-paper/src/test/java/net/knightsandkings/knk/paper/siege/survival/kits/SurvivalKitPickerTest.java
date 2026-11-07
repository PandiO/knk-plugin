package net.knightsandkings.knk.paper.siege.survival.kits;

import net.knightsandkings.knk.core.survival.kits.KitSelection;
import net.knightsandkings.knk.core.survival.kits.KitSelection.KitOption;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-lobby selections and the pick replies (the menu itself needs a server). */
class SurvivalKitPickerTest {

    private final SurvivalKitPicker picker = new SurvivalKitPicker();
    private final UUID player = UUID.randomUUID();

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void picksByIdAndByNameUntilLocked() {
        picker.begin(1, new KitSelection(List.of(new KitOption(3, "Archer", 0), new KitOption(5, "Knight", 1)), OptionalInt.of(5)));
        assertTrue(plain(picker.pick(player, 1, 3)).contains("Kit picked: Archer"));
        assertTrue(plain(picker.pick(player, 1, 9)).contains("not one of this scenario's kits"));
        assertTrue(plain(picker.pickByName(player, 1, "kni")).contains("Kit picked: Knight"));
        assertTrue(plain(picker.pickByName(player, 1, "nope")).contains("Archer, Knight"));
        assertEquals(5, picker.selection(1).orElseThrow().resolve(player).orElseThrow().kitId());
        picker.lock(1);
        assertTrue(plain(picker.pick(player, 1, 3)).contains("final"));
        assertTrue(plain(picker.pick(player, 2, 3)).contains("no kits to pick"));
        picker.forget(1);
        assertTrue(picker.selection(1).isEmpty());
        assertEquals(List.of("Archer", "Knight"), SurvivalKitPicker.names(
            new KitSelection(List.of(new KitOption(3, "Archer", 0), new KitOption(5, "Knight", 1)), OptionalInt.empty())));
    }
}
