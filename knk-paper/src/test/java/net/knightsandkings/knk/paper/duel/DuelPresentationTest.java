package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaSpectatorArea;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.duel.view.DuelAnnouncements;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels P6 (DESIGN §9.1, §9.3, KNG-220) around the real P4 runtime: the announcer's warmup broadcast (buttons,
 * spam cooldown, arena toggles), the result after the API's answer ("pending review" when held), draws and cancels,
 * and the duel sidebar/boss bar for duelists and for a player in the spectator area - whose board the minigame
 * registry then keeps (the scoreboard predicate).
 */
@org.junit.jupiter.api.Timeout(30)
class DuelPresentationTest {

    @TempDir
    Path dataFolder;

    private DuelHarness h;
    private final List<Component> broadcasts = new ArrayList<>();
    private final List<Component> toSpectator = new ArrayList<>();
    private Player spectator;
    private final AtomicReference<Scoreboard> spectatorBoard = new AtomicReference<>();
    private Location spectatorAt;
    private Location stands;
    private DuelHud hud;

    private DuelHarness harness(boolean announceMatches, boolean announceFriendly, boolean warp) {
        h = new DuelHarness(dataFolder);
        h.arena = withArenaSettings(h.arena, announceMatches, announceFriendly,
            List.of(new KnkArenaSpectatorArea(40, DuelHarness.ARENA_ID, "Stands", "arena_2_stands", "arena", warp, null)));
        stands = new Location(h.world, 5, 70, 5);
        spectatorAt = stands;
        spectator = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes("Steve".getBytes());
        when(spectator.getUniqueId()).thenReturn(id);
        when(spectator.getName()).thenReturn("Steve");
        when(spectator.isOnline()).thenReturn(true);
        when(spectator.getLocation()).thenAnswer(inv -> spectatorAt);
        when(spectator.getScoreboard()).thenAnswer(inv -> spectatorBoard.get());
        doAnswer(inv -> {
            spectatorBoard.set(inv.getArgument(0));
            return null;
        }).when(spectator).setScoreboard(any());
        doAnswer(inv -> toSpectator.add(inv.getArgument(0))).when(spectator).sendMessage(any(Component.class));

        DuelSpectators spectators = new DuelSpectators(() -> List.of(h.alice.player(), h.bob.player(), spectator),
            location -> location == stands ? Set.of("ARENA_2_STANDS") : Set.of(), () -> List.of(h.arena));
        h.service.addObserver(new DuelAnnouncer(h.service, spectators, broadcasts::add, this::player,
            DuelAnnouncements.Templates::defaults));
        hud = new DuelHud(h.service, spectators, this::player, title -> mock(Scoreboard.class, RETURNS_DEEP_STUBS), null);
        h.service.setDuelBoardViewers(hud::isViewing);
        h.service.addObserver(hud);
        return h;
    }

    private Player player(UUID id) {
        if (id.equals(spectator.getUniqueId())) return spectator;
        DuelTestItems.TestPlayer p = h.players.get(id);
        return p == null ? null : p.player();
    }

    private StakeContract staked() {
        ItemStack sword = h.items.item("sword");
        h.alice.give(sword);
        return h.contract(GearMode.OWN_GEAR_KEEP, null, new Stake(100, List.of(h.stake(h.alice, sword))), Stake.coins(100));
    }

    /**
     * Alice invites Bob, Bob accepts. A friendly lock-in finishes on a later tick, so the queued ticks run before
     * the outcome is read (the harness's own helper reads it first).
     */
    private DuelService.LockOutcome duel(StakeContract contract) {
        var offer = h.service.offers().invite(h.alice.id(), h.bob.id(), h.settings(), contract, h.tick);
        assertTrue(offer.ok(), "invite refused: " + offer.status());
        var outcome = h.service.accept(h.bob.player(), offer.offer().offerId());
        h.runNextTicks();
        assertTrue(outcome.isDone(), "the lock-in finished");
        return outcome.join();
    }

    private StakeContract friendly() {
        return h.contract(GearMode.OWN_GEAR_KEEP, null, Stake.NONE, Stake.NONE);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(DuelPresentationTest::plain).toList();
    }

    // ===== announcements =====

