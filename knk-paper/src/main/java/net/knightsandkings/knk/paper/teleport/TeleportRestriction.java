package net.knightsandkings.knk.paper.teleport;

import java.util.Optional;
import java.util.Set;

import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * A rule that can refuse a teleport (docs/specs/teleport/DESIGN.md §3.4 step 1). Checked on the
 * main thread before the warmup and again right before the teleport happens, so a player who got
 * frozen or joined a siege mid-warmup is still refused.
 * <p>
 * Registered through {@link TeleportService#registerRestriction} - that's how the siege minigame
 * plugs in its match / area-lockdown guards (DESIGN §4 D9) without the teleport engine depending on
 * the siege code: a {@code SiegeTeleportRestriction} on the siege branch implements this with
 * {@code SiegeService.activeLobbyOf} (subject and {@link TeleportCheck#visited()}) and
 * {@code SiegeAreaLockdown.blockingEntry}, declaring {@code knk.siege.bypass.commands} and
 * {@code knk.siege.bypass.lockdown} as its bypass nodes.
 * <p>
 * Must be quick and must not block: it runs on the main thread.
 */
public interface TeleportRestriction {

    /** Empty when allowed; otherwise why not (shown to whoever started the teleport). */
    Optional<TeleportDenial> deny(TeleportCheck check);

    /** Permission nodes this restriction reads through {@link TeleportCheck#hasBypass}. */
    default Set<String> bypassNodes() {
        return Set.of();
    }
}
