package net.knightsandkings.knk.core.menu;

import java.util.regex.Pattern;

/**
 * The single regex for {@code $x.y$}-style variable placeholders, shared by
 * {@link VariableResolver} (live resolution) and {@link MenuDefinitionValidator}
 * (load-time chain validation) so there is exactly one place reconciliation
 * bug #7's fix lives, not two copies that could quietly drift apart. Bug #7
 * was v2's broken {@code $(.*?)$} pattern letting every placeholder through
 * unsubstituted for years without anyone noticing; this pattern is properly
 * bounded ({@code \w} plus {@code .} only, both delimiters required).
 */
final class MenuVariablePlaceholders {

    static final Pattern PATTERN = Pattern.compile("\\$([\\w.]+)\\$");

    private MenuVariablePlaceholders() {
    }
}
