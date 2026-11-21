package net.kaleidoscope.tavern;

import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SandwichBoardStateTest {
    @Test void allFourteenStylesRoundTripThroughRealNbtBytes() throws Exception {
        assertEquals(14, SandwichBoardState.STYLES.size());
        for (String style : SandwichBoardState.STYLES) {
            var state = SandwichBoardState.blank(style).edit("菜单\n<red>literal</red>", SandwichBoardState.Alignment.RIGHT).dye("purple").glow(true).wax();
            var decoded = FurniturePersistentData.fromBytes(FurniturePersistentData.of(state.save()).toBytes()).copyTag();
            assertEquals(state, SandwichBoardState.read(decoded));
            assertEquals(style, SandwichBoardState.styleOf(state.itemId()));
        }
    }
    @Test void everyDyeAndAlignmentIsValidAndStrictlySaved() {
        assertEquals(16, SandwichBoardState.COLORS.size());
        for (String color : SandwichBoardState.COLORS.keySet()) for (var alignment : SandwichBoardState.Alignment.values()) {
            var state = SandwichBoardState.blank("base").dye(color).edit("A", alignment);
            assertEquals(state, SandwichBoardState.read(state.save()));
        }
    }
    @Test void full320CharactersAreStoredWhileThe321stIsRejected() {
        assertEquals(320, SandwichBoardState.blank("base").edit("菜".repeat(320), SandwichBoardState.Alignment.CENTER).text().length());
        assertThrows(IllegalArgumentException.class, () -> SandwichBoardState.blank("base").edit("a".repeat(321), SandwichBoardState.Alignment.LEFT));
    }
    @Test void invalidUnicodeAndControlCharactersAreRejectedBeforeSaving() {
        for (String text : new String[] {"\u0000", "\r", "\t", "\ud800", "\udc00", "x\ud800y"})
            assertThrows(IllegalArgumentException.class, () -> SandwichBoardState.validateText(text));
        assertDoesNotThrow(() -> SandwichBoardState.validateText("😀\n中文"));
    }
    @Test void unknownVersionMissingFieldsAndWrongNbtTypesFailClosed() {
        CompoundTag valid = SandwichBoardState.blank("base").save();
        var version = valid.deepClone(); version.putInt("version", 2);
        assertThrows(IllegalArgumentException.class, () -> SandwichBoardState.read(version));
        for (String field : new String[] {"text", "color", "style", "alignment", "waxed", "glowing"}) {
            var wrong = valid.deepClone(); wrong.putInt(field, 3);
            assertThrows(IllegalArgumentException.class, () -> SandwichBoardState.read(wrong));
        }
    }
    @Test void floralMappingsPreserveTheSourceAliasesAndModernShortGrassName() {
        assertEquals("grass", SandwichBoardState.pattern(Material.SHORT_GRASS));
        assertEquals("azure_bluet", SandwichBoardState.pattern(Material.LILY_OF_THE_VALLEY));
        assertEquals("poppy", SandwichBoardState.pattern(Material.ROSE_BUSH));
        assertEquals("sunflower", SandwichBoardState.pattern(Material.DANDELION));
        assertEquals("tulip", SandwichBoardState.pattern(Material.WHITE_TULIP));
        assertEquals("peony", SandwichBoardState.pattern(Material.LILAC));
        assertNull(SandwichBoardState.pattern(Material.HONEYCOMB));
    }
    @Test void aPatternChangeRetainsTextAlignmentColorWaxAndGlow() {
        var before = SandwichBoardState.blank("base").edit("招牌", SandwichBoardState.Alignment.LEFT).dye("red").glow(true).wax();
        var after = before.pattern("cornflower");
        assertEquals(before.text(), after.text()); assertEquals(before.color(), after.color());
        assertEquals(before.alignment(), after.alignment()); assertTrue(after.waxed()); assertTrue(after.glowing());
    }
    @Test void nonGlowingTextUsesTheSourceSixtyPercentDarkening() {
        assertEquals(0x999999, SandwichBoardState.blank("base").textRgb());
        assertEquals(0xff0000, SandwichBoardState.blank("base").dye("red").glow(true).textRgb());
        assertEquals(0, SandwichBoardState.blank("base").dye("black").glow(true).textRgb());
    }
    @Test void everyOpeningTagAndBackslashIsEscapedBeforeCeResolvesTags() {
        String text = "<click:run_command:'/op X'>\\<papi:player_name><red>菜</red>";
        var state = SandwichBoardState.blank("base").edit(text, SandwichBoardState.Alignment.CENTER);
        String encoded = SandwichBoardDisplay.literalText(state, text);
        assertEquals("<#999999><bold>" + text.replace("\\", "\\\\").replace("<", "\\<") + "</bold>", encoded);
    }
    @Test void displayBoundsRenderingToEightRowsAndKeepsTheEntireTextInState() {
        var state = SandwichBoardState.blank("base").edit("W".repeat(320), SandwichBoardState.Alignment.CENTER);
        var lines = SandwichBoardText.lines(state.text());
        assertEquals(8, lines.size());
        for (String line : lines) assertTrue(SandwichBoardText.width(line) <= 55);
        assertEquals(320, state.text().length());
    }
    @Test void explicitNewlinesAndAstralCharactersDoNotSplitSurrogatePairs() {
        assertEquals(java.util.List.of("a", "", "b"), SandwichBoardText.lines("a\n\nb"));
        for (String line : SandwichBoardText.lines("😀".repeat(100))) {
            assertDoesNotThrow(() -> SandwichBoardState.validateText(line));
            assertTrue(SandwichBoardText.width(line) <= 55);
        }
        assertTrue(SandwichBoardText.lines("").isEmpty());
    }
    @Test void glowingBrightnessAndFixedFirstRowAreNativeDisplayConfiguration() {
        var blank = SandwichBoardState.blank("base");
        var one = SandwichBoardDisplay.configuration(blank, "A", 0);
        var eight = SandwichBoardDisplay.configuration(blank.glow(true), "A", 7);
        assertEquals(0, one.get("background_color"));
        assertFalse(one.containsKey("brightness"));
        assertEquals(java.util.Map.of("block_light", 15, "sky_light", 15), eight.get("brightness"));
        assertEquals("-0.01000,1.15239,-0.02173", one.get("position"));
        assertNotEquals(one.get("position"), eight.get("position"));
    }
}
