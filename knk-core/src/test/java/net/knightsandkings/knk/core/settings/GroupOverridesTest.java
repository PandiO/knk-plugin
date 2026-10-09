package net.knightsandkings.knk.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;
import net.knightsandkings.knk.core.domain.users.UserSummary;

/** Per-group overrides of the Game Settings (docs/specs/game-settings/DESIGN.md §3.8, KNG-52). */
class GroupOverridesTest {

    private static final PermissionGroupRef DEFAULT = new PermissionGroupRef(1, "Default");
    private static final PermissionGroupRef NOBLE = new PermissionGroupRef(2, "Noble");
    private static final PermissionGroupRef STAFF = new PermissionGroupRef(3, "Staff");

    private static final KnkSpawnReference LOUNGE = new KnkSpawnReference(KnkSpawnReference.SourceType.STRUCTURE, 9,
        "Structure: Noble lounge", new KnkLocation(1, "lounge", 5.0, 70.0, 5.0, 0f, 0f, "world"));
    private static final KnkRespawnPolicy SYNCED = new KnkRespawnPolicy(KnkRespawnPolicy.Mode.JOIN_SPAWN, null, null, true);

    private static KnkGameSettings settings(KnkGroupOverride... overrides) {
        return new KnkGameSettings("WorldSpawn", null, "&a{player} joined", null, null, List.of(), null, null,
            Arrays.asList(overrides));
    }

    @Test
    void eachSettingIsPickedFromTheFirstGroupThatOverridesIt() {
        KnkGameSettings settings = settings(
            new KnkGroupOverride(2, "Noble", 1, null, LOUNGE, null),
            new KnkGroupOverride(1, "Default", 2, "&7{player} ({group})", null, SYNCED));
        List<PermissionGroupRef> player = List.of(NOBLE, DEFAULT);

        GroupOverrides.Pick<String> join = GroupOverrides.joinAnnouncement(settings, player).orElseThrow();
        assertEquals("&7{player} ({group})", join.value());
        assertEquals(DEFAULT, join.group());
        assertSame(LOUNGE, GroupOverrides.joinSpawn(settings, player).orElseThrow().value());
        assertEquals(SYNCED, GroupOverrides.respawnPolicy(settings, player).orElseThrow().value());
    }

    @Test
    void thePlayersGroupOrderDecides() {
        KnkGameSettings settings = settings(
            new KnkGroupOverride(3, "Staff", 1, "&c[Staff] {player}", null, null),
            new KnkGroupOverride(2, "Noble", 2, "&6Noble {player}", null, null));

        assertEquals("&6Noble {player}", GroupOverrides.joinAnnouncement(settings, List.of(NOBLE, STAFF)).orElseThrow().value());
        assertEquals("&c[Staff] {player}", GroupOverrides.joinAnnouncement(settings, List.of(STAFF, NOBLE)).orElseThrow().value());
    }

    @Test
    void noMatchingGroupMeansNoOverride() {
        KnkGameSettings settings = settings(new KnkGroupOverride(3, "Staff", 1, "x", LOUNGE, SYNCED));

        assertTrue(GroupOverrides.joinAnnouncement(settings, List.of(DEFAULT, NOBLE)).isEmpty());
        assertTrue(GroupOverrides.joinSpawn(settings, List.of()).isEmpty());
        assertTrue(GroupOverrides.respawnPolicy(settings, null).isEmpty());
        assertTrue(GroupOverrides.respawnPolicy(null, List.of(STAFF)).isEmpty());
        assertTrue(GroupOverrides.joinSpawn(settings, Arrays.asList(null, DEFAULT)).isEmpty());
    }

    @Test
    void aBlankGroupMessageIsAnOverrideThatSilencesTheJoin() {
        KnkGameSettings settings = settings(new KnkGroupOverride(3, "Staff", 1, "", null, null));

        String template = GroupOverrides.joinAnnouncement(settings, List.of(STAFF)).orElseThrow().value();

        assertEquals(Optional.empty(), Announcements.render(template, Announcements.DEFAULT_JOIN, "Alex", "Staff"));
    }

