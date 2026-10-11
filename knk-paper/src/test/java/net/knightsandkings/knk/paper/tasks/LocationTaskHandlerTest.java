package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.utils.PlaceholderInterpolationUtil;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LocationTaskHandlerTest {
    @Test
    void pausedTaskDoesNotHandleBlockRightClick() {
        LocationTaskHandler handler = new LocationTaskHandler(
                mock(WorldTasksApi.class),
                mock(Plugin.class)
        );
        Player player = mock(Player.class);
        Block block = mock(Block.class);
        handler.startTask(player, 89, "{}");
        handler.onPlayerChat(player, "pause");

        boolean handled = handler.onBlockRightClick(player, block);

        assertFalse(handled);
        verify(block, never()).getLocation();
    }

    // KNG-131: the rule's message is filled the way the API fills it, whatever the dependency looks like.
    @Test
    void failureMessageFillsCoordinatesAndTheParentsName() {
        JsonObject gate = JsonParser.parseString("{\"id\":21,\"name\":\"Main Gate\",\"wgRegionId\":\"domain_21\"}")
                .getAsJsonObject();

        String message = PlaceholderInterpolationUtil.interpolate(
                "Location {coordinates} is outside {parentEntityName}'s boundaries.",
                LocationTaskHandler.computedPlaceholders(gate, "domain_21", -967.0, 113.0, 1653.5));

        assertEquals("Location (-967.00, 113.00, 1653.50) is outside Main Gate's boundaries.", message);
    }

    @Test
    void parentNameFallsBackToTheRegionWhenTheDependencyIsJustTheRegionId() {
        JsonObject placeholders = LocationTaskHandler.computedPlaceholders(
                new JsonPrimitive("town_kardenna"), "town_kardenna", 1, 2, 3);

        assertEquals("town_kardenna", placeholders.get("parentEntityName").getAsString());
    }

    @Test
    void aMissingRegionIsNamedInsteadOfCallingTheLocationOutside() {
        assertEquals("Cannot check the location: region '21' does not exist in world 'knk_hub'. "
                        + "Check the form's region before saving.",
                LocationTaskHandler.regionNotFoundMessage("21", "knk_hub"));
    }
}
