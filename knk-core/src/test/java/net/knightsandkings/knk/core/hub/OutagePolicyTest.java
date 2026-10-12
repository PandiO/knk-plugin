package net.knightsandkings.knk.core.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings.OutageAction;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.PermissionGroupRef;

/** Hub DESIGN §13 and §16.4 decision 5: per-field group overrides of the outage action, and the mode exemptions. */
class OutagePolicyTest {

    private static final PermissionGroupRef STAFF = new PermissionGroupRef(3, "Staff");
    private static final PermissionGroupRef NOBLE = new PermissionGroupRef(2, "Noble");
    private static final PermissionGroupRef DEFAULT = new PermissionGroupRef(1, "Default");

    private static final KnkGameSettings SETTINGS = new KnkGameSettings("WorldSpawn", null, null, null, null, List.of(), null, null,
        List.of(
            new KnkGroupOverride(3, "Staff", 1, null, null, null, null, false, null, null, true, false),
            new KnkGroupOverride(2, "Noble", 2, null, null, null, null, false, OutageAction.KICK, null, false, false),
            new KnkGroupOverride(1, "Default", 3, null, null, null, null, false, null, 120, false, false)),
        new KnkHubSettings(4, "Hubtown", "Town", "hub", "town_4", null, OutageAction.SEND_TO_HUB, 30, KnkHubRules.defaults()));

    @Test
    void actionAndDelayComeFromTheFirstGroupThatSetsEach() {
        assertEquals(new OutagePolicy.Applicable(OutageAction.KICK, 120),
            OutagePolicy.applicable(SETTINGS, List.of(NOBLE, DEFAULT)));
        assertEquals(new OutagePolicy.Applicable(OutageAction.SEND_TO_HUB, 30),
            OutagePolicy.applicable(SETTINGS, List.of(STAFF)), "the hub's defaults");
        assertEquals(new OutagePolicy.Applicable(OutageAction.SEND_TO_HUB, 0), OutagePolicy.applicable(null, List.of()));
    }

    @Test
    void onlyAnActiveModeWithATickedBoxExempts() {
        assertTrue(OutagePolicy.exempt(SETTINGS, List.of(NOBLE, STAFF), ActiveMode.STAFF), "any of the player's groups");
        assertFalse(OutagePolicy.exempt(SETTINGS, List.of(STAFF), ActiveMode.NONE), "the rank alone exempts nobody");
        assertFalse(OutagePolicy.exempt(SETTINGS, List.of(STAFF), ActiveMode.OWNER), "the owner box is not ticked");
        assertFalse(OutagePolicy.exempt(SETTINGS, List.of(NOBLE), ActiveMode.STAFF));
    }
}
