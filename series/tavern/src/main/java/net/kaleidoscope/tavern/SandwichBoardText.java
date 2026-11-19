package net.kaleidoscope.tavern;

import java.util.ArrayList;
import java.util.List;

/** Bounded server layout for the default font; custom/uniform client fonts require visual acceptance. */
final class SandwichBoardText {
    static final int MAX_WIDTH = 55, MAX_LINES = 8;
    // Default 1.21.11 ascii.png advances (space comes from include/space), before bold's +1.
    private static final int[] ASCII = {4,2,4,6,6,6,6,2,4,4,4,6,2,6,2,6,6,6,6,6,6,6,6,6,6,6,2,2,5,6,5,6,7,6,6,6,6,6,6,6,6,4,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,4,6,4,6,6,3,6,6,6,6,6,5,6,6,2,6,5,3,6,6,6,6,6,6,6,4,6,6,6,6,6,6,4,2,4,7};
    private SandwichBoardText() { }

    static int advance(int codePoint) {
        // The CJK/unifont fallback is conservative; stored text is never truncated to this layout.
        return codePoint >= 32 && codePoint < 127 ? ASCII[codePoint - 32] + 1 : 10;
    }

    static int width(String text) { return text.codePoints().map(SandwichBoardText::advance).sum(); }

    static List<String> lines(String text) {
        SandwichBoardState.validateText(text);
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length() && result.size() < MAX_LINES) {
            int at = start, width = 0, lastSpace = -1;
            while (at < text.length()) {
                int cp = text.codePointAt(at);
                if (cp == '\n') break;
                if (width + advance(cp) > MAX_WIDTH) break;
                width += advance(cp);
                if (cp == ' ') lastSpace = at;
                at += Character.charCount(cp);
            }
            if (at == text.length()) { result.add(text.substring(start)); break; }
            if (text.charAt(at) == '\n') {
                result.add(text.substring(start, at));
                start = at + 1;
            } else if (lastSpace >= start) {
                result.add(text.substring(start, lastSpace));
                start = lastSpace + 1;
            } else {
                result.add(text.substring(start, at));
                start = at;
            }
        }
        return List.copyOf(result);
    }
}
