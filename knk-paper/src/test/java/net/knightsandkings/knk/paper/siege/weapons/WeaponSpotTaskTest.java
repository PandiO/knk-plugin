package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi.Team;
import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import net.knightsandkings.knk.core.siege.weapons.SpotFacing;
import net.knightsandkings.knk.core.siege.weapons.SpotSizeClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege weapons phase 2 (#783), A02-A03: the weapon spot task on a MockBukkit world. WorldEdit and WorldGuard are
 * replaced by a {@link SpotSelections} that returns fixed bounds; the floor and volume checks read real blocks.
 */
class WeaponSpotTaskTest {

    private static final Team ATTACKERS = new Team(11, "Red Banners", null, "Attacker");
    /** 7 along x, 5 high, 9 along z, standing on y 63. */
    private static final SpotBounds SEVEN_BY_NINE = new SpotBounds(0, 64, 0, 6, 68, 8);
    private static final float LOOK_NORTH = 180f;

    private ServerMock server;
    private WorldMock world;
    private PlayerMock admin;
    private FakeApi api;
    private FakeSelections selections;
    private WeaponSpotTask task;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new WorldMock(Material.AIR, 0);
        world.setName("siege");
        server.addWorld(world);
        admin = server.addPlayer();
        admin.teleport(new Location(world, 3.5, 64, -3.5, LOOK_NORTH, 0f));
        api = new FakeApi();
        selections = new FakeSelections();
        task = new WeaponSpotTask(api, selections, BukkitBlockProbe::new, Runnable::run);
        fill(SEVEN_BY_NINE.minX(), 63, SEVEN_BY_NINE.minZ(), SEVEN_BY_NINE.maxX(), 63, SEVEN_BY_NINE.maxZ(), Material.STONE);
        fill(SEVEN_BY_NINE.minX(), 64, SEVEN_BY_NINE.minZ(), SEVEN_BY_NINE.maxX(), 68, SEVEN_BY_NINE.maxZ(), Material.AIR);
        task.start(admin, 3, ATTACKERS, "North wall");
        messages();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void save_StoresBoundsTeamAndTheLookDirection_SizeClassSmall_AndConfirmCreatesTheSpot() {
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        assertTrue(task.onPlayerChat(admin, "save"));
        List<String> shown = messages();
        assertTrue(shown.contains("Facing: North (your look direction)"), shown.toString());
        assertTrue(shown.contains("Size class: Small (needs 7 wide × 9 long × 5 high)"), shown.toString());
        assertTrue(shown.stream().noneMatch(line -> line.contains("Warning")), shown.toString());

        assertTrue(task.onPlayerChat(admin, "confirm"));
        SiegeWeaponSpotsApi.NewSpot sent = api.created.getFirst();
        assertEquals(3, api.scenarioIds.getFirst());
        assertEquals(11, sent.teamId());
        assertEquals("North wall", sent.name());
        assertEquals(SEVEN_BY_NINE, sent.bounds());
        assertNull(sent.wgRegionId());
        assertEquals(SpotFacing.NORTH, sent.facing());
        assertEquals(SpotSizeClass.SMALL, sent.sizeClass());
        assertTrue(messages().getLast().contains("Created spot 41 'North wall' for Red Banners: facing North, size class Small."));
        assertFalse(task.isHandling(admin));
    }

    @Test
    void overridingTheFacing_WorksTheClassOutAgain_EastIsRefused_SouthFitsAgain() {
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        task.onPlayerChat(admin, "save");
        messages();

        assertTrue(task.onPlayerChat(admin, "facing east"));
        List<String> east = messages();
        assertTrue(east.contains("Facing: East (set by you)"), east.toString());
        assertTrue(east.stream().anyMatch(line -> line.contains("Too small for a weapon facing east")
                && line.contains("It fits facing north or south")), east.toString());
        task.onPlayerChat(admin, "confirm");
        assertTrue(api.created.isEmpty(), "a refused facing must not be saved");

        task.onPlayerChat(admin, "facing s");
        assertTrue(messages().contains("Size class: Small (needs 7 wide × 9 long × 5 high)"));
        task.onPlayerChat(admin, "confirm");
        assertEquals(SpotFacing.SOUTH, api.created.getFirst().facing());
    }

    @Test
    void aFacingSetBeforeSave_IsUsedInsteadOfTheLookDirection() {
        task.onPlayerChat(admin, "facing west");
        assertTrue(messages().getLast().contains("Facing West is used when you type 'save'"));
        selections.selection = SpotSelections.Read.of(new SpotBounds(0, 64, 0, 8, 68, 6), false);
        task.onPlayerChat(admin, "save");
        assertTrue(messages().contains("Facing: West (set by you)"));
        task.onPlayerChat(admin, "confirm");
        assertEquals(SpotFacing.WEST, api.created.getFirst().facing());
        assertEquals(SpotSizeClass.SMALL, api.created.getFirst().sizeClass());
    }

    @Test
    void aSelectionSmallerThanTheSmallClass_IsRefused_AndNothingIsSaved() {
        selections.selection = SpotSelections.Read.of(new SpotBounds(0, 64, 0, 4, 68, 4), false);
        task.onPlayerChat(admin, "save");
        List<String> shown = messages();
        assertTrue(shown.stream().anyMatch(line -> line.contains("Too small for a weapon facing north")
                && line.contains("the small class needs at least 7 wide × 9 long × 5 high")), shown.toString());
        assertTrue(shown.stream().noneMatch(line -> line.contains("Type 'confirm'")), shown.toString());

        task.onPlayerChat(admin, "confirm");
        assertTrue(api.created.isEmpty());
        assertTrue(messages().getLast().contains("Too small"));
        assertTrue(task.isHandling(admin));
    }

    @Test
    void aFloorPartlyAirAndABlockInside_Warn_ButTheAdminCanStillConfirm() {
        fill(0, 63, 0, 0, 63, 8, Material.AIR);
        world.getBlockAt(3, 65, 4).setType(Material.OAK_PLANKS);
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        task.onPlayerChat(admin, "save");
        List<String> shown = messages();
        assertTrue(shown.stream().anyMatch(line -> line.contains("Warning: The floor isn't solid: 9 of 63")), shown.toString());
        assertTrue(shown.stream().anyMatch(line -> line.contains("Warning: The volume isn't clear: 1 blocks")), shown.toString());
        assertTrue(shown.getLast().startsWith("Type 'confirm' to create the spot anyway"), shown.toString());

        task.onPlayerChat(admin, "confirm");
        assertEquals(1, api.created.size());
    }

    @Test
    void regionId_UsesTheWorldGuardRegionsBounds() {
        selections.region = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        task.onPlayerChat(admin, "region siegespot_north");
        assertEquals("siegespot_north", selections.lastRegionId);
        assertTrue(messages().getFirst().endsWith("(region siegespot_north)"));
        task.onPlayerChat(admin, "confirm");
        assertEquals("siegespot_north", api.created.getFirst().wgRegionId());
    }

    @Test
    void noSelection_SaysHow_AndOtherChatIsNotTaken() {
        selections.selection = SpotSelections.Read.failed("You have no complete WorldEdit selection in this world.");
        task.onPlayerChat(admin, "save");
        assertTrue(messages().getLast().contains("no complete WorldEdit selection"));
        assertFalse(task.onPlayerChat(admin, "hello everyone"));
        task.onPlayerChat(admin, "confirm");
        assertTrue(messages().getLast().contains("Nothing selected yet"));
    }

    @Test
    void anApiRefusal_IsShown_AndTheTaskStaysOpen() {
        api.failure = new ApiException("http://api/SiegeScenarios/3/weapon-spots", 400, "Request failed",
                "\"siegeTeamId must be a team of this scenario.\"");
        selections.selection = SpotSelections.Read.of(SEVEN_BY_NINE, false);
        task.onPlayerChat(admin, "save");
        task.onPlayerChat(admin, "confirm");
        assertTrue(messages().getLast().contains("The API refused the spot: siegeTeamId must be a team of this scenario."));
        assertTrue(task.isHandling(admin));
    }

    @Test
    void cancel_EndsTheTask_WithoutSaving() {
        assertTrue(task.onPlayerChat(admin, "cancel"));
        assertFalse(task.isHandling(admin));
        assertTrue(api.created.isEmpty());
        assertFalse(task.onPlayerChat(admin, "save"));
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

    private static final class FakeApi implements SiegeWeaponSpotsApi {
        final List<NewSpot> created = new ArrayList<>();
        final List<Integer> scenarioIds = new ArrayList<>();
        RuntimeException failure;

        @Override
        public CompletableFuture<List<Team>> listTeams(int scenarioId) {
            return CompletableFuture.completedFuture(List.of(ATTACKERS));
        }

        @Override
        public CompletableFuture<CreatedSpot> createSpot(int scenarioId, NewSpot spot) {
            if (failure != null) return CompletableFuture.failedFuture(failure);
            created.add(spot);
            scenarioIds.add(scenarioId);
            return CompletableFuture.completedFuture(new CreatedSpot(41, spot.name(), spot.facing().apiName(),
                    spot.sizeClass().apiName()));
        }
    }
}
