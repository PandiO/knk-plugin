package net.knightsandkings.knk.core.siege;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Cleans authored names before they reach chat, scoreboards or displays. The data is left as it
 * is; only the displayed copy is repaired:
 * <ul>
 *   <li>control characters (e.g. a trailing {@code \n} in a district name) become spaces, runs of
 *       whitespace collapse, and the result is trimmed;</li>
 *   <li>UTF-8 text that was decoded as Windows-1252 once (mojibake such as {@code â€”} for an em
 *       dash, seen on the dev lobby's name) is decoded back, but only when that round trip is
 *       lossless, so correct text is never touched.</li>
 * </ul>
 */
public final class SiegeDisplayText {
    private SiegeDisplayText() {}

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** @return the cleaned text, or {@code fallback} when the text is null or blank */
    public static String clean(String text, String fallback) {
        if (text == null) return fallback;
        String repaired = repairMojibake(text);
        StringBuilder out = new StringBuilder(repaired.length());
        boolean pendingSpace = false;
        for (int i = 0; i < repaired.length(); i++) {
            char c = repaired.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) out.append(' ');
            pendingSpace = false;
            out.append(c);
        }
        return out.isEmpty() ? fallback : out.toString();
    }

    public static String clean(String text) {
        return clean(text, "");
    }

    static String repairMojibake(String text) {
        if (text.indexOf('Ã') < 0 && text.indexOf('â') < 0 && text.indexOf('Â') < 0) return text;
        try {
            ByteBuffer bytes = WINDOWS_1252.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException e) {
            return text;
        }
    }
}
