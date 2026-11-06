package net.knightsandkings.knk.paper.visibility;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.modes.ModeServiceTestAccess;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41): the one visibility arbiter. Without ghosts every show/hide call it
 * makes is the one the pre-S3 {@code ModeService.applyVisibility} made (the oracle below is that method, verbatim in
 * effect); with a ghost, outsiders never see it - not through a join, a mode change or a permission refresh - and
 * teammates do.
 */
class PlayerVisibilityArbiterTest {

    private final Plugin plugin = mock(Plugin.class);
    private final Set<UUID> vanished = new HashSet<>();
    private final Map<UUID, CompletableFuture<Boolean>> staff = new HashMap<>();
    private final List<Player> online = new ArrayList<>();
    private final List<Runnable> mainThreadQueue = new ArrayList<>();
    /** viewer → the show/hide calls made on them, in order: "show:<target>" / "hide:<target>". */
    private final Map<UUID, List<String>> calls = new LinkedHashMap<>();
    private final Set<UUID> translucent = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();

    private final PlayerVisibilityArbiter arbiter = new PlayerVisibilityArbiter(plugin,
            p -> vanished.contains(p.getUniqueId()),
            p -> staff.getOrDefault(p.getUniqueId(), CompletableFuture.completedFuture(false)),
            () -> online, () -> true, mainThreadQueue::add,
            p -> translucent.add(p.getUniqueId()), p -> translucent.remove(p.getUniqueId()));

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        names.put(id, name);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        calls.put(id, new ArrayList<>());
        doAnswer(inv -> calls.get(id).add("hide:" + ((Player) inv.getArgument(1)).getName()))
                .when(player).hidePlayer(any(Plugin.class), any(Player.class));
        doAnswer(inv -> calls.get(id).add("show:" + ((Player) inv.getArgument(1)).getName()))
                .when(player).showPlayer(any(Plugin.class), any(Player.class));
        online.add(player);
        return player;
    }

    private List<String> callsOf(Player viewer) {
        return calls.get(viewer.getUniqueId());
    }

    private void runMainThreadQueue() {
        List<Runnable> queued = new ArrayList<>(mainThreadQueue);
        mainThreadQueue.clear();
        queued.forEach(Runnable::run);
    }

    // ===== the oracle: ModeService.applyVisibility before link S3 =====

    /** What the pre-S3 code did for one pair, with the viewer's check already resolved. */
    private List<String> legacyApply(Player viewer, Player target, boolean targetVanished, boolean viewerAllowed) {
        if (!targetVanished) return List.of("show:" + target.getName());
        List<String> expected = new ArrayList<>(List.of("hide:" + target.getName()));
        if (viewerAllowed) expected.add("show:" + target.getName());
        return expected;
    }

    @Test
    void withoutGhostsEveryPairIsWhatModeServiceDidBefore() {
        for (boolean targetVanished : new boolean[] {false, true}) {
            for (boolean viewerAllowed : new boolean[] {false, true}) {
                Player viewer = player("viewer-" + targetVanished + viewerAllowed);
                Player target = player("target-" + targetVanished + viewerAllowed);
                if (targetVanished) vanished.add(target.getUniqueId());
                staff.put(viewer.getUniqueId(), CompletableFuture.completedFuture(viewerAllowed));

                arbiter.apply(viewer, target);

                assertEquals(legacyApply(viewer, target, targetVanished, viewerAllowed), callsOf(viewer),
                        "vanished=" + targetVanished + " allowed=" + viewerAllowed);
                assertTrue(callsOf(target).isEmpty(), "the target's own view is untouched");
                assertTrue(mainThreadQueue.isEmpty(), "a resolved check applies inline, no flicker");
            }
        }
    }

    @Test
    void aPendingPermissionCheckHidesFirstAndRevealsOnTheMainThreadLater_asBefore() {
        Player viewer = player("viewer");
        Player target = player("target");
        vanished.add(target.getUniqueId());
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        staff.put(viewer.getUniqueId(), pending);

        arbiter.apply(viewer, target);
        assertEquals(List.of("hide:target"), callsOf(viewer));

        pending.complete(true);
        assertEquals(List.of("hide:target"), callsOf(viewer), "revealed on the main thread, not on the resolving thread");
        runMainThreadQueue();
        assertEquals(List.of("hide:target", "show:target"), callsOf(viewer));
    }

    @Test
    void aLateRevealIsDroppedWhenTheTargetLeftTheirModeMeanwhile_asBefore() {
        Player viewer = player("viewer");
        Player target = player("target");
        vanished.add(target.getUniqueId());
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        staff.put(viewer.getUniqueId(), pending);
        arbiter.apply(viewer, target);

        vanished.remove(target.getUniqueId());
        pending.complete(true);
        runMainThreadQueue();
        assertEquals(List.of("hide:target"), callsOf(viewer), "the re-check found no mode; the next refresh shows them");
    }

    @Test
    void refreshesSkipTheSelfPairAndCoverEveryoneElse_asBefore() {
        Player a = player("a");
        Player b = player("b");
        Player c = player("c");
        vanished.add(b.getUniqueId());
        staff.put(a.getUniqueId(), CompletableFuture.completedFuture(true));

        arbiter.refreshVisibilityOf(b);
        assertEquals(List.of("hide:b", "show:b"), callsOf(a));
        assertEquals(List.of("hide:b"), callsOf(c));
        assertTrue(callsOf(b).isEmpty());

        arbiter.refreshVisibilityFor(a);
        assertEquals(List.of("hide:b", "show:b", "hide:b", "show:b", "show:c"), callsOf(a));
    }

    // ===== ghosts =====

    private GhostTeam teamOf(Player... members) {
        Set<UUID> ids = new HashSet<>();
        for (Player m : members) ids.add(m.getUniqueId());
        return new GhostTeam("siege:1:2", ids::contains);
    }

    @Test
    void aGhostIsHiddenFromOutsidersAndTranslucentToTeammates() {
        Player ghost = player("ghost");
        Player teammate = player("teammate");
        Player ghostTeammate = player("ghost-teammate");
        Player outsider = player("outsider");
        Player staffOutsider = player("staff");
        staff.put(staffOutsider.getUniqueId(), CompletableFuture.completedFuture(true));
        GhostTeam team = teamOf(ghost, teammate, ghostTeammate);

        arbiter.markGhost(ghost, team);
        arbiter.markGhost(ghostTeammate, team);

        assertTrue(arbiter.isGhost(ghost.getUniqueId()));
        assertEquals(team, arbiter.ghostTeamOf(ghost.getUniqueId()).orElseThrow());
        assertEquals(Set.of(ghost.getUniqueId(), ghostTeammate.getUniqueId()), arbiter.ghosts());
        assertTrue(translucent.contains(ghost.getUniqueId()), "vanilla invisibility for the team's see-friendly-invisibles");
        assertEquals(List.of("show:ghost", "show:ghost-teammate"), callsOf(teammate));
        assertEquals(List.of("show:ghost"), callsOf(ghostTeammate), "a ghost sees the other ghosts of its team");
        assertEquals(List.of("hide:ghost", "hide:ghost-teammate"), callsOf(outsider));
        assertEquals(List.of("hide:ghost", "hide:ghost-teammate"), callsOf(staffOutsider), "staff see vanished players, not ghosts");
        assertTrue(mainThreadQueue.isEmpty());
    }

    @Test
    void nothingUnhidesAGhost_andAJoinerHidesExistingGhosts() {
        Player ghost = player("ghost");
        Player outsider = player("outsider");
        arbiter.markGhost(ghost, teamOf(ghost));
        callsOf(outsider).clear();

        // The refreshes ModeService runs on a join, a mode change and a permission change.
        arbiter.refreshVisibilityOf(ghost);
        arbiter.refreshVisibilityFor(outsider);
        arbiter.apply(outsider, ghost);
        assertEquals(List.of("hide:ghost", "hide:ghost", "hide:ghost"), callsOf(outsider));
        assertFalse(callsOf(outsider).contains("show:ghost"));

        Player joiner = player("joiner");
        arbiter.refreshVisibilityFor(joiner);
        assertEquals(List.of("hide:ghost", "show:outsider"), callsOf(joiner));
    }

    @Test
    void aVanishedGhostFollowsTheVanishRuleForTeammatesOnly() {
        Player ghost = player("ghost");
        Player teammate = player("teammate");
        Player staffTeammate = player("staff-teammate");
        Player staffOutsider = player("staff-outsider");
        staff.put(staffTeammate.getUniqueId(), CompletableFuture.completedFuture(true));
        staff.put(staffOutsider.getUniqueId(), CompletableFuture.completedFuture(true));
        vanished.add(ghost.getUniqueId());

        arbiter.markGhost(ghost, teamOf(ghost, teammate, staffTeammate));

        assertEquals(List.of("hide:ghost"), callsOf(teammate), "a teammate without the staff node doesn't see vanished staff");
        assertEquals(List.of("hide:ghost", "show:ghost"), callsOf(staffTeammate));
        assertEquals(List.of("hide:ghost"), callsOf(staffOutsider), "the ghost reason wins over the staff reveal");
    }

    @Test
    void aLateStaffRevealIsDroppedWhenTheTargetBecameAGhostMeanwhile() {
        Player viewer = player("viewer");
        Player target = player("target");
        vanished.add(target.getUniqueId());
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        staff.put(viewer.getUniqueId(), pending);
        arbiter.apply(viewer, target);

        arbiter.markGhost(target, teamOf(target));
        pending.complete(true);
        runMainThreadQueue();
        assertEquals(List.of("hide:target", "hide:target"), callsOf(viewer));
    }

    @Test
    void clearingTheGhostShowsThemAgainUnderTheRemainingRules_markingTwiceAppliesTranslucencyOnce() {
        Player ghost = player("ghost");
        Player outsider = player("outsider");
        GhostTeam team = teamOf(ghost);
        arbiter.markGhost(ghost, team);
        translucent.remove(ghost.getUniqueId()); // simulate the effect wearing off: a re-mark must not re-apply it
        arbiter.markGhost(ghost, new GhostTeam("siege:1:3", team.membership()));
        assertFalse(translucent.contains(ghost.getUniqueId()), "re-marking only updates the team");
        assertEquals("siege:1:3", arbiter.ghostTeamOf(ghost.getUniqueId()).orElseThrow().key());

        arbiter.clearGhost(ghost);
        assertFalse(arbiter.isGhost(ghost.getUniqueId()));
        assertEquals(List.of("hide:ghost", "hide:ghost", "show:ghost"), callsOf(outsider));

        arbiter.clearGhost(ghost); // idempotent
        assertEquals(List.of("hide:ghost", "hide:ghost", "show:ghost"), callsOf(outsider));

        arbiter.markGhost(ghost, team);
        arbiter.forget(ghost);
        assertFalse(arbiter.isGhost(ghost.getUniqueId()));
    }

    // ===== through ModeService: the un-hide the implementation checks found (report §2) =====

    @Test
    void modeServiceRoutesEveryRefreshThroughTheArbiter_soAJoinCannotUnhideAGhost() {
        Player ghost = player("ghost");
        Player outsider = player("outsider");
        ModeService modes = ModeServiceTestAccess.create(plugin, mock(KnkPermissible.class),
                new UserCache(Duration.ofMinutes(1)), null, service -> arbiter);
        assertEquals(arbiter, modes.visibility());

        modes.visibility().markGhost(ghost, teamOf(ghost));
        callsOf(outsider).clear();

        // ModeListener.onJoin for a player without a persisted mode: the pre-S3 code called showPlayer here.
        modes.applyMode(ghost, ActiveMode.NONE, false);
        modes.refreshVisibilityFor(outsider);
        // UserAdminService after a permission change.
        modes.refreshVisibilityFor(outsider);
        assertEquals(List.of("hide:ghost", "hide:ghost", "hide:ghost"), callsOf(outsider));

        // Entering and leaving staff mode as a ghost: still hidden from the outsider.
        modes.applyMode(ghost, ActiveMode.STAFF, false);
        modes.applyMode(ghost, ActiveMode.NONE, false);
        assertFalse(callsOf(outsider).contains("show:ghost"));

        modes.forget(ghost);
        assertFalse(arbiter.isGhost(ghost.getUniqueId()), "quit forgets the ghost");
    }

    @Test
    void modeServiceWithoutGhostsBehavesAsBefore() {
        Player vanishedStaff = player("vanished");
        Player plain = player("plain");
        Player staffViewer = player("staff-viewer");
        staff.put(staffViewer.getUniqueId(), CompletableFuture.completedFuture(true));
        ModeService modes = ModeServiceTestAccess.create(plugin, mock(KnkPermissible.class),
                new UserCache(Duration.ofMinutes(1)), null, service -> new PlayerVisibilityArbiter(plugin,
                        service::isVanished, p -> staff.getOrDefault(p.getUniqueId(), CompletableFuture.completedFuture(false)),
                        () -> online, () -> true, mainThreadQueue::add, p -> { }, p -> { }));

        modes.applyMode(vanishedStaff, ActiveMode.STAFF, false);
        assertTrue(modes.isVanished(vanishedStaff));
        assertEquals(List.of("hide:vanished"), callsOf(plain));
        assertEquals(List.of("hide:vanished", "show:vanished"), callsOf(staffViewer));

        modes.applyMode(vanishedStaff, ActiveMode.NONE, false);
        assertEquals(List.of("hide:vanished", "show:vanished"), callsOf(plain));
        assertEquals(List.of("hide:vanished", "show:vanished", "show:vanished"), callsOf(staffViewer));
    }
}
