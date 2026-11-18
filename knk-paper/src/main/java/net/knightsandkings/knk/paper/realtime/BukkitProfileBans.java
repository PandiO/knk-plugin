package net.knightsandkings.knk.paper.realtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.bukkit.BanList;
import org.bukkit.Bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;

/**
 * {@link ModerationRealtimeSync.Bans} on Paper's profile ban list ({@code banned-players.json}), the list in-game
 * {@code /ban} and {@code /pardon} use (PandiO/knk-workspace#574). Main thread.
 */
public final class BukkitProfileBans implements ModerationRealtimeSync.Bans {

    private final Supplier<? extends BanList<PlayerProfile>> list;
    private final BiFunction<UUID, String, PlayerProfile> profiles;

    /** The server's own profile ban list. */
    public BukkitProfileBans() {
        this(() -> Bukkit.getBanList(BanListType.PROFILE), Bukkit::createProfile);
    }

    BukkitProfileBans(Supplier<? extends BanList<PlayerProfile>> list, BiFunction<UUID, String, PlayerProfile> profiles) {
        this.list = Objects.requireNonNull(list, "list");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public boolean isBanned(UUID uuid, String username) {
        return list.get().isBanned(profiles.apply(uuid, username));
    }

    /** Permanent: no expiry. */
    @Override
    public void ban(UUID uuid, String username, String reason, String source) {
        list.get().addBan(profiles.apply(uuid, username), reason, (Instant) null, source);
    }
}
