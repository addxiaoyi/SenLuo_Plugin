package net.kaleidoscope.tavern;

import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

/** Immutable text/pattern snapshot; a delayed editor may only replace its original snapshot. */
record SandwichBoardState(String style, String text, String color, Alignment alignment, boolean waxed, boolean glowing) {
    static final int MAX_TEXT = 320;
    static final Set<String> STYLES = Set.of("base", "grass", "allium", "azure_bluet", "cornflower", "orchid",
            "peony", "pink_petals", "pitcher_plant", "poppy", "sunflower", "torchflower", "tulip", "wither_rose");
    // DyeColor#getTextColor in the fixed upstream, rather than the wool/diffuse colour.
    static final Map<String, Integer> COLORS = Map.ofEntries(Map.entry("white", 0xffffff), Map.entry("orange", 0xff681f),
            Map.entry("magenta", 0xff00ff), Map.entry("light_blue", 0x9ac0cd), Map.entry("yellow", 0xffff00),
            Map.entry("lime", 0xbfff00), Map.entry("pink", 0xff69b4), Map.entry("gray", 0x808080),
            Map.entry("light_gray", 0xd3d3d3), Map.entry("cyan", 0x00ffff), Map.entry("purple", 0xa020f0),
            Map.entry("blue", 0x0000ff), Map.entry("brown", 0x8b4513), Map.entry("green", 0x00ff00),
            Map.entry("red", 0xff0000), Map.entry("black", 0));

    enum Alignment { LEFT, CENTER, RIGHT }

    SandwichBoardState {
        if (!STYLES.contains(style) || !COLORS.containsKey(color) || alignment == null) throw new IllegalArgumentException("Invalid board state");
        validateText(text);
    }

    static void validateText(String text) {
        if (text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("Board text exceeds 320 characters");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((Character.isISOControl(c) && c != '\n') || c == '\u007f') throw new IllegalArgumentException("Invalid board control character");
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Invalid board Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid board Unicode");
        }
    }

    static String styleOf(String id) {
        String prefix = "kaleidoscope_tavern:", suffix = "_sandwich_board";
        if (id == null || !id.startsWith(prefix) || !id.endsWith(suffix)) throw new IllegalArgumentException("Not a sandwich board");
        String style = id.substring(prefix.length(), id.length() - suffix.length());
        if (!STYLES.contains(style)) throw new IllegalArgumentException("Unknown sandwich board");
        return style;
    }

    static SandwichBoardState blank(String style) { return new SandwichBoardState(style, "", "white", Alignment.CENTER, false, false); }
    String itemId() { return "kaleidoscope_tavern:" + style + "_sandwich_board"; }
    SandwichBoardState pattern(String value) { return new SandwichBoardState(value, text, color, alignment, waxed, glowing); }
    SandwichBoardState dye(String value) { return new SandwichBoardState(style, text, value, alignment, waxed, glowing); }
    SandwichBoardState glow(boolean value) { return new SandwichBoardState(style, text, color, alignment, waxed, value); }
    SandwichBoardState wax() { return new SandwichBoardState(style, text, color, alignment, true, glowing); }
    SandwichBoardState edit(String value, Alignment align) { return new SandwichBoardState(style, value, color, align, waxed, glowing); }

    static String pattern(Material material) {
        return switch (material) {
            case SHORT_GRASS -> "grass";
            case ALLIUM -> "allium";
            case AZURE_BLUET, OXEYE_DAISY, LILY_OF_THE_VALLEY -> "azure_bluet";
            case CORNFLOWER -> "cornflower";
            case BLUE_ORCHID -> "orchid";
            case PEONY, LILAC -> "peony";
            case PINK_PETALS -> "pink_petals";
            case PITCHER_PLANT -> "pitcher_plant";
            case POPPY, ROSE_BUSH -> "poppy";
            case SUNFLOWER, DANDELION -> "sunflower";
            case TORCHFLOWER -> "torchflower";
            case RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP -> "tulip";
            case WITHER_ROSE -> "wither_rose";
            default -> null;
        };
    }

    int textRgb() {
        int rgb = COLORS.get(color);
        if (glowing) return rgb;
        return ((int) (((rgb >> 16) & 255) * 0.6) << 16)
                | ((int) (((rgb >> 8) & 255) * 0.6) << 8) | (int) ((rgb & 255) * 0.6);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putString("style", style);
        tag.putString("text", text);
        tag.putString("color", color);
        tag.putString("alignment", alignment.name());
        tag.putBoolean("waxed", waxed);
        tag.putBoolean("glowing", glowing);
        return tag;
    }

    static SandwichBoardState read(CompoundTag tag) {
        if (tag == null || SavedState.integer(tag, "version") != 1) throw new IllegalArgumentException("Unsupported board save");
        return new SandwichBoardState(SavedState.text(tag, "style"), SavedState.text(tag, "text"), SavedState.text(tag, "color"),
                Alignment.valueOf(SavedState.text(tag, "alignment")), SavedState.flag(tag, "waxed"), SavedState.flag(tag, "glowing"));
    }
}
