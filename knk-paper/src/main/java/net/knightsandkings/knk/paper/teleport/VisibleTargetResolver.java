package net.knightsandkings.knk.paper.teleport;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Vanish-aware online-player lookup for the teleport commands (docs/specs/teleport/DESIGN.md
 * §3.4.4): a player vanished in owner/staff mode ({@code ModeService.isVanished}) is treated as
 * offline - same "no online player" message, left out of tab completion - for a viewer who can't
 * see them. Whether the viewer can is read from Bukkit's own {@code canSee}, which
 * {@code ModeService} keeps in sync with {@code knk.mode.staff}/{@code knk.mode.owner}. The console
 * and the player themselves always see them.
 * <p>
 * Deliberately local to the teleport package for now: a shared vanish-safe lookup is being built
 * for private messages on another branch; the two should be merged into one helper later.
 */
public final class VisibleTargetResolver {

    private final Function<String, Player> byExactName;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Predicate<Player> isVanished;

    public VisibleTargetResolver(Function<String, Player> byExactName,
                                 Supplier<Collection<? extends Player>> onlinePlayers,
                                 Predicate<Player> isVanished) {
        this.byExactName = Objects.requireNonNull(byExactName, "byExactName must not be null");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers must not be null");
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
    }

    /** The online player with this name as {@code viewer} may see them, or null. */
    public Player find(CommandSender viewer, String name) {
        Player target = byExactName.apply(name);
        return target != null && canSee(viewer, target) ? target : null;
    }

    public boolean canSee(CommandSender viewer, Player target) {
        if (!(viewer instanceof Player player)) {
            return true;
        }
        if (player.getUniqueId().equals(target.getUniqueId()) || !isVanished.test(target)) {
            return true;
        }
        return player.canSee(target);
    }

    /** Names of players {@code viewer} can see starting with {@code prefix}, plus {@code extra}. */
    public List<String> complete(CommandSender viewer, String prefix, String... extra) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        Stream<String> names = onlinePlayers.get().stream()
            .filter(player -> canSee(viewer, player))
            .map(Player::getName);
        return Stream.concat(Arrays.stream(extra), names)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }
}