    @Test
    void theWarmupStartIsBroadcastWithSpectateAndDetailsButtons() {
        harness(true, true, true);
        DuelMatch match = h.duel(staked()).match();

        assertEquals(1, broadcasts.size());
        String line = plain(broadcasts.getFirst());
        assertTrue(line.startsWith("⚔ Duel at Arena: Alice vs Bob - own gear - stakes: 200 coins + 1 item"), line);
        assertTrue(line.endsWith("[Spectate] [Details]"), line);
        List<String> clicks = clicks(broadcasts.getFirst());
        assertEquals(List.of("/duels spectate " + DuelHarness.ARENA_ID, "/duels view " + match.token()), clicks);
    }

    @Test
    void withoutASpectatorWarpThereIsNoSpectateButton() {
        harness(true, true, false);
        h.duel(staked());
        assertEquals(List.of(DuelAnnouncer.DETAILS_COMMAND.trim() + " " + h.service.matches().getFirst().token()),
            clicks(broadcasts.getFirst()));
    }

    @Test
    void theSpamCooldownAllowsOneWarmupBroadcastPerPlayer() {
        harness(true, true, true);
        duel(friendly());
        assertEquals(1, broadcasts.size());
        h.service.cancel(h.service.matches().getFirst().token());
        h.runNextTicks();
        h.seconds(11);
        broadcasts.clear();

        duel(friendly());
        assertTrue(broadcasts.isEmpty(), "the same players again within duels.announce.cooldown-seconds: " + plain(broadcasts));

        h.service.cancel(h.service.matches().getFirst().token());
        h.runNextTicks();
        h.seconds(11);
        DuelConfig d = DuelConfig.defaults();
        h.service.updateConfig(new DuelConfig(d.enabled(), d.maxIncoming(), 0, d.agreedDrawWindowSeconds(),
            d.commandAllowList(), d.itemBlocklist(), d.refuseFilledContainers(), 0, d.apiTimeoutSeconds(),
            d.resultRetrySeconds(), d.heldRefreshSeconds()));
        duel(friendly());
        assertEquals(1, broadcasts.size(), "a cooldown of 0 never limits");
    }

    @Test
    void friendlyDuelsNeedAnnounceFriendly_StakedOnesOnlyAnnounceMatches() {
        harness(true, false, true);
        duel(friendly());
        assertTrue(broadcasts.isEmpty(), "AnnounceFriendly off");
        h.service.cancel(h.service.matches().getFirst().token());
        h.runNextTicks();
        h.seconds(11);

        h.duel(staked());
        assertEquals(1, broadcasts.size(), "a staked duel only needs AnnounceMatches");
    }

    @Test
    void announceMatchesOffSilencesTheArena() {
        harness(false, true, true);
        h.duel(staked());
        assertTrue(broadcasts.isEmpty(), "AnnounceMatches off");
        assertTrue(toSpectator.isEmpty(), "no warmup line at all");
    }

    @Test
    void aPaidResultIsAnnouncedAfterTheApiAnswered_AndTheDeathLineStaysInTheArena() {
        harness(true, true, true);
        h.duel(staked());
        h.toFight();
        broadcasts.clear();

        h.kill(h.bob);

        assertEquals(1, broadcasts.size(), plain(broadcasts).toString());
        String result = plain(broadcasts.getFirst());
        assertTrue(result.startsWith("⚔ Alice defeated Bob in 0:"), result);
        assertTrue(result.endsWith(" and won 190 coins + 1 item."), "the pot less the 5% fee: " + result);
        assertTrue(plain(toSpectator).contains("Bob was defeated by Alice."), "the spectator gets the death line");
        assertFalse(plain(broadcasts).contains("Bob was defeated by Alice."), "the death line isn't broadcast");
    }

    @Test
    void aHeldResultSaysPendingReview() {
        harness(true, true, true);
        h.completeSettlement = Settlement.HELD;
        h.duel(staked());
        h.toFight();
        broadcasts.clear();

        h.kill(h.alice);

        assertEquals(1, broadcasts.size());
        assertTrue(plain(broadcasts.getFirst()).contains("Bob defeated Alice"), plain(broadcasts.getFirst()));
        assertTrue(plain(broadcasts.getFirst()).endsWith("(pending review)."), plain(broadcasts.getFirst()));
    }

    @Test
    void drawsAreAnnounced() {
        harness(true, true, true);
        duel(friendly());
        h.toFight();
        broadcasts.clear();
        h.service.offerDraw(h.alice.player());
        h.service.offerDraw(h.bob.player());
        assertEquals(List.of("⚔ Alice and Bob drew at Arena (agreed draw)."), plain(broadcasts));
    }

