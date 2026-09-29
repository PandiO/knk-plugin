package net.knightsandkings.knk.paper.commands;

import org.bukkit.command.CommandSender;

/**
 * {@code /knk tp <player> [-s]} - kept as an alias of {@code /tp <player>} (docs/specs/teleport/
 * DESIGN.md §4 D1) and gated, as before, on {@code knk.admin.tp} by {@code /knk} itself. Delegates to
 * {@link StaffTeleportCommand}, so it now gets the same vanish-aware lookup, silent flag, freeze/
 * region/siege guards and {@code COMMAND} teleport cause as {@code /tp}; it used to call
 * {@code Player.teleport} directly with the default {@code PLUGIN} cause, which the siege area
 * lockdown ignores.
 * <p>
 * History: no v1 player-to-player teleport ever worked - v1's owner-mode {@code /tpa <player>} had
 * the same dead {@code getOnlinePlayers().contains(String)} guard as the rest of
 * {@code PlayerTeleportCommand} (DESIGN §1.1), so this is a v3 feature, not a port.
 */
public class TeleportToPlayerCommand {
    private final StaffTeleportCommand staffTeleportCommand;

    public TeleportToPlayerCommand(StaffTeleportCommand staffTeleportCommand) {
        this.staffTeleportCommand = staffTeleportCommand;
    }

    public boolean onCommand(CommandSender sender, String[] args) {
        return staffTeleportCommand.onKnkTp(sender, args);
    }
}
