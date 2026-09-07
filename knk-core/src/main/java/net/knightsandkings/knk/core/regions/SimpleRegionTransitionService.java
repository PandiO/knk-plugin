package net.knightsandkings.knk.core.regions;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.ports.gates.GateControlPort;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.RegionSnapshot;

/**
 * Full implementation of region transition logic.
 * Resolves WG region IDs to domain entities, enforces entry/exit policies,
 * builds priority-based messages, and triggers gate control.
 * 
 * Implements three key features:
 * 1. Entry/exit policy enforcement (allowEntry/allowExit)
 * 2. Town > District > Structure priority for messaging
 * 3. Gate control via GateControlPort (optional)
 */
public class SimpleRegionTransitionService implements RegionTransitionService {
    private static final Logger LOGGER = Logger.getLogger(SimpleRegionTransitionService.class.getName());
    
    private final RegionDomainResolver regionResolver;
    private final GateControlPort gateControlPort;
    private final Consumer<Set<DomainSnapshot>> onDomainsEntered;

    /**
     * Construct with resolver, optional gate control, and an optional callback invoked with the
     * set of domains entered by this transition (e.g. used to trigger on-demand gate loading the
     * first time a player is resolved into a District - see DistrictGateLoader/KnKPlugin).
     */
    public SimpleRegionTransitionService(
        RegionDomainResolver regionResolver,
        GateControlPort gateControlPort,
        Consumer<Set<DomainSnapshot>> onDomainsEntered
    ) {
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver");
        this.gateControlPort = gateControlPort;  // May be null if gates not implemented
        this.onDomainsEntered = onDomainsEntered;  // May be null if no listener is needed
    }

    /**
     * Construct with resolver and optional gate control.
     */
    public SimpleRegionTransitionService(RegionDomainResolver regionResolver, GateControlPort gateControlPort) {
        this(regionResolver, gateControlPort, null);
    }

    /**
     * Construct with resolver only (no gate control).
     * Useful for basic region entry/exit without gates.
     */
    public SimpleRegionTransitionService(RegionDomainResolver regionResolver) {
        this(regionResolver, null, null);
    }

    /**
     * Backward-compatible no-arg constructor.
     * Initializes with a default RegionDomainResolver and no gate control.
     * Note: Resolver uses in-memory placeholders until API-backed lookup is implemented.
     */
    public SimpleRegionTransitionService() {
        this(new RegionDomainResolver(), null, null);
    }

    @Override
    public RegionTransitionDecision handleRegionTransition(UUID playerId, Set<String> oldRegionIds, Set<String> newRegionIds) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(oldRegionIds, "oldRegionIds");
        Objects.requireNonNull(newRegionIds, "newRegionIds");

        LOGGER.info("[KnK Service] handleRegionTransition called: oldRegionIds=" + oldRegionIds + ", newRegionIds=" + newRegionIds);

        // Resolve domain entities from WG region IDs
        RegionSnapshot oldSnapshot = regionResolver.resolveRegions(oldRegionIds);
        RegionSnapshot newSnapshot = regionResolver.resolveRegions(newRegionIds);

        LOGGER.info("[KnK Service] Resolved: oldSnapshot=(domains=" + oldSnapshot.domains().size() + 
                    "), newSnapshot=(domains=" + newSnapshot.domains().size() + ")");

        // Determine entered and left entities
        EnteredLeftSnapshot transition = computeTransition(oldSnapshot, newSnapshot);

        LOGGER.info("[KnK Service] Transition: enteredDomains=" + transition.enteredDomains().size() + 
                    ", leftDomains=" + transition.leftDomains().size());

        // TODO 1: Enforce entry/exit policies
        // Check entry permissions for all entered entities (Town > District > Structure priority)
        RegionTransitionDecision entryDeny = checkEntryDenials(transition);
        if (entryDeny != null) {
            LOGGER.info("[KnK Service] ENTRY DENIED: " + entryDeny.getMessage().orElse("(no message)"));
            return entryDeny;
        }

        // Check exit permissions for all left entities
        RegionTransitionDecision exitDeny = checkExitDenials(transition);
        if (exitDeny != null) {
            LOGGER.info("[KnK Service] EXIT DENIED: " + exitDeny.getMessage().orElse("(no message)"));
            return exitDeny;
        }

