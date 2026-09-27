package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.siege.SiegeCommandFilter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The alias resolver for {@link SiegeCommandFilter} (private-messages DESIGN §4 D9): a command label
 * becomes the primary name of the command the server would run for it, so {@code /tell}, {@code /w},
 * {@code /whisper}, {@code /pm} … count as {@code /msg} and {@code /reply} as {@code /r}. The filter
 * hands over labels without their namespace, so {@code /minecraft:tell} and {@code /knightsandkings:w}
 * resolve like the plain label - the command players actually get (VanillaMessagingBlockListener
 * rewrites the vanilla forms to {@code /msg}). Unknown labels come back unchanged. Main thread.
 */
public final class SiegeCommandAliases {
    private SiegeCommandAliases() {}

    /** @param commandMap looked up on every call (the server's map; null = nothing resolves) */
    public static UnaryOperator<String> resolver(Supplier<CommandMap> commandMap) {
        Objects.requireNonNull(commandMap, "commandMap");
        return label -> {
            CommandMap map = commandMap.get();
            Command command = map == null || label == null || label.isEmpty() ? null : map.getCommand(label);
            if (command == null) return label;
            String name = SiegeCommandFilter.label(command.getName());
            return name.isEmpty() ? label : name;
        };
    }
}
