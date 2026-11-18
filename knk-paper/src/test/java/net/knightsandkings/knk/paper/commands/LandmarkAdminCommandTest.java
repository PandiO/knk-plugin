package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.ports.api.LandmarksApi;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * {@code /knk landmark} (off-road phase 5.1, PandiO/knk-workspace#708, A12, in game): create at the player's position
 * with an extent, list, show, move the same Location, link to a domain by name or id, unlink, delete; names with spaces
 * and {@code #id}; the API's refusal shown; nothing done with {@code navigation.landmarks} off. The node
 * ({@value LandmarkAdminCommand#PERMISSION}) is the {@code /knk} metadata's, checked by the registry
 * ({@code CommandRegistryPermissionsTest}).
 */
class LandmarkAdminCommandTest {

    private ServerMock server;
    private World world;
    private PlayerMock staff;
    private final FakeApi api = new FakeApi();
    private final AtomicInteger refreshes = new AtomicInteger();
    private NavigationModel model = new NavigationModel(Instant.now(),
        List.of(new DomainPlace(9, "Keep", "Structure", null, null, "domain_9", "world", null, true),
            new DomainPlace(10, "Market", "District", null, null, "domain_10", "world", null, true),
            new DomainPlace(11, "Market", "Structure", null, null, "domain_11", "world", null, true)),
        List.of(), null, List.of());
    private LandmarkAdminCommand command;

    /** An in-memory api/landmarks. */
    static final class FakeApi implements LandmarksApi {
        final List<KnkLandmark> rows = new ArrayList<>();
        final List<LandmarkWrite> writes = new ArrayList<>();
        final List<Integer> deleted = new ArrayList<>();
        ApiException refuse;
        int nextId = 41;

        @Override
        public CompletableFuture<List<KnkLandmark>> list(String world) {
            return CompletableFuture.completedFuture(rows.stream()
                .filter(l -> world == null || world.equalsIgnoreCase(l.world())).toList());
        }

        @Override
        public CompletableFuture<Optional<KnkLandmark>> get(int id) {
            return CompletableFuture.completedFuture(rows.stream().filter(l -> l.id() == id).findFirst());
        }

        @Override
        public CompletableFuture<KnkLandmark> create(LandmarkWrite w) {
            writes.add(w);
            if (refuse != null) {
                return CompletableFuture.failedFuture(new CompletionException(refuse));
            }
            KnkLandmark saved = new KnkLandmark(nextId++, w.name(), 300, w.pin().world(), w.pin().x(), w.pin().y(), w.pin().z(),
                0f, 0f, w.domainId(), null, null, w.extent(), w.regionId(), w.radius(), 1.0, true);
            rows.add(saved);
            return CompletableFuture.completedFuture(saved);
        }

        @Override
        public CompletableFuture<KnkLandmark> update(int id, LandmarkWrite w) {
            writes.add(w);
            KnkLandmark old = rows.stream().filter(l -> l.id() == id).findFirst().orElseThrow();
            KnkLandmark saved = new KnkLandmark(id, w.name(), old.locationId(), old.world(),
                w.pin() == null ? old.x() : w.pin().x(), old.y(), old.z(), 0f, 0f, w.domainId(), null, null,
                old.extent(), old.regionId(), old.radius(), 1.0, true);
            rows.set(rows.indexOf(old), saved);
            return CompletableFuture.completedFuture(saved);
        }

        @Override
        public CompletableFuture<Void> delete(int id) {
            deleted.add(id);
            rows.removeIf(l -> l.id() == id);
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        server.addSimpleWorld("world_nether");
        staff = server.addPlayer();
        staff.teleport(new Location(world, 100.5, 72, -40.5));
        command = new LandmarkAdminCommand(true, api, () -> model, refreshes::incrementAndGet, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line) {
        command.execute(staff, line.split(" "));
    }

    private List<String> messages() {
        List<String> out = new ArrayList<>();
        net.kyori.adventure.text.Component message;
        while ((message = staff.nextComponentMessage()) != null) {
            out.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return out;
    }

    private KnkLandmark landmark(int id, String name, String world, Integer domainId) {
        return new KnkLandmark(id, name, 30 + id, world, 1, 64, 1, 0f, 0f, domainId, domainId == null ? null : "Keep",
            domainId == null ? null : "Structure", Landmark.Extent.NONE, null, null, 1.0, true);
    }

    @Test
    void createTakesTheNameTheExtentAndThePlayersPosition() {
        run("create Throne room region keep_throne");

        LandmarkWrite w = api.writes.get(0);
        assertEquals("Throne room", w.name());
        assertEquals(Landmark.Extent.REGION, w.extent());
        assertEquals("keep_throne", w.regionId());
        assertEquals("world", w.pin().world());
        assertEquals(100.5, w.pin().x());
        assertNull(w.pin().locationId(), "a new Location");
        assertEquals(1, refreshes.get(), "the model is refreshed");
        assertTrue(messages().get(0).contains("Created landmark Throne room (#41) region keep_throne"));
    }

    @Test
    void createWithARadius_andABadRadiusIsRefusedBeforeTheApi() {
        run("create Old well radius 5");
        assertEquals(5.0, api.writes.get(0).radius());
        messages();

        run("create Old well radius 600");
        assertEquals(1, api.writes.size());
        assertTrue(messages().get(0).contains("at most 512"));
    }

    @Test
    void theApisRefusalIsShown() {
        api.refuse = new ApiException("http://api/landmarks", 400, "Request failed",
            "\"A landmark named \\\"Hall\\\" already exists in world world.\"");
        run("create Hall");
        assertTrue(messages().get(0).startsWith("A landmark named"), "the API's reason");
        assertEquals(0, refreshes.get());
    }

    @Test
    void listShowsThePlayersWorld() {
        api.rows.add(landmark(1, "Throne room", "world", 9));
        api.rows.add(landmark(2, "Portal", "world_nether", null));
        run("list");
        List<String> lines = messages();
        assertEquals("Landmarks in world (1):", lines.get(0));
        assertTrue(lines.get(1).contains("#1 Throne room point only, in Keep"));
    }

    @Test
    void showFindsANameWithSpacesOrAnId() {
        api.rows.add(landmark(1, "Throne room", "world", 9));
        run("show throne ROOM");
        assertTrue(messages().get(0).contains("Landmark #1: Throne room"));
        run("show #1");
        assertTrue(messages().get(0).contains("Throne room"));
        run("show Nowhere");
        assertTrue(messages().get(0).startsWith("No landmark Nowhere in world"));
    }

    @Test
    void moveMovesTheSameLocationAndKeepsTheDomain() {
        api.rows.add(landmark(1, "Throne room", "world", 9));
        run("move Throne room");
        LandmarkWrite w = api.writes.get(0);
        assertEquals(31, w.pin().locationId(), "the landmark's own Location");
        assertEquals(100.5, w.pin().x());
        assertEquals(9, w.domainId(), "kept: the API takes domainId as sent");
        assertNull(w.extent(), "kept");
    }

    @Test
    void linkTakesADomainByNameOrId_andAnAmbiguousNameAsksForTheId() {
        api.rows.add(landmark(1, "Throne room", "world", null));
        run("link Throne room Keep");
        assertEquals(9, api.writes.get(0).domainId());
        assertTrue(messages().get(0).contains("Linked Throne room to Keep (Structure)"));

        run("link Throne room Market");
        assertEquals(1, api.writes.size());
        assertTrue(messages().get(0).contains("District #10, Structure #11"));

        run("link #1 #11");
        assertEquals(11, api.writes.get(1).domainId());
    }

    @Test
    void unlinkSendsNoDomain_deleteDeletes() {
        api.rows.add(landmark(1, "Throne room", "world", 9));
        run("unlink Throne room");
        assertNull(api.writes.get(0).domainId());
        run("delete Throne room");
        assertEquals(List.of(1), api.deleted);
        assertTrue(messages().get(1).contains("Deleted landmark Throne room (#1)"));
    }

    @Test
    void withTheFlagOffNothingHappens() {
        LandmarkAdminCommand off = new LandmarkAdminCommand(false, api, () -> model, refreshes::incrementAndGet, Runnable::run);
        off.execute(staff, new String[] {"create", "Hall"});
        assertTrue(api.writes.isEmpty());
        assertEquals(LandmarkAdminCommand.OFF, messages().get(0));
        assertEquals(List.of(), off.complete(staff, new String[] {""}));
    }

    @Test
    void theConsoleCantCreateButCanList() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        command.execute(console, new String[] {"create", "Hall"});
        assertTrue(api.writes.isEmpty());
        String line = console.nextMessage();
        assertNotNull(line);
        assertTrue(line.contains("Only a player"));
    }

    @Test
    void completesActionsLandmarksAndDomains() {
        model = new NavigationModel(Instant.now(), model.domains(), List.of(), null,
            List.of(new Landmark(1, "Throne room", "world", 0, 0, 0, Landmark.Extent.NONE, null, null, null, null, true)));
        assertEquals(List.of("create", "list", "show", "move", "delete", "link", "unlink"),
            command.complete(staff, new String[] {""}));
        assertEquals(List.of("Throne"), command.complete(staff, new String[] {"show", "t"}));
        assertEquals(List.of("Keep"), command.complete(staff, new String[] {"link", "Throne", "room", "K"}));
        assertEquals(List.of("radius", "region"), command.complete(staff, new String[] {"create", "Hall", "r"}).stream()
            .sorted().toList());
    }

    @Test
    void theMetadataGatesOnTheLandmarkNode() {
        assertEquals("knk.admin.world.landmark", LandmarkAdminCommand.metadata().permission());
        assertEquals("landmark", LandmarkAdminCommand.metadata().name());
    }
}
