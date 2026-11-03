package net.knightsandkings.knk.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The one line-wrapping step for item and menu lore (PandiO/knk-workspace#212). A lore line that is wider than the
 * maximum width continues on the next line, so no tooltip runs off the screen.
 * <p>
 * Choices (recorded in {@code docs/architecture/item-render-pipeline.md} § 2a):
 * <ul>
 *   <li><b>Width</b> is counted in visible characters; legacy colour and format codes ({@code §x} / {@code &x},
 *       hex {@code §x§r§r§g§g§b§b}) don't count. Minecraft's font is proportional, so this is an approximation that
 *       keeps ordinary text well inside the tooltip; pixel widths would need the client's font table.</li>
 *   <li><b>Maximum width</b> is {@value #DEFAULT_MAX_WIDTH} by default, configurable with {@code lore.max-width}
 *       in the plugin's {@code config.yml} ({@link #setMaxWidth}).</li>
 *   <li><b>Formatting is kept:</b> a continuation line starts with the colour and formats that were active where
 *       the line broke, so a gray line stays gray and a bold line stays bold.</li>
 *   <li><b>Words</b> break only at spaces. A single word longer than the width (a long name, a URL) is hard-broken
 *       into pieces of exactly the width; a word that fits is never split.</li>
 *   <li>A line that already fits is returned unchanged (same string), so wrapping twice changes nothing and blank
 *       spacer lines stay where they are.</li>
 * </ul>
 * The item scan (PandiO/knk-workspace#40) never captures the wrap: a blueprint item whose description was wrapped
 * carries the authored lines in a tag ({@code ItemDescriptionTag} in knk-paper); {@link #unwrap}, the inverse of
 * {@link #wrap}, is the fallback when those lines no longer match the lore.
 */
public final class LoreWrap {

    /** Default and fallback maximum visible width of a lore line. */
    public static final int DEFAULT_MAX_WIDTH = 40;
    /** Narrowest width {@link #setMaxWidth} accepts; anything below is raised to this. */
    public static final int MIN_WIDTH = 10;

    private static volatile int maxWidth = DEFAULT_MAX_WIDTH;

    private LoreWrap() {
    }

    /** The configured maximum width ({@code lore.max-width}, default {@value #DEFAULT_MAX_WIDTH}). */
    public static int maxWidth() {
        return maxWidth;
    }

    /** Sets the maximum width used by the overloads without a width (plugin start-up); raised to {@value #MIN_WIDTH}. */
    public static void setMaxWidth(int width) {
        maxWidth = Math.max(MIN_WIDTH, width);
    }

    // ------------------------------------------------------------------ wrapping

    /** {@link #wrap(String, int)} at the configured width. */
    public static List<String> wrap(String line) {
        return wrap(line, maxWidth);
    }

    /**
     * The line as one or more lore lines of at most {@code width} visible characters. Null gives no lines; a blank
     * line or one that already fits comes back unchanged.
     */
    public static List<String> wrap(String line, int width) {
        if (line == null) {
            return List.of();
        }
        int limit = Math.max(1, width);
        List<Token> tokens = tokenize(line);
        if (visibleLength(tokens) <= limit) {
            return List.of(line);
        }

        List<String> lines = new ArrayList<>();
        Style style = new Style();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        boolean currentHasText = false;

        for (List<Token> word : words(tokens)) {
            int wordWidth = visibleLength(word);
            if (wordWidth == 0) {
                // Codes only (e.g. "&c" standing between two spaces): no space of its own.
                for (Token token : word) {
                    current.append(token.text);
                    style.apply(token);
                }
                continue;
            }
            if (currentHasText && currentWidth + 1 + wordWidth <= limit) {
                current.append(' ');
                currentWidth++;
            } else if (currentHasText) {
                lines.add(current.toString());
                current = new StringBuilder(style.prefix());
                currentWidth = 0;
                currentHasText = false;
            }
            for (Token token : word) {
                if (token.code) {
                    current.append(token.text);
                    style.apply(token);
                    continue;
                }
                if (currentWidth == limit) {
                    // Only reached inside a word longer than the whole width: hard break.
                    lines.add(current.toString());
                    current = new StringBuilder(style.prefix());
                    currentWidth = 0;
                }
                current.append(token.text);
                currentWidth++;
                currentHasText = true;
            }
        }
        if (currentHasText || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    /** {@link #wrapAll(List, int)} at the configured width. */
    public static List<String> wrapAll(List<String> lines) {
        return wrapAll(lines, maxWidth);
    }

    /** Every line through {@link #wrap(String, int)}, in order; null lines are dropped. */
    public static List<String> wrapAll(List<String> lines, int width) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.addAll(wrap(line, width));
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ unwrapping

    /** {@link #unwrap(List, int)} at the configured width. */
    public static List<String> unwrap(List<String> lines) {
        return unwrap(lines, maxWidth);
    }

    /**
     * Joins the continuation lines {@link #wrap} made back onto the line they continue. Two neighbouring lines A and B
     * are joined when B could only have come from wrapping: A fits the width, B starts in the formatting A ended in,
     * and either B's first word would not have fit after A (joined with a space), or A is a hard-broken piece (exactly
     * the width, no space; joined without one). Codes at the start of B that change nothing after A are dropped.
     * <p>
     * Two lines an author wrote that happen to meet these rules are joined too; wrapping the joined line again at
     * the same width gives exactly the two original lines back, so the item looks the same.
     */
    public static List<String> unwrap(List<String> lines, int width) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, width);
        List<String> result = new ArrayList<>(lines.size());
        String previousPhysical = null;
        for (String line : lines) {
            if (line == null) {
                previousPhysical = null;
                continue;
            }
            if (previousPhysical != null) {
                String joined = join(result.get(result.size() - 1), previousPhysical, line, limit);
                if (joined != null) {
                    result.set(result.size() - 1, joined);
                    previousPhysical = line;
                    continue;
                }
            }
            result.add(line);
            previousPhysical = line;
        }
        return List.copyOf(result);
    }

    /** {@code accumulated} + {@code next} when {@code next} continues {@code physical} (the last line joined in), else null. */
    private static String join(String accumulated, String physical, String next, int limit) {
        List<Token> previousTokens = tokenize(physical);
        int previousWidth = visibleLength(previousTokens);
        if (previousWidth == 0 || previousWidth > limit) {
            return null;
        }
        List<Token> nextTokens = tokenize(next);
        int lead = 0;
        while (lead < nextTokens.size() && nextTokens.get(lead).code) {
            lead++;
        }
        if (lead == nextTokens.size() || nextTokens.get(lead).space) {
            return null;
        }
        List<Token> leadingCodes = nextTokens.subList(0, lead);

        Style endOfPrevious = Style.after(tokenize(accumulated));
        Style continued = endOfPrevious.copy().applyAll(leadingCodes);
        Style onItsOwn = new Style().applyAll(leadingCodes);
        if (!continued.equals(onItsOwn)) {
            return null;
        }

        boolean hardBreak = previousWidth == limit && previousTokens.stream().noneMatch(token -> token.space);
        boolean softBreak = previousWidth + 1 + firstWordWidth(nextTokens, lead) > limit;
        if (!hardBreak && !softBreak) {
            return null;
        }
        String rest = continued.equals(endOfPrevious) ? textOf(nextTokens.subList(lead, nextTokens.size())) : next;
        return accumulated + (hardBreak ? "" : " ") + rest;
    }

    private static int firstWordWidth(List<Token> tokens, int from) {
        int width = 0;
        for (int i = from; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.space) {
                break;
            }
            if (!token.code) {
                width++;
            }
        }
        return width;
    }

    // ------------------------------------------------------------------ measuring and helpers

    /** Visible characters in the line (code points), colour and format codes not counted; null counts 0. */
    public static int visibleLength(String line) {
        return line == null ? 0 : visibleLength(tokenize(line));
    }

    /** The visible text only, colour and format codes removed; null stays null. */
    public static String stripCodes(String line) {
        if (line == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(line.length());
        for (Token token : tokenize(line)) {
            if (!token.code) {
                out.append(token.text);
            }
        }
        return out.toString();
    }

    /**
     * The text cut to at most {@code width} visible characters, the last of them "…" when it was cut. Codes are kept;
     * null stays null.
     */
    public static String truncate(String text, int width) {
        if (text == null) {
            return null;
        }
        List<Token> tokens = tokenize(text);
        int limit = Math.max(1, width);
        if (visibleLength(tokens) <= limit) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Token token : tokens) {
            if (!token.code) {
                if (shown == limit - 1) {
                    break;
                }
                shown++;
            }
            out.append(token.text);
        }
        return out.append('…').toString();
    }

    /**
     * Greedy packing of whole items (never split) onto lines of at most {@code width} visible characters, for a list
     * shown as "a, b, c" over several lore lines (the kits menu's "Other contents", PandiO/knk-workspace#172).
     *
     * @param items          the items, each already short enough ({@link #truncate})
     * @param separatorWidth visible width between two items on one line (", " = 2)
     * @param lineEndWidth   visible width kept free at the end of every line (a trailing "," = 1)
     * @param firstLineUsed  visible width already taken on the first line (a label)
     * @return the items per line; empty for no items
     */
    public static List<List<String>> packItems(List<String> items, int separatorWidth, int lineEndWidth, int width,
                                               int firstLineUsed) {
        List<List<String>> lines = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return lines;
        }
        List<String> current = new ArrayList<>();
        int used = firstLineUsed;
        for (String item : items) {
            int itemWidth = visibleLength(item);
            int needed = current.isEmpty() ? itemWidth : separatorWidth + itemWidth;
            if (!current.isEmpty() && used + needed + lineEndWidth > width) {
                lines.add(current);
                current = new ArrayList<>();
                used = 0;
                needed = itemWidth;
            }
            current.add(item);
            used += needed;
        }
        lines.add(current);
        return lines;
    }

    // ------------------------------------------------------------------ tokens and style

    private record Token(String text, boolean code, char op, boolean space) {
        static Token code(String text, char op) {
            return new Token(text, true, op, false);
        }

        static Token visible(String text) {
            return new Token(text, false, '\0', text.codePointCount(0, text.length()) == 1
                    && Character.isWhitespace(text.codePointAt(0)));
        }
    }

    /** Splits a line into codes and visible characters ({@code &} only counts as a code before a valid code char). */
    private static List<Token> tokenize(String line) {
        List<Token> tokens = new ArrayList<>(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < line.length()) {
                char op = Character.toLowerCase(line.charAt(i + 1));
                if (op == 'x' && isHexRun(line, i + 2)) {
                    tokens.add(Token.code(line.substring(i, i + 14), 'x'));
                    i += 14;
                    continue;
                }
                if (isCodeChar(op) && op != 'x') {
                    tokens.add(Token.code(line.substring(i, i + 2), op));
                    i += 2;
                    continue;
                }
                if (c == '§') {
                    // The client hides "§" plus any character, even an unknown one; it changes nothing.
                    tokens.add(Token.code(line.substring(i, i + 2), '\0'));
                    i += 2;
                    continue;
                }
            }
            int end = i + Character.charCount(line.codePointAt(i));
            tokens.add(Token.visible(line.substring(i, end)));
            i = end;
        }
        return tokens;
    }

    private static boolean isHexRun(String line, int from) {
        if (from + 12 > line.length()) {
            return false;
        }
        for (int pair = 0; pair < 6; pair++) {
            char marker = line.charAt(from + pair * 2);
            char digit = Character.toLowerCase(line.charAt(from + pair * 2 + 1));
            if ((marker != '§' && marker != '&') || Character.digit(digit, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCodeChar(char op) {
        return (op >= '0' && op <= '9') || (op >= 'a' && op <= 'f') || (op >= 'k' && op <= 'o') || op == 'r' || op == 'x';
    }

    private static int visibleLength(List<Token> tokens) {
        int width = 0;
        for (Token token : tokens) {
            if (!token.code) {
                width++;
            }
        }
        return width;
    }

    /** Words (codes and visible characters) between runs of whitespace; codes before a word belong to it. */
    private static List<List<Token>> words(List<Token> tokens) {
        List<List<Token>> words = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token token : tokens) {
            if (token.space) {
                if (!current.isEmpty()) {
                    words.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            words.add(current);
        }
        return words;
    }

    private static String textOf(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            out.append(token.text);
        }
        return out.toString();
    }

    /** Active colour + formats at a point of a line, as legacy code text (to start a continuation line with). */
    private static final class Style {
        private String color;
        private final Map<Character, String> formats = new LinkedHashMap<>();

        static Style after(List<Token> tokens) {
            return new Style().applyAll(tokens);
        }

        Style applyAll(List<Token> tokens) {
            for (Token token : tokens) {
                if (token.code) {
                    apply(token);
                }
            }
            return this;
        }

        void apply(Token token) {
            char op = token.op;
            if ((op >= '0' && op <= '9') || (op >= 'a' && op <= 'f') || op == 'x') {
                color = token.text;
                formats.clear();
            } else if (op == 'r') {
                color = null;
                formats.clear();
            } else if (op >= 'k' && op <= 'o') {
                formats.put(op, token.text);
            }
        }

        String prefix() {
            StringBuilder out = new StringBuilder(color == null ? "" : color);
            formats.values().forEach(out::append);
            return out.toString();
        }

        Style copy() {
            Style copy = new Style();
            copy.color = color;
            copy.formats.putAll(formats);
            return copy;
        }

        private String normalizedColor() {
            return color == null ? null : color.replace('&', '§').toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Style other && Objects.equals(normalizedColor(), other.normalizedColor())
                    && formats.keySet().equals(other.formats.keySet());
        }

        @Override
        public int hashCode() {
            return Objects.hash(normalizedColor(), formats.keySet());
        }
    }
}
