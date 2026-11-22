package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;
import net.knightsandkings.knk.core.landmarks.LandmarkMessageSettings;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/**
 * Landmark-domain DESIGN § 5 rules 6 and 7, LD05, LD15, A10, A12 (PandiO/knk-workspace#985): a landmark is silent
 * unless its enter and leave messages are on; then one message per crossing, none between its sub-regions; its entry
 * rule applies like any domain's; a type-less domain no longer breaks the gate check (§ 7.2 item 4).
 */
class LandmarkTransitionMessagesTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final RegionDomainResolver resolver = new RegionDomainResolver();
    private final LandmarkMessageSettings settings = new LandmarkMessageSettings();

    private static DomainSnapshot landmark(int id, String name, String regionId, Boolean allowEntry) {
        return new DomainSnapshot(id, name, "", regionId, allowEntry, true, "Landmark", Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static KnkLandmark row(int id, boolean messages) {
        return new KnkLandmark(id, "L" + id, 1, "world", 0, 0, 0, 0f, 0f, null, null, null, null, LandmarkAreaKind.OWN_REGION,
            "domain_" + id, List.of(), messages, 1.0, true);
    }

    private SimpleRegionTransitionService service() {
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);
        service.setLandmarkMessages(settings);
        return service;
    }

    @Test
    void withMessagesOff_EnteringAndLeavingALandmarkShowsNothing() {
        resolver.registerDomain(landmark(7, "Tower", "domain_7", true));
        settings.replace(List.of(row(7, false)));

        RegionTransitionDecision in = service().handleRegionTransition(PLAYER, Set.of(), Set.of("domain_7"));
        RegionTransitionDecision out = service().handleRegionTransition(PLAYER, Set.of("domain_7"), Set.of());

        assertTrue(in.isMovementAllowed());
        assertEquals(Optional.empty(), in.getMessage());
        assertEquals(Optional.empty(), out.getMessage());
        assertEquals(Optional.empty(), new SimpleRegionTransitionService(resolver)
            .handleRegionTransition(PLAYER, Set.of(), Set.of("domain_7")).getMessage(), "no settings: silent (LD24)");
    }

    @Test
    void withMessagesOn_OneEnterAndOneLeave_NothingBetweenItsSubRegions() {
        DomainSnapshot tower = landmark(7, "Tower", "domain_7", true);
        resolver.registerDomain(tower);
        resolver.registerRegion(null, "domain_7_sub_1", tower);
        settings.replace(List.of(row(7, true)));
        SimpleRegionTransitionService service = service();

        assertEquals(Optional.of("You are now entering Tower."),
            service.handleRegionTransition(PLAYER, Set.of(), Set.of("domain_7")).getMessage());
        assertEquals(Optional.empty(),
            service.handleRegionTransition(PLAYER, Set.of("domain_7"), Set.of("domain_7_sub_1")).getMessage(),
            "into its sub-region: the same landmark");
        assertEquals(Optional.of("You are now leaving Tower."),
            service.handleRegionTransition(PLAYER, Set.of("domain_7_sub_1"), Set.of()).getMessage());
    }

    @Test
    void aTownsMessageStillWins_AndALandmarkInsideTheTownSpeaksOnlyForItself() {
        resolver.registerDomain(new DomainSnapshot(1, "Rivia", "", "town_1", true, true, "Town", Set.of(), Set.of(), Set.of(),
            Set.of()));
        resolver.registerDomain(landmark(7, "Well", "domain_7", true));
        settings.replace(List.of(row(7, true)));
        SimpleRegionTransitionService service = service();

        assertEquals(Optional.of("You are now entering Rivia."),
            service.handleRegionTransition(PLAYER, Set.of(), Set.of("town_1", "domain_7")).getMessage());
        assertEquals(Optional.of("You are now entering Well."),
            service.handleRegionTransition(PLAYER, Set.of("town_1"), Set.of("town_1", "domain_7")).getMessage());
    }

    @Test
    void aLandmarkWithEntryOff_KeepsThePlayerOut_LikeAnyDomain() {
        resolver.registerDomain(landmark(7, "Vault", "domain_7", false));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver);

        RegionTransitionDecision decision = service.handleRegionTransition(PLAYER, Set.of(), Set.of("domain_7"));

        assertFalse(decision.isMovementAllowed());
        assertEquals(Optional.of("You are not allowed to enter Vault."), decision.getMessage());
    }

    @Test
    void aDomainWithoutAType_DoesNotBreakTheGateCheck() {
        resolver.registerDomain(new DomainSnapshot(9, "Odd", "", "odd_9", true, true, null, Set.of(), Set.of(), Set.of(),
            Set.of()));
        java.util.List<String> opened = new java.util.ArrayList<>();
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver, new net.knightsandkings.knk.core.ports.gates.GateControlPort() {
            @Override
            public void openGate(UUID gateId, UUID playerId) {
                opened.add("open");
            }

            @Override
            public void closeGate(UUID gateId, UUID playerId) {
                opened.add("close");
            }
        });

        assertTrue(service.handleRegionTransition(PLAYER, Set.of(), Set.of("odd_9")).isMovementAllowed());
        assertTrue(service.handleRegionTransition(PLAYER, Set.of("odd_9"), Set.of()).isMovementAllowed());
        assertEquals(List.of(), opened);
    }

    @Test
    void theSettingsKeepTheLastSetWhenARefreshFails() {
        settings.replace(List.of(row(7, true), row(8, false)));
        assertTrue(settings.test(7));
        assertFalse(settings.test(8));
        assertFalse(settings.test(9), "unknown: off");
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<List<KnkLandmark>>> answer =
            new java.util.concurrent.atomic.AtomicReference<>(
                java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("down")));
        net.knightsandkings.knk.core.ports.api.LandmarksApi api = new net.knightsandkings.knk.core.ports.api.LandmarksApi() {
            @Override
            public java.util.concurrent.CompletableFuture<List<KnkLandmark>> list(String world) {
                return answer.get();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Optional<KnkLandmark>> get(int id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.CompletableFuture<KnkLandmark> create(
                    net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite landmark) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.CompletableFuture<KnkLandmark> update(int id,
                    net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite landmark) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> delete(int id) {
                throw new UnsupportedOperationException();
            }
        };
        settings.refresh(api).join();
        assertTrue(settings.test(7), "kept");
        answer.set(java.util.concurrent.CompletableFuture.completedFuture(List.of(row(8, true))));
        settings.refresh(api).join();
        assertFalse(settings.test(7));
        assertTrue(settings.test(8));
    }
}
