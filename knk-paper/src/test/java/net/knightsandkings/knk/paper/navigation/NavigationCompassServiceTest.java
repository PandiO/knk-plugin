package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.SiegeLobbyMode;
import net.knightsandkings.knk.core.domain.siege.SiegeNavigationItemMode;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine;
import org.bukkit.inventory.ItemStack;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule.NameMatch;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.CaptureCalculator;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The navigation compass (KNG-234): which item it is, what a click does in a siege match, outside it, and refusals. */
class NavigationCompassServiceTest {

    private static final int DEF = 1;
    private static final int ATT = 2;

    private final NavigationService navigation = mock(NavigationService.class);
    private final NavigationDestinations destinations = mock(NavigationDestinations.class);
    private final SiegeService siege = mock(SiegeService.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(10_000);
    private CompassSettings settings = new CompassSettings(CompassItemRule.defaults(), true, false, List.of());
    private final FakeItems items = new FakeItems();
    private NavigationCompassService compass;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("siege_world");
        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        compass = new NavigationCompassService(() -> settings, () -> navigation, () -> destinations, () -> siege,
            (p, node) -> false, Runnable::run, clock::get, items);
    }

    private static KnkLocation at(int n) {
        return new KnkLocation(n, "L" + n, 10.0 * n, 64.0, 5.0, 0f, 0f, "siege_world");
    }

    private static KnkSiegeObjective objective(int id, String name, int holder) {
        return new KnkSiegeObjective(id, id, name, at(id), null, 100, 3.0, id == 1, holder, true, SiegeGateState.OPEN);
    }

    /** A running match: defenders hold Keep and Gate; this player is on {@code team}. */
    private SiegeMatch match(int team) {
        KnkSiegeScenario scenario = new KnkSiegeScenario(1, "Kardenna", null, 5, "Kardenna", null, List.of(), null, 2, 20,
            null, null, KnkSiegeMatchLength.DEFAULT, null, true, false, true,
            List.of(new KnkSiegeTeam(DEF, 1, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "WHITE", null, null, List.of()),
                new KnkSiegeTeam(ATT, 2, SiegeTeamRole.ATTACKER, 2, null, "Raiders", "WHITE", null, null, List.of())),
            List.of(objective(1, "Keep", DEF), objective(2, "Gate", DEF)), List.of(), List.of());
        AllianceResolver alliances = AllianceResolver.of(scenario);
        SiegeMatch match = mock(SiegeMatch.class);
        when(match.scenario()).thenReturn(scenario);
        when(match.alliances()).thenReturn(alliances);
        when(match.board()).thenReturn(new SiegeObjectiveBoard(scenario, new CaptureCalculator(KnkSiegeConfiguration.legacyDefaults()), alliances));
        when(match.roster()).thenReturn(new SiegeMatchRoster(Map.of(team, List.of(id))));
        when(match.matchToken()).thenReturn("m1");
        return match;
    }

    private void inMatch(int team) {
        SiegeMatch match = match(team);
        when(siege.runningMatchOf(id)).thenReturn(Optional.of(match));
        when(siege.lobbyOf(id)).thenReturn(Optional.of(mock(SiegeLobbyRuntime.class)));
    }