    @Test
    void cancelsReachOnlyTheDuelistsAndSpectators() {
        harness(true, true, true);
        DuelMatch match = h.duel(staked()).match();
        broadcasts.clear();
        toSpectator.clear();
        h.service.cancel(match.token());
        h.runNextTicks();
        assertTrue(broadcasts.isEmpty());
        assertEquals(List.of("The duel Alice vs Bob was cancelled: staff cancelled it"), plain(toSpectator));
        verify(h.alice.player(), atLeastOnce()).sendMessage(any(Component.class));
    }

    // ===== sidebar and boss bar =====

    @Test
    void duelistsAndSpectatorsSeeTheDuelBoard_AndTheRegistryKeepsIt() {
        harness(true, true, true);
        Scoreboard before = mock(Scoreboard.class);
        spectatorBoard.set(before);
        DuelMatch match = h.duel(staked()).match();
        assertEquals(DuelPhase.WARMUP, match.phase());

        hud.tick();

        assertTrue(hud.isViewing(h.alice.id()) && hud.isViewing(h.bob.id()), "both duelists");
        assertTrue(hud.isViewing(spectator.getUniqueId()), "the player standing in the stands");
        assertTrue(spectatorBoard.get() != before, "the spectator was given the duel board");
        verify(spectator, atLeastOnce()).showBossBar(any(BossBar.class));
        assertTrue(h.registry.keepsOwnScoreboard(spectator.getUniqueId()),
            "ScoreboardUtil's predicate is the registry: the spectator's board stays");
        assertTrue(h.registry.keepsOwnScoreboard(h.alice.id()));

        spectatorAt = new Location(h.world, 500, 64, 500);
        hud.tick();
        assertFalse(hud.isViewing(spectator.getUniqueId()), "left the stands");
        assertEquals(before, spectatorBoard.get(), "their own board is back");
        verify(spectator, atLeastOnce()).hideBossBar(any(BossBar.class));
        assertFalse(h.registry.keepsOwnScoreboard(spectator.getUniqueId()));
    }

    @Test
    void spectatorsSeeNothingWhileTheArenaHasNoLiveDuel() {
        harness(true, true, true);
        hud.tick();
        assertFalse(hud.isViewing(spectator.getUniqueId()));
        verify(spectator, never()).setScoreboard(any());
    }

    @Test
    void theBoardEndsWithTheMatch() {
        harness(true, true, true);
        DuelMatch match = h.duel(staked()).match();
        hud.tick();
        h.service.cancel(match.token());
        h.runNextTicks();
        h.seconds(3);
        hud.tick();
        assertFalse(hud.isViewing(h.alice.id()));
        assertFalse(hud.isViewing(spectator.getUniqueId()));
        assertFalse(h.registry.keepsOwnScoreboard(h.alice.id()));
    }

    // ===== helpers =====

    private static List<String> clicks(Component line) {
        List<String> out = new ArrayList<>();
        collect(line, out);
        return out;
    }

    private static void collect(Component c, List<String> out) {
        ClickEvent click = c.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
            out.add(((ClickEvent.Payload.Text) click.payload()).value());
        }
        c.children().forEach(child -> collect(child, out));
    }

    static KnkArena withArenaSettings(KnkArena a, boolean announceMatches, boolean announceFriendly,
                                      List<KnkArenaSpectatorArea> areas) {
        return new KnkArena(a.id(), a.key(), a.name(), a.wgRegionId(), a.worldName(), a.districtId(), a.location(),
            a.lobbyLocation(), a.exitLocation(), a.duelEnabled(), a.maxQueuedMatches(), a.maxOffers(), a.offerSeconds(),
            a.allowCoinStakes(), a.allowItemStakes(), a.maxCoinStake(), a.maxItemStacks(), a.minStakeRatioPercent(),
            a.feePercent(), a.stakeMinAccountAgeHours(), a.stakeMinTitleBracketId(), a.maxStakedDuelsPerPairPerDay(),
            a.pairCooldownMinutes(), a.dailyCoinWinCap(), a.allowOwnGearKeep(), a.allowOwnGearDrop(), a.allowFixedKit(),
            a.allowKitChoice(), a.defaultGearMode(), a.warmupSeconds(), a.countdownSeconds(), a.fightTimeLimitSeconds(),
            a.maxWaitSeconds(), a.winnerLingerSeconds(), announceMatches, announceFriendly, a.eligibleKits(),
            a.battlegrounds(), areas);
    }
}
