package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.paper.gates.GatePassThroughRules;

/**
 * A player as an {@link AccessSubject} (PandiO/knk-workspace#360): every answer is the one player navigation reads
 * from the player today ({@link NavigationAccess#bypasses}, {@link GatePassThroughRules}, the regions at the
 * player's feet), read when asked. The access code that takes a subject recognises this adapter where a rule needs
 * the player itself (siege lockdown membership, WorldGuard's door check).
 */
public final class PlayerAccessSubject implements AccessSubject {

    private final Player player;
    private final NavigationAccess access;

    public PlayerAccessSubject(Player player, NavigationAccess access) {
        this.player = Objects.requireNonNull(player, "player");
        this.access = Objects.requireNonNull(access, "access");
    }

    public Player player() {
        return player;
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public Optional<UUID> playerId() {
        return Optional.of(player.getUniqueId());
    }

    @Override
    public boolean bypassesDomainRules() {
        return access.bypasses(player);
    }

    @Override
    public boolean gateAdmin() {
        return GatePassThroughRules.isAdmin(player);
    }

    @Override
    public boolean mayUseGates() {
        return GatePassThroughRules.mayUse(player);
    }

    /**
     * Doors are WorldGuard's answer for the player and a block; the walk access asks it itself for a player subject
     * ({@code WalkAccessFactory}), so this is only the bypass half.
     */
    @Override
    public boolean mayOpenDoor(String world, int x, int y, int z) {
        return true;
    }

    @Override
    public String world() {
        Location location = player.getLocation();
        return location == null || location.getWorld() == null ? null : location.getWorld().getName();
    }

    @Override
    public Set<String> currentRegions() {
        return access.regionsAt(player);
    }
}