    private String lastMessage() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return PlainTextComponentSerializer.plainText().serialize(captor.getValue());
    }

    private Destination navigatedTo(int times) {
        ArgumentCaptor<Destination> captor = ArgumentCaptor.forClass(Destination.class);
        verify(navigation, times(times)).navigate(any(), captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private void tick() {
        clock.addAndGet(NavigationCompassService.CLICK_COOLDOWN_MILLIS + 1);
    }

    // ==================== the item ====================

    @Test
    void anyItemWithTheConfiguredMaterialAndNameIsTheCompass() {
        assertTrue(compass.isCompass("COMPASS", "Navigation Compass"));
        assertTrue(compass.isCompass("COMPASS", "§6Navigation Compass"));
        assertFalse(compass.isCompass("COMPASS", "Compass"));
        assertFalse(compass.isCompass("COMPASS", null));
        assertFalse(compass.isCompass("RECOVERY_COMPASS", "Navigation Compass"));
        assertFalse(compass.isCompass(null));
    }

    @Test
    void theRuleFollowsTheSettings() {
        settings = new CompassSettings(new CompassItemRule(true, "recovery_compass", "pathfinder", NameMatch.CONTAINS), true, false, List.of());

        assertTrue(compass.isCompass("RECOVERY_COMPASS", "Elder Pathfinder II"));
        assertFalse(compass.isCompass("COMPASS", "Navigation Compass"));
    }

    // ==================== in a siege match ====================

    @Test
    void aDefenderCyclesThroughTheObjectivesAndWrapsRound() {
        inMatch(DEF);

        assertTrue(compass.use(player, false));
        assertEquals("Keep", navigatedTo(1).name());
        assertTrue(lastMessage().contains("Compass 1/2: Keep (defend)"), lastMessage());

        tick();
        compass.use(player, false);
        assertEquals("Gate", navigatedTo(2).name());

        tick();
        compass.use(player, false);
        assertEquals("Keep", navigatedTo(3).name());
    }

    @Test
    void theDestinationIsTheObjectivesLocation() {
        inMatch(ATT);

        compass.use(player, false);

        Destination destination = navigatedTo(1);
        assertEquals(Destination.Kind.POINT, destination.kind());
        assertEquals("siege_world", destination.world());
        assertEquals(10.0, destination.point()[0]);
        assertEquals(64.0, destination.point()[1]);
    }

    @Test
    void shiftClickStopsARunningNavigationAndBringsItBack() {
        inMatch(DEF);
        compass.use(player, false);
        when(navigation.isNavigating(id)).thenReturn(true);
        when(navigation.stop(player)).thenReturn(true);

        tick();
        compass.use(player, true);
        verify(navigation).stop(player);

        when(navigation.isNavigating(id)).thenReturn(false);
        tick();
        compass.use(player, true);
        assertEquals("Keep", navigatedTo(2).name());
    }

    @Test
    void shiftClickBeforeAnyChoiceStartsTheFirstTarget() {
        inMatch(DEF);

        compass.use(player, true);

        assertEquals("Keep", navigatedTo(1).name());
    }

    @Test
    void aSecondClickWithinTheCooldownIsIgnored() {
        inMatch(DEF);

        compass.use(player, false);
        compass.use(player, false);

        verify(navigation, times(1)).navigate(any(), any());
    }

    @Test
    void theCycleStartsAfreshInANewMatch() {
        inMatch(DEF);
        compass.use(player, false);
        tick();
        compass.use(player, false);
        assertEquals("Gate", navigatedTo(2).name());

        SiegeMatch next = match(DEF);
        when(next.matchToken()).thenReturn("m2");
        when(siege.runningMatchOf(id)).thenReturn(Optional.of(next));
        tick();
        compass.use(player, false);

        assertEquals("Keep", navigatedTo(3).name());
    }

    @Test
    void aLobbyMemberWhoseMatchHasNotStartedIsTold() {
        when(siege.runningMatchOf(id)).thenReturn(Optional.empty());
        when(siege.lobbyOf(id)).thenReturn(Optional.of(mock(SiegeLobbyRuntime.class)));

        assertTrue(compass.use(player, false));

        verify(navigation, never()).navigate(any(), any());
        assertTrue(lastMessage().contains("once the siege match has started"), lastMessage());
    }

    @Test
    void theCompassCanBeSwitchedOffForSieges() {
        inMatch(DEF);
        settings = new CompassSettings(CompassItemRule.defaults(), false, false, List.of());

        compass.use(player, false);

        verify(navigation, never()).navigate(any(), any());
        assertTrue(lastMessage().contains("switched off during sieges"), lastMessage());
    }

    // ==================== the match scope of /navigate ====================

    @Test
    void inAMatchTheScopeIsTheCompassListAndNothingElse() {
        inMatch(DEF);

        var scope = compass.destinationsOf(player).orElseThrow();

        assertEquals(List.of("Keep", "Gate"), scope.stream().map(MatchNavigationScope.MatchDestination::label).toList());
        assertTrue(compass.allows(player, scope.get(0).destination()));
        assertFalse(compass.allows(player, Destination.point("Kardenna Castle", "siege_world", 1, 64, 1)));
    }

    @Test
    void outsideAMatchThereIsNoScope() {
        when(siege.runningMatchOf(id)).thenReturn(Optional.empty());

        assertTrue(compass.destinationsOf(player).isEmpty());
        assertTrue(compass.allows(player, Destination.point("Anywhere", "siege_world", 1, 64, 1)));
    }

    @Test
    void aSiegeWithTheCompassSwitchedOffHasAScopeWithoutDestinations() {
        inMatch(DEF);
        settings = new CompassSettings(CompassItemRule.defaults(), false, false, List.of());

        assertEquals(List.of(), compass.destinationsOf(player).orElseThrow());
    }

    // ==================== outside a siege ====================

    @Test
    void outsideASiegeTheCompassDoesNothingUnlessEnabled() {
        when(siege.runningMatchOf(id)).thenReturn(Optional.empty());
        when(siege.lobbyOf(id)).thenReturn(Optional.empty());

        compass.use(player, false);

        verify(navigation, never()).navigate(any(), any());
        assertTrue(lastMessage().contains("no destinations here"), lastMessage());
    }

    @Test
    void outsideASiegeItCyclesTheConfiguredDestinationsInOrder() {
        when(siege.runningMatchOf(id)).thenReturn(Optional.empty());
        when(siege.lobbyOf(id)).thenReturn(Optional.empty());
        settings = new CompassSettings(CompassItemRule.defaults(), true, true, List.of("town:Kardenna", "Market Square spawn", "nowhere"));
        NavTarget kardenna = NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna");
        NavTarget market = NavTarget.domain(NavTarget.Type.STRUCTURE, 2, "Market Square");
        when(destinations.resolve("town:Kardenna", "siege_world")).thenReturn(new NavigationDestinations.Resolution(kardenna, List.of(), List.of()));
        when(destinations.resolve("Market Square", "siege_world")).thenReturn(new NavigationDestinations.Resolution(market, List.of(), List.of()));
        when(destinations.resolve("nowhere", "siege_world")).thenReturn(new NavigationDestinations.Resolution(null, List.of(), List.of()));
        when(destinations.locate(kardenna, NavigationDestinations.Mode.DEFAULT, "siege_world"))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                NavigationDestinations.Located.of(Destination.point("Kardenna", "siege_world", 1, 64, 1))));
        when(destinations.locate(market, NavigationDestinations.Mode.SPAWN, "siege_world"))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                NavigationDestinations.Located.of(Destination.point("Market Square", "siege_world", 2, 64, 2))));

        compass.use(player, false);
        assertEquals("Kardenna", navigatedTo(1).name());
        assertTrue(lastMessage().contains("Compass 1/2: Kardenna"), lastMessage());

        tick();
        compass.use(player, false);
        assertEquals("Market Square", navigatedTo(2).name());
    }

    // ==================== refusals ====================

    @Test
    void withoutThePermissionNodeNothingHappens() {
        inMatch(DEF);
        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(false);

        compass.use(player, false);

        verify(navigation, never()).navigate(any(), any());
        assertTrue(lastMessage().contains("permission"), lastMessage());
    }

    @Test
    void withoutNavigationTheCompassSaysSo() {
        NavigationCompassService off = new NavigationCompassService(() -> settings, () -> null, () -> null, () -> siege,
            (p, node) -> false, Runnable::run, clock::get, items);

        assertTrue(off.use(player, false));

        assertTrue(lastMessage().contains("not available"), lastMessage());
    }

    @Test
    void aTeamWithoutARosterEntryGetsNoTargets() {
        SiegeMatch stranger = match(DEF);
        when(stranger.roster()).thenReturn(new SiegeMatchRoster(Map.of(DEF, List.of(UUID.randomUUID()))));
        when(siege.runningMatchOf(id)).thenReturn(Optional.of(stranger));
        when(siege.lobbyOf(id)).thenReturn(Optional.of(mock(SiegeLobbyRuntime.class)));

        compass.use(player, false);

        verify(navigation, never()).navigate(any(), any());
    }

    // ==================== given by the siege at the hub ====================

    /** Records what the siege hands out instead of touching Bukkit items. */
    private static final class FakeItems implements CompassItems {
        final ItemStack stack = mock(ItemStack.class);
        boolean holds;
        boolean fits = true;
        boolean usable = true;
        int given;

        @Override
        public ItemStack create(CompassItemRule rule) {
            return usable ? stack : null;
        }

        @Override
        public String nameOf(CompassItemRule rule) {
            return "Navigation Compass";
        }

        @Override
        public boolean holds(Player player, CompassItemRule rule) {
            return holds;
        }

        @Override
        public boolean give(Player player, ItemStack item) {
            if (fits) {
                given++;
            }
            return fits;
        }
    }

    private SiegeLobbyRuntime lobby(SiegeNavigationItemMode lobbyChoice, boolean global) {
        KnkSiegeConfiguration base = KnkSiegeConfiguration.legacyDefaults();
        KnkSiegeConfiguration configuration = new KnkSiegeConfiguration(base.captureAttackBase(), base.captureAttackPerExtra(),
            base.captureAttackPerExtraInstantVictory(), base.captureDefendBase(), base.captureDefendPerExtra(),
            base.captureDefendPerExtraInstantVictory(), base.sideCaptureReduction(), base.voteCloseSecondsBeforeStart(),
            base.drawSecondsBeforeStart(), base.hubSecondsBeforeStart(), base.teamSplitSecondsBeforeStart(),
            base.matchmakingAnnouncementMarks(), base.killAnnouncementThresholds(), base.killStreakAnnounceAbove(),
            base.headshotMultiplier(), base.allowedCommands(), base.spawnPickerDelayTicks(), base.enchantDropChancePerMille(),
            base.allowedEnchantmentKeys(), base.enchantLevelMin(), base.enchantLevelMax(), base.maxBooksAlive(),
            base.nonMemberGateView(), global);
        SiegeLobbyRuntime runtime = mock(SiegeLobbyRuntime.class);
        SiegeLobbyStateMachine machine = mock(SiegeLobbyStateMachine.class);
        when(machine.configuration()).thenReturn(configuration);
        when(runtime.machine()).thenReturn(machine);
        when(runtime.lobby()).thenReturn(new KnkSiegeLobby(1, "Cinix", "cinix", SiegeLobbyMode.CONTINUOUS, 300, 900, 2, true,
            List.of(), List.of(), lobbyChoice));
        return runtime;
    }

    private static KnkSiegeScenario scenarioWith(SiegeNavigationItemMode choice) {
        return new KnkSiegeScenario(1, "Kardenna", null, 5, "Kardenna", null, List.of(), null, 2, 20, null, null,
            KnkSiegeMatchLength.DEFAULT, null, true, false, true, List.of(), List.of(), List.of(), List.of(), choice);
    }

    @Test
    void theGlobalSettingGivesTheCompassWhenLobbyAndScenarioInherit() {
        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.INHERIT, true), scenarioWith(SiegeNavigationItemMode.INHERIT), player);

        assertEquals(1, items.given);
        assertTrue(lastMessage().contains("You received a Navigation Compass"), lastMessage());
    }

    @Test
    void nothingIsGivenWhenEveryLevelLeavesItOff() {
        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.INHERIT, false), scenarioWith(SiegeNavigationItemMode.INHERIT), player);

        assertEquals(0, items.given);
    }

    @Test
    void aScenarioOverridesTheGlobalSetting() {
        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.INHERIT, false), scenarioWith(SiegeNavigationItemMode.GIVE), player);
        assertEquals(1, items.given);

        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.INHERIT, true), scenarioWith(SiegeNavigationItemMode.DO_NOT_GIVE), player);
        assertEquals(1, items.given, "the scenario said no");
    }

    @Test
    void aLobbyOverridesTheScenario() {
        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.DO_NOT_GIVE, true), scenarioWith(SiegeNavigationItemMode.GIVE), player);
        assertEquals(0, items.given);

        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.GIVE, false), scenarioWith(SiegeNavigationItemMode.DO_NOT_GIVE), player);
        assertEquals(1, items.given);
    }

    @Test
    void nothingIsGivenWhenTheCompassIsSwitchedOffForSiegesOrEntirely() {
        settings = new CompassSettings(CompassItemRule.defaults(), false, false, List.of());
        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.GIVE, true), scenarioWith(SiegeNavigationItemMode.GIVE), player);

        settings = new CompassSettings(new CompassItemRule(false, "COMPASS", "Navigation Compass", NameMatch.EXACT), true, false, List.of());
        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.GIVE, true), scenarioWith(SiegeNavigationItemMode.GIVE), player);

        assertEquals(0, items.given);
    }

    @Test
    void aPlayerWhoAlreadyCarriesACompassGetsNoSecondOne() {
        items.holds = true;

        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.GIVE, false), scenarioWith(SiegeNavigationItemMode.INHERIT), player);

        assertEquals(0, items.given);
    }

    @Test
    void aFullInventoryIsToldSoAndNothingIsDropped() {
        items.fits = false;

        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.GIVE, false), scenarioWith(SiegeNavigationItemMode.INHERIT), player);

        assertEquals(0, items.given);
        assertTrue(lastMessage().contains("inventory is full"), lastMessage());
    }

    @Test
    void aRuleWithoutAUsableMaterialGivesNothingAndSaysNothing() {
        items.usable = false;

        compass.memberReadiedAtHub(lobby(SiegeNavigationItemMode.GIVE, false), scenarioWith(SiegeNavigationItemMode.INHERIT), player);

        assertEquals(0, items.given);
        verify(player, never()).sendMessage(any(Component.class));
    }
}
