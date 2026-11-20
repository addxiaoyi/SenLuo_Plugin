package net.kaleidoscope.cookery.catalog;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class BakedFoodCanonicalPayloadTest {
    @Test void everyBaseFoodHasTheExactExporterPdcString() throws Exception {
        try (var stream = getClass().getResourceAsStream("/catalog/base-food-v1.json")) {
            assertNotNull(stream, "Missing shared Python/Java food_v1 byte golden");
            var expected = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var actualIds = new TreeSet<String>();
            for (var entry : CookeryCatalog.bundled().snapshot().items().values()) {
                var baked = BakedFood.of(entry, QualityProfile.BASE);
                if (baked == null) continue;
                actualIds.add(entry.id());
                assertTrue(expected.has(entry.id()), "Missing exporter PDC golden: " + entry.id());
                assertArrayEquals(expected.get(entry.id()).getAsString().getBytes(StandardCharsets.UTF_8),
                        baked.encode().getBytes(StandardCharsets.UTF_8), "Nonstackable food_v1 encoding: " + entry.id());
            }
            assertEquals(103, expected.size(), "Pinned BASE consumable coverage changed");
            assertEquals(new TreeSet<>(expected.keySet()), actualIds, "Exporter/production consumable sets differ");
        }
    }

    @Test void legacyCePayloadStringsDecodeWithoutChangingTheirFoodSnapshot() throws Exception {
        try (var stream = getClass().getResourceAsStream("/catalog/base-food-v1.json")) {
            assertNotNull(stream, "Missing shared Python/Java food_v1 byte golden");
            var goldens = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var catalog = CookeryCatalog.bundled().snapshot().items();
            for (String name : List.of("tomato", "barley_tea", "caterpillar")) {
                String id = "kaleidoscopecookery:" + name;
                String canonical = goldens.get(id).getAsString();
                var legacy = JsonParser.parseString(canonical).getAsJsonObject();
                var food = legacy.getAsJsonObject("food");
                if (!name.equals("barley_tea")) {
                    // The old exporter appended saturation after the other food members.
                    var saturation = food.remove("saturation");
                    food.add("saturation", saturation);
                } else {
                    assertEquals(0.0, food.get("saturation").getAsDouble());
                    food.addProperty("saturation", 0);
                }
                if (name.equals("caterpillar")) {
                    var effect = food.getAsJsonArray("effects").get(0).getAsJsonObject();
                    assertEquals(1.0, effect.get("probability").getAsDouble());
                    effect.addProperty("probability", 1);
                }
                String previousEncoding = legacy.toString();
                assertNotEquals(canonical, previousEncoding, "Legacy form was not exercised: " + id);
                var decoded = BakedFood.decode(previousEncoding);
                assertEquals(BakedFood.of(catalog.get(id), QualityProfile.BASE), decoded,
                        "Legacy JSON text must not reinterpret nutrition, quality or effects: " + id);
                assertArrayEquals(canonical.getBytes(StandardCharsets.UTF_8), decoded.encode().getBytes(StandardCharsets.UTF_8),
                        "Legacy payload must re-encode to the exact canonical golden: " + id);
            }
        }
    }
}
