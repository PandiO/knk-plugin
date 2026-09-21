package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.Comparator;
import java.util.List;

/**
 * Phase 2's stand-in for real variable resolution (explicitly out of scope
 * this phase - see {@code VariableResolver}, Phase 3): renders a
 * {@code VariableBinding}'s raw {@code Expression} as literal text instead of
 * evaluating its getter-chain. Bukkit-free on purpose so it's usable
 * identically from tests and from knk-paper's rendering code; Phase 3 swaps
 * the body of this class for real resolution without call sites needing to
 * change.
 */
public final class MenuVariablePlaceholderText {

    private MenuVariablePlaceholderText() {
    }

    /** The literal text of the single "Name"-targeted binding, or null if none is set. */
    public static String resolveName(List<KnkVariableBinding> bindings) {
        return bindingsFor(bindings, "Name").findFirst().orElse(null);
    }

    /** The literal text of each "Lore"-targeted binding, in sortOrder order - one line per binding. */
    public static List<String> resolveLore(List<KnkVariableBinding> bindings) {
        return bindingsFor(bindings, "Lore").toList();
    }

    private static java.util.stream.Stream<String> bindingsFor(List<KnkVariableBinding> bindings, String targetProperty) {
        if (bindings == null) {
            return java.util.stream.Stream.empty();
        }
        return bindings.stream()
                .filter(binding -> targetProperty.equalsIgnoreCase(binding.targetProperty()))
                .sorted(Comparator.comparingInt(binding -> binding.sortOrder() != null ? binding.sortOrder() : 0))
                .map(KnkVariableBinding::expression);
    }
}