    @Test
    void theLeaveMessageIsPickedOnItsOwn() {
        KnkGameSettings settings = settings(
            new KnkGroupOverride(3, "Staff", 1, "&c[Staff] {player}", null, null),
            new KnkGroupOverride(2, "Noble", 2, null, "&6{group} {player} left", null, null));

        GroupOverrides.Pick<String> leave = GroupOverrides.leaveAnnouncement(settings, List.of(STAFF, NOBLE)).orElseThrow();
        assertEquals("&6{group} {player} left", leave.value());
        assertSame(NOBLE, leave.group());
        assertTrue(GroupOverrides.leaveAnnouncement(settings, List.of(STAFF)).isEmpty());
    }

    @Test
    void titlePlaceholder_AndEmptyPlaceholdersLeaveNoDoubleSpace() {
        assertEquals(Optional.of("- Noble Knight Steve joined the server."),
            Announcements.render("- {group} {titlename} {player} joined the server.", null, "Steve", "Noble", "Knight"));
        assertEquals(Optional.of("- Noble Knight Steve left"),
            Announcements.render("- {group} {title} {player} left", null, "Steve", "Noble", " Knight "));
        assertEquals(Optional.of("- Noble Steve joined the server."),
            Announcements.render("- {group} {title} {player} joined the server.", null, "Steve", "Noble", null));
        assertEquals(Optional.of("- Steve joined"),
            Announcements.render("- {group} {title} {player} joined", null, "Steve", "", ""));
    }

    @Test
    void primaryGroupNameIsTheFirstGroup() {
        assertEquals("Noble", GroupOverrides.primaryGroupName(List.of(NOBLE, DEFAULT)));
        assertEquals("", GroupOverrides.primaryGroupName(List.of()));
        assertEquals("", GroupOverrides.primaryGroupName(null));
    }

    @Test
    void groupPlaceholderAndMotd() {
        assertEquals(Optional.of("&6[Noble] &eSteve &7joined"),
            Announcements.render("&6[{group}] &e{player} &7joined", null, "Steve", "Noble"));
        assertEquals(Optional.of("&6Knights and Kings\n&e3/50 online"),
            Announcements.renderMotd("&6Knights and Kings\r\n&e{online}/{max} online", 3, 50));
        assertEquals(Optional.of("a\nb"), Announcements.renderMotd("a\nb\nc", 0, 0));
        assertEquals(Optional.empty(), Announcements.renderMotd("  ", 0, 0));
        assertEquals(Optional.empty(), Announcements.renderMotd(null, 0, 0));
    }

    @Test
    void overridesAreKeptInPrecedenceOrderAndLookedUpById() {
        KnkGameSettings settings = settings(
            new KnkGroupOverride(1, "Default", 2, "b", null, null),
            new KnkGroupOverride(2, "Noble", 1, "a", null, null));

        assertEquals(List.of(2, 1), settings.groupOverrides().stream().map(KnkGroupOverride::permissionGroupId).toList());
        assertEquals("b", settings.groupOverride(1).orElseThrow().joinAnnouncement());
        assertTrue(settings.groupOverride(7).isEmpty());
    }

    @Test
    void userSummaryCopiesKeepTheGroups() {
        UserSummary user = new UserSummary(1, "Steve", null, null, 10, 0, 0, true, false, GatePassThroughMethod.DEFAULT,
            ActiveMode.NONE, null, null, 0, null, null, null, false, null, null, null, null, null, List.of(NOBLE, DEFAULT));

        assertEquals(List.of(NOBLE, DEFAULT), user.withBalances(5, 5).permissionGroups());
        assertEquals(List.of(NOBLE, DEFAULT), user.withActiveMode(ActiveMode.STAFF).permissionGroups());
        assertEquals(List.of(NOBLE, DEFAULT), user.withFrozen(true, "x").permissionGroups());
        assertEquals(List.of(), new UserSummary(1, "Steve", null, 0).permissionGroups());
    }
}
