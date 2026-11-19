package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege weapons phase 2 (#783): {@code /knk weaponspot} resolves the scenario team and starts the spot task. */
class WeaponSpotCommandTest {

    private static final List<Team> TEAMS = List.of(new Team(11, "Red Banners", null, "Attacker"),
            new Team(12, null, "Iron Wardens", "Defender"));

    private ServerMock server;
    private PlayerMock admin;
    private List<Team> teams = TEAMS;
    private Optional<String> busy = Optional.empty();
    private WeaponSpotTask task;
    private WeaponSpotCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        admin = server.addPlayer();
        SiegeWeaponSpotsApi api = new SiegeWeaponSpotsApi() {
            @Override
            public CompletableFuture<List<Team>> listTeams(int scenarioId) {
                return CompletableFuture.completedFuture(scenarioId == 3 ? teams : null);
            }

            @Override
            public CompletableFuture<CreatedSpot> createSpot(int scenarioId, NewSpot spot) {
                return CompletableFuture.failedFuture(new IllegalStateException("not in this test"));
            }
        };
        SpotSelections none = new SpotSelections() {
            @Override
            public Read worldEditSelection(Player player) {
                return Read.failed("none");
            }

            @Override
            public Read worldGuardRegion(World world, String regionId) {
                return Read.failed("none");
            }
        };
        task = new WeaponSpotTask(api, none, BukkitBlockProbe::new, Runnable::run);
        command = new WeaponSpotCommand(api, task, player -> busy, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void startsTheTask_ForTheTeamNamedByRole() {
        command.execute(admin, new String[]{"3", "defenders", "Gate", "tower"});
        assertTrue(task.isHandling(admin));
        assertTrue(messages().getFirst().contains("Weapon spot 'Gate tower' for Iron Wardens in scenario 3."));
    }

    @Test
    void anUnknownTeam_ListsTheScenariosTeams() {
        command.execute(admin, new String[]{"3", "green", "Gate"});
        assertFalse(task.isHandling(admin));
        String reply = messages().getLast();
        assertTrue(reply.contains("No team green in this scenario. Teams: 11 Red Banners (attacker), 12 Iron Wardens (defender)."), reply);
    }

    @Test
    void anUnknownScenario_OrBadArguments_AreRefused() {
        command.execute(admin, new String[]{"9", "attackers", "Gate"});
        assertTrue(messages().getLast().contains("No siege scenario 9."));
        command.execute(admin, new String[]{"x", "attackers", "Gate"});
        assertTrue(messages().getLast().startsWith("Usage: /knk weaponspot <scenario id>"));
        command.execute(admin, new String[]{"3", "attackers"});
        assertTrue(messages().getLast().startsWith("Usage:"));
        assertFalse(task.isHandling(admin));
    }

    @Test
    void anOpenWorldTask_OrSpotTask_BlocksASecondOne() {
        busy = Optional.of("Finish task 7 first.");
        command.execute(admin, new String[]{"3", "attackers", "Gate"});
        assertTrue(messages().getLast().contains("Finish task 7 first."));
        assertFalse(task.isHandling(admin));

        busy = Optional.empty();
        command.execute(admin, new String[]{"3", "attackers", "Gate"});
        messages();
        command.execute(admin, new String[]{"3", "11", "Second"});
        assertTrue(messages().getLast().contains("You already have a weapon spot task open"));
    }

    @Test
    void theConsole_CantSelectASpot() {
        var console = server.getConsoleSender();
        command.execute(console, new String[]{"3", "attackers", "Gate"});
        assertTrue(console.nextMessage().contains("Only a player in game"));
    }

    @Test
    void metadata_IsGatedOnTheSiegeManageNode_AndCompletesRoles() {
        assertEquals("weaponspot", WeaponSpotCommand.metadata().name());
        assertEquals("knk.siege.admin.manage", WeaponSpotCommand.metadata().permission());
        assertEquals(List.of("attackers"), command.complete(admin, new String[]{"3", "att"}));
    }

    private List<String> messages() {
        List<String> out = new ArrayList<>();
        Component message;
        while ((message = admin.nextComponentMessage()) != null) {
            out.add(PlainTextComponentSerializer.plainText().serialize(message).replaceAll("§.", ""));
        }
        return out;
    }
}
