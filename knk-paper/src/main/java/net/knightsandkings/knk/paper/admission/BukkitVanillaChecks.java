package net.knightsandkings.knk.paper.admission;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.bukkit.BanEntry;
import org.bukkit.Bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;

/** {@link JoinRefusalNotifier.VanillaChecks} against the server's ban lists, whitelist and player limit. */
final class BukkitVanillaChecks implements JoinRefusalNotifier.VanillaChecks {

    @Override
    public String profileBanReason(PlayerProfile profile) {
        BanEntry<PlayerProfile> entry = Bukkit.getBanList(BanListType.PROFILE).getBanEntry(profile);
        if (entry == null) {
            return null;
        }
        return entry.getReason() == null ? "" : entry.getReason();
    }

    @Override
    public boolean ipBanned(InetSocketAddress address) {
        return address.getAddress() != null && Bukkit.getBanList(BanListType.IP).isBanned(address.getAddress());
    }

    @Override
    public boolean whitelistRefuses(UUID uuid) {
        // Vanilla lets operators through the whitelist.
        return Bukkit.hasWhitelist() && !Bukkit.getOfflinePlayer(uuid).isWhitelisted()
            && !Bukkit.getOfflinePlayer(uuid).isOp();
    }

    @Override
    public boolean full() {
        return Bukkit.getOnlinePlayers().size() >= Bukkit.getMaxPlayers();
    }
}
