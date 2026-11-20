package net.kaleidoscope.cookery.recipe.edit;

import net.kaleidoscope.cookery.recipe.StockpotRecipe;
import net.kaleidoscope.cookery.recipe.StockpotVisuals;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class StockpotFileEditTest {
    @TempDir Path directory;

    @Test
    void patchesEveryStrictFieldAndRoundTripsThroughTheParser() throws Exception {
        Path file = directory.resolve("stockpot.yml");
        String node = "stockpot_recipes#local.test:soup";
        Files.writeString(file, "stockpot_recipes#local:\n  test:soup:\n    match: exact\n    ingredients:\n      - [minecraft:bone, minecraft:carrot]\n    soup_base: minecraft:water_bucket\n    result: minecraft:beef\n    result_count: 2\n    time: 240\n    carrier: minecraft:air\n    visuals:\n      cooking_model: test:cooking\n      finished_model: test:finished\n      cooking_color: 1193046\n      finished_color: 6636321\n");
        var targets = RecipeFileStore.resolveTargets(RecipeSourceIndex.Kind.STOCKPOT, Key.of("test:soup"), file, node);
        assertEquals(1, targets.size());
        assertEquals(240, targets.getFirst().originalSource().get("time"));
        assertEquals("minecraft:air", targets.getFirst().originalSource().get("carrier"));
        assertTrue(targets.getFirst().originalSource().containsKey("visuals"));

        Key updatedId = Key.of("test:updated");
        String updatedNode = "stockpot_recipes#local.test:updated";
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("match", "distinct");
        changes.put("ingredients", List.of(
                List.of("minecraft:beef", "minecraft:chicken"),
                List.of("minecraft:potato")));
        changes.put("soup_base", "minecraft:lava_bucket");
        changes.put("result", "test:stew");
        changes.put("result_count", 7);
        changes.put("time", 480);
        changes.put("carrier", "test:bowl");
        changes.put("visuals", Map.of("cooking_model", "test:updated_cooking",
                "finished_model", "test:updated_finished", "cooking_color", 0x123456,
                "finished_color", 0xABCDEF));

        try (var ignored = mockStatic(Config.class)) {
            RecipeFileStore.patchTarget(file, targets.getFirst(), updatedNode, updatedId, changes, () -> false);
        }

        Map<String, Object> root = stringMap(net.kaleidoscope.cookery.util.CookeryYaml.readMapping(file));
        Map<String, Object> section = stringMap(root.get("stockpot_recipes#local"));
        Map<String, Object> savedNode = stringMap(section.get("test:updated"));
        StockpotRecipe parsed = StockpotRecipe.fromConfig(updatedId, ConfigSection.of(updatedNode, savedNode));
        StockpotRecipe expected = new StockpotRecipe(updatedId, StockpotRecipe.Mode.DISTINCT,
                List.of(Set.of(Key.of("minecraft:beef"), Key.of("minecraft:chicken")),
                        Set.of(Key.of("minecraft:potato"))), Key.of("minecraft:lava_bucket"),
                Key.of("test:stew"), 7, 480, Key.of("test:bowl"),
                new StockpotVisuals(Key.of("test:updated_cooking"), Key.of("test:updated_finished"),
                        0x123456, 0xABCDEF));
        assertEquals(expected, parsed);
        assertFalse(section.containsKey("test:soup"));
    }

    @Test
    void refusesPatchAfterTheSourceChangesOutsideTheMenu() throws Exception {
        Path file = directory.resolve("stockpot.yml");
        String node = "stockpot_recipes#local.test:soup";
        Files.writeString(file, "stockpot_recipes#local:\n  test:soup:\n    match: exact\n    ingredients:\n      - [minecraft:bone]\n    soup_base: minecraft:water_bucket\n    result: minecraft:beef\n    result_count: 2\n    time: 240\n    carrier: minecraft:air\n    visuals:\n      cooking_model: test:cooking\n      finished_model: test:finished\n      cooking_color: 1193046\n      finished_color: 6636321\n");
        var source = RecipeFileStore.resolveTargets(RecipeSourceIndex.Kind.STOCKPOT,
                Key.of("test:soup"), file, node).getFirst();
        Files.writeString(file, Files.readString(file).replace("time: 240", "time: 241"));
        byte[] changed = Files.readAllBytes(file);

        IOException failure = assertThrows(IOException.class, () -> RecipeFileStore.patchTarget(file, source,
                node, Key.of("test:soup"), Map.of("time", 480), () -> false));
        assertTrue(failure.getMessage().contains("已变化"));
        assertArrayEquals(changed, Files.readAllBytes(file));
    }

    private static Map<String, Object> stringMap(Object value) {
        assertInstanceOf(Map.class, value);
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }
}
