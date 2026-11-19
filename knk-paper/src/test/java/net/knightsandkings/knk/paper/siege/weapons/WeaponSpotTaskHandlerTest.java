package net.knightsandkings.knk.paper.siege.weapons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import net.knightsandkings.knk.paper.listeners.WorldTaskChatListener;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import net.knightsandkings.knk.paper.tasks.WorldTaskLifecycle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Siege weapons phase 2 (#783, #799), A02-A03: the {@code SiegeWeaponSpot} world task handler on a MockBukkit world.
 * WorldEdit and WorldGuard are replaced by a {@link SpotSelections} that returns fixed bounds; the floor and volume
 * checks read real blocks. The task completes with the result the web form spreads over its fields.
 */
class WeaponSpotTaskHandlerTest {

    private static final int TASK_ID = 57;
    /** 7 along x, 5 high, 9 along z, standing on y 63. */
    private static final SpotBounds SEVEN_BY_NINE = new SpotBounds(0, 64, 0, 6, 68, 8);
    private static final float LOOK_NORTH = 180f;

    private ServerMock server;
    private WorldMock world;
    private PlayerMock admin;
    private WorldTasksApi api;
    private final List<String> completed = new ArrayList<>();
    private CompletableFuture<Object> nextResult;
    private FakeSelections selections;
    private WorldTaskLifecycle lifecycle;
    private WeaponSpotTaskHandler handler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new WorldMock(Material.AIR, 0);
        world.setName("siege");
        server.addWorld(world);
        admin = server.addPlayer();
        admin.teleport(new Location(world, 3.5, 64, -3.5, LOOK_NORTH, 0f));
        api = mock(WorldTasksApi.class);
        nextResult = CompletableFuture.completedFuture(null);
        when(api.complete(anyInt(), anyString())).thenAnswer(call -> {
            completed.add(call.getArgument(1));
            @SuppressWarnings({"unchecked", "rawtypes"})
            CompletableFuture result = nextResult;
            return result;
        });
        selections = new FakeSelections();
        lifecycle = mock(WorldTaskLifecycle.class);
        handler = new WeaponSpotTaskHandler(api, selections, BukkitBlockProbe::new, Runnable::run, () -> 99L);
        handler.setLifecycle(lifecycle);
        fill(SEVEN_BY_NINE.minX(), 63, SEVEN_BY_NINE.minZ(), SEVEN_BY_NINE.maxX(), 63, SEVEN_BY_NINE.maxZ(), Material.STONE);
        fill(SEVEN_BY_NINE.minX(), 64, SEVEN_BY_NINE.minZ(), SEVEN_BY_NINE.maxX(), 68, SEVEN_BY_NINE.maxZ(), Material.AIR);
        handler.startTask(admin, TASK_ID, "{}");
        messages();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void save_TakesBoundsAndTheLookDirection_SizeClassSmall_AndConfirmCompletesTheTaskWithTheResult() {
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        assertTrue(handler.onPlayerChat(admin, "save"));
        List<String> shown = messages();
        assertTrue(shown.contains("Facing: North (your look direction)"), shown.toString());
        assertTrue(shown.contains("Size class: Small (needs 7 wide × 9 long × 5 high)"), shown.toString());
        assertTrue(shown.stream().noneMatch(line -> line.contains("Warning")), shown.toString());

        assertTrue(handler.onPlayerChat(admin, "confirm"));
        JsonObject output = output();
        assertEquals("SiegeWeaponSpot", output.get("fieldName").getAsString());
        assertEquals("Success", output.get("status").getAsString());
        assertEquals(0, output.get("minX").getAsInt());
        assertEquals(64, output.get("minY").getAsInt());
        assertEquals(8, output.get("maxZ").getAsInt());
        assertEquals("North", output.get("facing").getAsString());
        assertEquals("Small", output.get("sizeClass").getAsString());
        assertTrue(output.get("wgRegionId").isJsonNull());
        assertEquals("siege", output.get("world").getAsString());
        assertEquals(99L, output.get("capturedAt").getAsLong());
        verify(api).complete(TASK_ID, completed.getFirst());
        assertTrue(messages().getLast().contains("Spot sent to the web form: facing North, size class Small."));
        assertFalse(handler.isHandling(admin));
    }

    @Test
    void overridingTheFacing_WorksTheClassOutAgain_EastIsRefused_SouthFitsAgain() {
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        handler.onPlayerChat(admin, "save");
        messages();

        assertTrue(handler.onPlayerChat(admin, "facing east"));
        List<String> east = messages();
        assertTrue(east.contains("Facing: East (set by you)"), east.toString());
        assertTrue(east.stream().anyMatch(line -> line.contains("Too small for a weapon facing east")
                && line.contains("It fits facing north or south")), east.toString());
        handler.onPlayerChat(admin, "confirm");
        assertTrue(completed.isEmpty(), "a refused facing must not complete the task");

        handler.onPlayerChat(admin, "facing s");
        assertTrue(messages().contains("Size class: Small (needs 7 wide × 9 long × 5 high)"));
        handler.onPlayerChat(admin, "confirm");
        assertEquals("South", output().get("facing").getAsString());
    }

    @Test
    void aFacingSetBeforeSave_IsUsedInsteadOfTheLookDirection() {
        handler.onPlayerChat(admin, "facing west");
        assertTrue(messages().getLast().contains("Facing West is used when you type 'save'"));
        selections.selection = SpotSelections.Read.of(new SpotBounds(0, 64, 0, 8, 68, 6), false);
        handler.onPlayerChat(admin, "save");
        assertTrue(messages().contains("Facing: West (set by you)"));
        handler.onPlayerChat(admin, "confirm");
        assertEquals("West", output().get("facing").getAsString());
        assertEquals("Small", output().get("sizeClass").getAsString());
    }

    @Test
    void aSelectionSmallerThanTheSmallClass_IsRefused_AndTheTaskIsNotCompleted() {
        selections.selection = SpotSelections.Read.of(new SpotBounds(0, 64, 0, 4, 68, 4), false);
        handler.onPlayerChat(admin, "save");
        List<String> shown = messages();
        assertTrue(shown.stream().anyMatch(line -> line.contains("Too small for a weapon facing north")
                && line.contains("the small class needs at least 7 wide × 9 long × 5 high")), shown.toString());
        assertTrue(shown.stream().noneMatch(line -> line.contains("Type 'confirm'")), shown.toString());

        handler.onPlayerChat(admin, "confirm");
        verify(api, never()).complete(anyInt(), anyString());
        assertTrue(messages().getLast().contains("Too small"));
        assertTrue(handler.isHandling(admin));
    }

    @Test
    void aFloorPartlyAirAndABlockInside_Warn_AndTheWarningsGoWithTheResult() {
        fill(0, 63, 0, 0, 63, 8, Material.AIR);
        world.getBlockAt(3, 65, 4).setType(Material.OAK_PLANKS);
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        handler.onPlayerChat(admin, "save");
        List<String> shown = messages();
        assertTrue(shown.stream().anyMatch(line -> line.contains("Warning: The floor isn't solid: 9 of 63")), shown.toString());
        assertTrue(shown.stream().anyMatch(line -> line.contains("Warning: The volume isn't clear: 1 blocks")), shown.toString());
        assertTrue(shown.getLast().startsWith("Type 'confirm' to send the spot to the web form anyway"), shown.toString());

        handler.onPlayerChat(admin, "confirm");
        JsonObject output = output();
        assertEquals("Warning", output.get("status").getAsString());
        assertEquals(2, output.getAsJsonArray("warnings").size());
        assertTrue(output.getAsJsonArray("warnings").get(0).getAsString().startsWith("The floor isn't solid"));
    }

    @Test
    void regionId_UsesTheWorldGuardRegionsBounds_AndSendsItsId() {
        selections.region = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        handler.onPlayerChat(admin, "region siegespot_north");
        assertEquals("siegespot_north", selections.lastRegionId);
        assertTrue(messages().getFirst().endsWith("(region siegespot_north)"));
        handler.onPlayerChat(admin, "confirm");
        assertEquals("siegespot_north", output().get("wgRegionId").getAsString());
    }

    @Test
    void noSelection_SaysHow_AndOtherChatIsNotTaken() {
        selections.selection = SpotSelections.Read.failed("You have no complete WorldEdit selection in this world.");
        handler.onPlayerChat(admin, "save");
        assertTrue(messages().getLast().contains("no complete WorldEdit selection"));
        assertFalse(handler.onPlayerChat(admin, "hello everyone"));
        handler.onPlayerChat(admin, "confirm");
        assertTrue(messages().getLast().contains("Nothing selected yet"));
    }

    @Test
    void aFailedComplete_IsShown_AndTheTaskStaysOpen_ButATaskThatMovedOnEndsIt() {
        nextResult = CompletableFuture.failedFuture(new ApiException("http://api/WorldTasks/57/complete", 500, "Request failed", ""));
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        handler.onPlayerChat(admin, "save");
        handler.onPlayerChat(admin, "confirm");
        assertTrue(messages().getLast().contains("The task could not be completed"));
        assertTrue(handler.isHandling(admin));

        nextResult = CompletableFuture.failedFuture(new ApiException("http://api/WorldTasks/57/complete", 409, "Conflict",
                "{\"message\":\"Task is Cancelled\",\"status\":\"Cancelled\"}"));
        handler.onPlayerChat(admin, "confirm");
        assertTrue(messages().getLast().contains("is no longer yours to complete"));
        assertFalse(handler.isHandling(admin));
    }

    @Test
    void cancel_HandsTheTaskBack_AndDiscardCancelsIt_WithoutCompleting() {
        assertTrue(handler.onPlayerChat(admin, "cancel"));
        verify(lifecycle).handedBack(admin, TASK_ID);
        assertFalse(handler.isHandling(admin));
        assertFalse(handler.onPlayerChat(admin, "save"));

        handler.startTask(admin, 58, "{}");
        assertTrue(handler.onPlayerChat(admin, "discard"));
        verify(lifecycle).discarded(admin, 58);
        verify(api, never()).complete(anyInt(), anyString());
    }

    @Test
    void theRegistryStartsItByTaskType_AndTheChatListenerRoutesTheTasksChatToIt() {
        handler.cancel(admin);
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        registry.registerHandler("SiegeWeaponSpot", handler);
        assertTrue(registry.startTask(admin, "SiegeWeaponSpot", "MinX", 60, "{}"));
        assertTrue(registry.isHandling(admin.getUniqueId()));
        assertTrue(registry.openTaskRefusal(admin).orElseThrow().contains("task #60 (SiegeWeaponSpot)"));

        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        WorldTaskChatListener listener = new WorldTaskChatListener(registry, null, onThisThread());
        AsyncPlayerChatEvent save = new AsyncPlayerChatEvent(true, admin, "save", new HashSet<>());
        listener.onPlayerChat(save);
        assertTrue(save.isCancelled(), "a task command is hidden from chat");
        AsyncPlayerChatEvent hello = new AsyncPlayerChatEvent(true, admin, "hello", new HashSet<>());
        listener.onPlayerChat(hello);
        assertFalse(hello.isCancelled());
        listener.onPlayerChat(new AsyncPlayerChatEvent(true, admin, "confirm", new HashSet<>()));
        verify(api).complete(60, completed.getFirst());
    }

    private static WorldTaskChatListener.MainThread onThisThread() {
        return new WorldTaskChatListener.MainThread() {
            @Override
            public boolean isMainThread() {
                return false;
            }

            @Override
            public <T> T call(java.util.concurrent.Callable<T> call, long timeoutMillis) {
                try {
                    return call.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private JsonObject output() {
        assertFalse(completed.isEmpty(), "the task was not completed");
        return JsonParser.parseString(completed.getLast()).getAsJsonObject();
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(x, y, z).setType(material);
                }
            }
        }
    }

    private List<String> messages() {
        List<String> out = new ArrayList<>();
        Component message;
        while ((message = admin.nextComponentMessage()) != null) {
            out.add(PlainTextComponentSerializer.plainText().serialize(message).replaceAll("§.", ""));
        }
        return out;
    }

    private static final class FakeSelections implements SpotSelections {
        Read selection = Read.failed("none");
        Read region = Read.failed("none");
        String lastRegionId;

        @Override
        public Read worldEditSelection(Player player) {
            return selection;
        }

        @Override
        public Read worldGuardRegion(World world, String regionId) {
            lastRegionId = regionId;
            return region;
        }
    }
}
