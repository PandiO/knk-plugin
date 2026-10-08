package net.knightsandkings.knk.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Name lookup and tab completion over a list of named, typed things (road navigation plan §2 R21):
 * the generalisation of teleport's {@code WarpTargets}, which stays as a thin wrapper bound to
 * {@code KnkTeleportDestination}. Case-insensitive, over whatever list the caller passes. A name
 * several items share (a Town and a District both called "Market") is ambiguous and the caller
 * picks with the {@code type:name} form ({@code town:Market}); with an id accessor the
 * {@code type:#id} / {@code #id} forms pick by id. An item may answer to more type words than its own
 * (aliases: a subtype's own word, a nickname), and {@code type:} + Tab lists every item of that type.
 * Pure, so it is unit-tested without a server.
 *
 * @param <T> the item type; {@code name} and {@code type} read its display name and its type word
 */
public final class NamedTargets<T> {

    /** What {@link #resolve} found: one item, several choices, or nothing. */
    public record Match<T>(T target, List<T> choices) {
        public Match {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        public static <T> Match<T> none() {
            return new Match<>(null, List.of());
        }

        public boolean found() {
            return target != null;
        }

        public boolean ambiguous() {
            return target == null && choices.size() > 1;
        }
    }

    private final Function<T, String> name;
    private final Function<T, String> type;
    private final ToIntFunction<T> id;
    private final Function<T, ? extends Collection<String>> aliases;

    /** Without an id accessor: the {@code #id} forms never match. */
    public NamedTargets(Function<T, String> name, Function<T, String> type) {
        this(name, type, null);
    }

    /**
     * @param name the item's display name (never null for a listed item)
     * @param type the item's type word ("Town", "location", …), compared ignoring case
     * @param id   the item's numeric id for the {@code type:#id} form, or null when there is none
     */
    public NamedTargets(Function<T, String> name, Function<T, String> type, ToIntFunction<T> id) {
        this(name, type, id, null);
    }

    /**
     * @param aliases more type words that pick the item too (a gate is {@code structure}, and also
     *                {@code gatestructure} and {@code gate}), compared ignoring case; null for none
     */
    public NamedTargets(Function<T, String> name, Function<T, String> type, ToIntFunction<T> id,
                        Function<T, ? extends Collection<String>> aliases) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.id = id;
        this.aliases = aliases;
    }

    /**
     * Find {@code input} ("Kardenna", "town:Kardenna", "town:#12") in {@code items}: exactly one match →
     * found; several → ambiguous with the choices; none → {@link Match#none()}.
     */
    public Match<T> resolve(List<T> items, String input) {
        if (input == null || input.isBlank() || items == null || items.isEmpty()) {
            return Match.none();
        }
        String wanted = normalize(input);
        String wantedType = null;
        int colon = wanted.indexOf(':');
        if (colon > 0 && colon < wanted.length() - 1) {
            wantedType = wanted.substring(0, colon);
            wanted = wanted.substring(colon + 1);
        }
        List<T> matches = new ArrayList<>();
        Integer wantedId = parseId(wanted);
        for (T item : items) {
            boolean typeFits = wantedType == null || hasType(item, wantedType);
            if (!typeFits) {
                continue;
            }
            if (wantedId != null) {
                if (id != null && id.applyAsInt(item) == wantedId) {
                    matches.add(item);
                }
            } else if (nameOf(item).equalsIgnoreCase(wanted)) {
                matches.add(item);
            }
        }
        if (matches.isEmpty() && wantedType != null) {
            // "Spawn:Hill" could be a place literally named that.
            String literal = normalize(input);
            for (T item : items) {
                if (nameOf(item).equalsIgnoreCase(literal)) {
                    matches.add(item);
                }
            }
        }
        if (matches.size() == 1) {
            return new Match<>(matches.get(0), List.of());
        }
        return matches.isEmpty() ? Match.none() : new Match<>(null, matches);
    }

    /**
     * Items whose name starts with {@code input} (ignoring case and spacing), for "did you mean" when
     * {@link #resolve} finds nothing. Never picks one itself.
     */
    public List<T> suggestions(List<T> items, String input) {
        String wanted = normalize(input).toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || items == null) {
            return List.of();
        }
        int colon = wanted.indexOf(':');
        String wantedType = colon > 0 ? wanted.substring(0, colon) : null;
        String bare = colon > 0 ? wanted.substring(colon + 1) : wanted;
        List<T> out = new ArrayList<>();
        for (T item : items) {
            String itemName = nameOf(item).toLowerCase(Locale.ROOT);
            boolean typeFits = wantedType == null || hasType(item, wantedType);
            if ((typeFits && itemName.startsWith(bare)) || itemName.startsWith(wanted)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Tab completions for a single word - same as {@link #complete(List, List)} with just {@code prefix}. */
    public List<String> complete(List<T> items, String prefix) {
        return complete(items, List.of(prefix == null ? "" : prefix));
    }

    /**
     * Tab completions for the last of {@code words} (the command's arguments so far). An item's name
     * - or its {@code type:name} form when several items share the name - is offered one word at a
     * time, since the client completes one argument: after "Residential" the next word "District" is
     * offered. Once the first word starts with a type word and a colon ({@code structure:}), every item of
     * that type (or alias) is offered in that form: {@code structure:} + Tab → {@code structure:Keep}, then
     * {@code Gate}. Matching ignores case.
     */
    public List<String> complete(List<T> items, List<String> words) {
        if (items == null || words == null || words.isEmpty()) {
            return List.of();
        }
        int index = words.size() - 1;
        String current = words.get(index).toLowerCase(Locale.ROOT);
        String typed = typedType(words.get(0));
        Set<String> seen = new LinkedHashSet<>();
        Set<String> shared = new LinkedHashSet<>();
        for (T item : items) {
            String key = nameOf(item).toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                shared.add(key);
            }
        }
        List<String> out = new ArrayList<>();
        for (T item : items) {
            String itemName = nameOf(item);
            boolean isShared = shared.contains(itemName.toLowerCase(Locale.ROOT));
            String candidate = typed != null && hasType(item, typed) ? typed + ":" + itemName
                : isShared ? qualifiedName(item) : itemName;
            String[] parts = candidate.split(" ");
            if (parts.length <= index || !samePrefix(parts, words, index)) {
                continue;
            }
            String part = parts[index];
            boolean matches = part.toLowerCase(Locale.ROOT).startsWith(current)
                // "mar" also offers "town:Market" on the first word.
                || (index == 0 && isShared && itemName.toLowerCase(Locale.ROOT).startsWith(current));
            if (matches && !out.contains(part)) {
                out.add(part);
            }
        }
        return out;
    }

    /** The {@code type:name} form ({@code town:Kardenna}) that picks one of several same-named items. */
    public String qualifiedName(T item) {
        return typeOf(item).toLowerCase(Locale.ROOT) + ":" + nameOf(item);
    }

    /** Whether {@code word} is the item's type word or one of its aliases, ignoring case. */
    public boolean hasType(T item, String word) {
        if (typeOf(item).equalsIgnoreCase(word)) {
            return true;
        }
        Collection<String> more = aliases == null ? null : aliases.apply(item);
        if (more != null) {
            for (String alias : more) {
                if (alias != null && alias.equalsIgnoreCase(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "structure:Ke" → "structure"; null when the word has no type word before a colon. */
    private static String typedType(String firstWord) {
        String word = firstWord == null ? "" : firstWord.trim().toLowerCase(Locale.ROOT);
        int colon = word.indexOf(':');
        return colon > 0 ? word.substring(0, colon) : null;
    }

    private String nameOf(T item) {
        return normalize(name.apply(item));
    }

    private String typeOf(T item) {
        String t = type.apply(item);
        return t == null ? "" : t;
    }

    /** "#12" → 12; anything else → null. */
    private static Integer parseId(String word) {
        if (word == null || word.length() < 2 || word.charAt(0) != '#') {
            return null;
        }
        try {
            return Integer.parseInt(word.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the first {@code count} words of the name are the words already typed. */
    private static boolean samePrefix(String[] parts, List<String> words, int count) {
        for (int i = 0; i < count; i++) {
            if (!parts[i].equalsIgnoreCase(words.get(i).trim())) {
                return false;
            }
        }
        return true;
    }

    /** Trimmed, with runs of spaces as one: "Residential  District " matches "Residential District". */
    public static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }
}
