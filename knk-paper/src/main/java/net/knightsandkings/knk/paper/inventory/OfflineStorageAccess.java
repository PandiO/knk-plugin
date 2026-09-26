package net.knightsandkings.knk.paper.inventory;

import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * An offline player's saved inventory and ender chest (KNG-13), for {@code /inventory} and
 * {@code /enderchest}. Permission and rank checks are the caller's; if the target turns out to be
 * online by now, their live storage is used instead. Main thread only.
 */
public interface OfflineStorageAccess {

    enum Kind {
        INVENTORY("inventory"),
        ENDER_CHEST("ender chest");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Opens the target's saved storage for {@code viewer}; edits are saved when the view closes. */
    void open(Player viewer, UUID target, String targetName, Kind kind);

    /** Empties the target's saved inventory, armour and off-hand included. */
    void clearInventory(CommandSender sender, UUID target, String targetName);
}
