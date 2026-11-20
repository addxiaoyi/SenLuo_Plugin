package net.kaleidoscope.cookery.catalog;

import java.util.regex.Pattern;

/** Enforces JSON lexical rules before the older Gson reader checks structure. */
final class StrictJsonTokens {
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private StrictJsonTokens() {}

    static void validate(String source) {
        int index = 0;
        while (index < source.length()) {
            char character = source.charAt(index++);
            if (separator(character)) continue;
            if (character == '"') {
                boolean closed = false;
                while (index < source.length()) {
                    character = source.charAt(index++);
                    if (character == '"') { closed = true; break; }
                    if (character < 0x20) throw invalid(index);
                    if (character != '\\') continue;
                    if (index == source.length()) throw invalid(index);
                    char escape = source.charAt(index++);
                    if ("\"\\/bfnrt".indexOf(escape) >= 0) continue;
                    if (escape != 'u' || source.length() - index < 4) throw invalid(index);
                    for (int end = index + 4; index < end; index++) {
                        char hex = source.charAt(index);
                        if (!(hex >= '0' && hex <= '9' || hex >= 'a' && hex <= 'f' || hex >= 'A' && hex <= 'F')) {
                            throw invalid(index);
                        }
                    }
                }
                if (!closed) throw invalid(index);
                continue;
            }
            int start = index - 1;
            while (index < source.length() && !separator(source.charAt(index)) && source.charAt(index) != '"') index++;
            String token = source.substring(start, index);
            if (!token.equals("true") && !token.equals("false") && !token.equals("null") && !NUMBER.matcher(token).matches()) {
                throw invalid(start);
            }
        }
    }

    private static boolean separator(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n'
                || "{}[]:,".indexOf(character) >= 0;
    }

    private static IllegalArgumentException invalid(int index) {
        return new IllegalArgumentException("Invalid JSON token at character " + index);
    }
}