        // TODO 2: Apply Town > District > Structure priority for messaging
        RegionTransitionDecision decision = buildPriorityMessage(transition);
        LOGGER.info("[KnK Service] Priority message: " + decision.getMessage().orElse("(none)"));

        // TODO 3: Trigger gate control for entered/left gates
        if (gateControlPort != null) {
            triggerGateControl(playerId, transition);
        }

        if (onDomainsEntered != null && !transition.enteredDomains().isEmpty()) {
            onDomainsEntered.accept(transition.enteredDomains());
        }

        return decision;
    }

    /**
     * Compute which entities are entered and which are left.
     */
    private EnteredLeftSnapshot computeTransition(RegionSnapshot oldSnapshot, RegionSnapshot newSnapshot) {
        Set<DomainSnapshot> enteredDomains = new HashSet<>(newSnapshot.domains());
        enteredDomains.removeAll(oldSnapshot.domains());

        Set<DomainSnapshot> leftDomains = new HashSet<>(oldSnapshot.domains());
        leftDomains.removeAll(newSnapshot.domains());

        return new EnteredLeftSnapshot(
            enteredDomains, leftDomains,
            oldSnapshot, newSnapshot
        );
    }

    /**
     * Check if entry is denied for any entered entity.
     * Returns a deny decision if any entry is not allowed; null otherwise.
     * Priority: Town > District > Structure.
     */
    private RegionTransitionDecision checkEntryDenials(EnteredLeftSnapshot transition) {
        // Check entered towns first (highest priority)
        for (DomainSnapshot domain : transition.enteredDomains()) {
            if (domain.allowEntry() != null && !domain.allowEntry()) {
                return RegionTransitionDecision.deny(RegionTransitionType.ENTER, "You are not allowed to enter " + domain.name() + ".");
            }
        }

        return null;  // No denials
    }

    /**
     * Check if exit is denied for any left entity.
     * Returns a deny decision if any exit is not allowed; null otherwise.
     */
    private RegionTransitionDecision checkExitDenials(EnteredLeftSnapshot transition) {
        // Check left towns first (highest priority)
        for (DomainSnapshot town : transition.leftDomains()) {
            if (town.allowExit() != null && !town.allowExit()) {
                return RegionTransitionDecision.deny(RegionTransitionType.EXIT, "You are not allowed to leave " + town.name() + ".");
            }
        }

        // Check left districts (second priority)
        for (DomainSnapshot district : transition.leftDomains()) {
            if (district.allowExit() != null && !district.allowExit()) {
                return RegionTransitionDecision.deny(RegionTransitionType.EXIT, "You are not allowed to leave " + district.name() + ".");
            }
        }

        // Check left structures (third priority)
        for (DomainSnapshot structure : transition.leftDomains()) {
            if (structure.allowExit() != null && !structure.allowExit()) {
                return RegionTransitionDecision.deny(RegionTransitionType.EXIT, "You are not allowed to leave " + structure.name() + ".");
            }
        }

        return null;  // No denials
    }

    /**
     * Build a welcome/farewell message with Town > District > Structure priority.
     * If the town changes, show a town message.
     * If the town stays the same but district changes, show a district message.
     * If town and district stay the same but structure changes, show a structure message.
     */
    private RegionTransitionDecision buildPriorityMessage(EnteredLeftSnapshot transition) {
        // Check if town changed
        RegionTransitionDecision townMessage = buildTownMessage(transition);
        if (townMessage.getMessage().isPresent()) {
            return townMessage;
        }

        // Check if district changed (same town)
        RegionTransitionDecision districtMessage = buildDistrictMessage(transition);
        if (districtMessage.getMessage().isPresent()) {
            return districtMessage;
        }

        // Check if structure changed (same town, same district)
        RegionTransitionDecision structureMessage = buildStructureMessage(transition);
        return structureMessage;
    }
    

    /**
     * Build a town-level message if a town was entered or left.
     */
    private RegionTransitionDecision buildTownMessage(EnteredLeftSnapshot transition) {
        Optional<DomainSnapshot> enteredTown = transition.enteredDomains().stream()
            .filter(d -> "town".equalsIgnoreCase(d.domainType()))
            .findFirst();
        if (enteredTown.isPresent()) {
            return RegionTransitionDecision.allow(RegionTransitionType.ENTER, "You are now entering " + enteredTown.get().name() + ".");
        }

        Optional<DomainSnapshot> leftTown = transition.leftDomains().stream()
            .filter(d -> "town".equalsIgnoreCase(d.domainType()))
            .findFirst();
        if (leftTown.isPresent()) {
            return RegionTransitionDecision.allow(RegionTransitionType.EXIT, "You are now leaving " + leftTown.get().name() + ".");
        }

        return RegionTransitionDecision.allow(RegionTransitionType.EXIT, null);
    }

    /**
     * Build a district-level message if a district was entered or left (same town).
     */
    private RegionTransitionDecision buildDistrictMessage(EnteredLeftSnapshot transition) {
        Optional<DomainSnapshot> enteredDistrict = transition.enteredDomains().stream()
            .filter(d -> "district".equalsIgnoreCase(d.domainType()))
            .findFirst();
        // Entering a new district in the same town
        if (enteredDistrict.isPresent()) {
            DomainSnapshot district = enteredDistrict.get();
            String message = "You are now entering " + district.name();
            if (district.parentDomainNames() != null && !district.parentDomainNames().isEmpty()) {
                message += " * " + district.parentDomainNames().iterator().next() + " *";
            } else {
                message += ".";
            }
            return RegionTransitionDecision.allow(RegionTransitionType.ENTER, message);
        }

        // Leaving a district in the same town
        // Optional<DomainSnapshot> leftDistrict = transition.leftDomains().stream()
        //     .filter(d -> "district".equalsIgnoreCase(d.domainType()))
        //     .findFirst();
        // if (leftDistrict.isPresent()) {
        //     DomainSnapshot district = leftDistrict.get();
        //     return Optional.of("You are now leaving " + district.name() + ".");
        // }

        return RegionTransitionDecision.allow(RegionTransitionType.EXIT, null);
    }

    /**
     * Build a structure-level message if a structure was entered or left (same town/district).
     */
    private RegionTransitionDecision buildStructureMessage(EnteredLeftSnapshot transition) {
        // Entering a new structure
        Optional<DomainSnapshot> enteredStructure = transition.enteredDomains().stream()
            .filter(d -> "structure".equalsIgnoreCase(d.domainType()))
            .findFirst();
        if (enteredStructure.isPresent()) {
            DomainSnapshot structure = enteredStructure.get();
            return RegionTransitionDecision.allow(RegionTransitionType.ENTER, "You are now entering " + structure.name() + ".");
        }

        // Leaving a structure
        // Optional<DomainSnapshot> leftStructure = transition.leftDomains().stream()
        //     .filter(d -> "structure".equalsIgnoreCase(d.domainType()))
        //     .findFirst();
        // if (leftStructure.isPresent()) {
        //     DomainSnapshot structure = leftStructure.get();
        //     return Optional.of("You are now leaving " + structure.name() + ".");
        // }

        return RegionTransitionDecision.allow(RegionTransitionType.EXIT, null);
    }

    /**
     * Trigger gate operations for entered/left gates.
     * 
     * TODO: Current implementation opens gate when any structure is entered,
     * and closes when any structure is left. Future enhancements:
     * - Only close gate if this is the last player leaving
     * - Track per-gate player counts
     * - Implement more complex gate behaviors
     */
    private void triggerGateControl(UUID playerId, EnteredLeftSnapshot transition) {
        // Open gates when player enters
        for (DomainSnapshot structure : transition.enteredDomains()) {
            if (structure.domainType().equals("gate")) {
                // Convert Integer ID to long for now; TODO: Use UUID from domain model when available
                gateControlPort.openGate(UUID.nameUUIDFromBytes(structure.id().toString().getBytes()), playerId);
            }
        }

        // Close gates when player leaves
        for (DomainSnapshot structure : transition.leftDomains()) {
            if (structure.domainType().equals("gate")) {
                gateControlPort.closeGate(UUID.nameUUIDFromBytes(structure.id().toString().getBytes()), playerId);
            }
        }
    }

    /**
     * Helper record to hold transition state.
     */
    private record EnteredLeftSnapshot(
        Set<DomainSnapshot> enteredDomains,
        Set<DomainSnapshot> leftDomains,
        RegionSnapshot oldSnapshot,
        RegionSnapshot newSnapshot
    ) {}
}
