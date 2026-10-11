package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

import java.util.Optional;

/**
 * The one source of truth for "may this player enter / leave this domain" (DESIGN §6.7).
 *
 * <p>Extracted from {@code SimpleRegionTransitionService}'s private {@code checkEntryDenials} /
 * {@code checkExitDenials} (road navigation plan §2 R6): the region tracker asks it when a player
 * crosses a region border, the road router asks it for every domain a candidate route enters or
 * leaves, so navigation can never send a player somewhere the border check would stop them, and
 * any future entry condition (title, balance, clan, premium rank - vision §2.2) lands in both at
 * once. The messages are the ones the border check has always shown.
 *
 * <p>Pure and Bukkit-free: a {@link DomainSnapshot} in, an {@link Optional} {@link Denial} out.
 * A {@code null} {@code allowEntry}/{@code allowExit} flag means "not restricted", exactly as
 * before. Bypasses (staff, owner mode, KNG-17's {@code knk.region.bypass}) are the caller's
 * concern for now: evaluate, then decide whether the player may ignore the denial - the
 * teleport merger points {@code previewAccess} here and feeds its bypass predicate the same way.
 */
public final class DomainAccessEvaluator {

    /** Why a domain may not be entered or left, with the player-facing message. */
    public record Denial(RegionTransitionType type, DomainSnapshot domain, String message) {
    }

    /**
     * @return the denial if the domain forbids entry ({@code allowEntry == false}), else empty
     */
    public Optional<Denial> entry(DomainSnapshot domain) {
        if (domain != null && domain.allowEntry() != null && !domain.allowEntry()) {
            return Optional.of(new Denial(RegionTransitionType.ENTER, domain,
                "You are not allowed to enter " + domain.name() + "."));
        }
        return Optional.empty();
    }

    /**
     * @return the denial if the domain forbids leaving ({@code allowExit == false}), else empty
     */
    public Optional<Denial> exit(DomainSnapshot domain) {
        if (domain != null && domain.allowExit() != null && !domain.allowExit()) {
            return Optional.of(new Denial(RegionTransitionType.EXIT, domain,
                "You are not allowed to leave " + domain.name() + "."));
        }
        return Optional.empty();
    }
}
