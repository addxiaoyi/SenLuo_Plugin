package net.kaleidoscope.cookery.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable consumption counter; an exhausted dish remains placed until explicitly removed. */
public record PlacedMealState(int version, String sourceCommit, String itemId, int maxBites,
                              int consumed, QualityProfile quality) {
    private static final Gson JSON = new Gson();
    private static final int MAX_BYTES = 4_096;
    private static final Set<String> FIELDS = Set.of("version", "sourceCommit", "itemId", "maxBites", "consumed", "quality");

    public PlacedMealState {
        CatalogJson.requireId(itemId);
        if (version != 1 || !CookeryCatalog.SOURCE_COMMIT.equals(sourceCommit) || quality == null
                || maxBites < 1 || maxBites > 64 || consumed < 0 || consumed > maxBites) {
            throw new IllegalArgumentException("Invalid placed meal state");
        }
    }

    public static PlacedMealState fresh(PlacedMealDefinition definition, QualityProfile quality) {
        Objects.requireNonNull(definition, "definition");
        return new PlacedMealState(1, CookeryCatalog.SOURCE_COMMIT, definition.itemId(), definition.maxBites(), 0, quality);
    }

    public int remaining() { return maxBites - consumed; }
    public boolean empty() { return consumed == maxBites; }
    public boolean untouched() { return consumed == 0; }

    public PlacedMealState consumeOne() {
        return empty() ? this : new PlacedMealState(version, sourceCommit, itemId, maxBites, consumed + 1, quality);
    }

    public void validate(PlacedMealDefinition definition) {
        if (definition == null || !itemId.equals(definition.itemId()) || maxBites != definition.maxBites()) {
            throw new IllegalArgumentException("Placed meal state does not match its definition: " + itemId);
        }
    }

    /** Source loot returns the whole dish before its first bite, otherwise only containers and extras. */
    public List<String> dropIds(PlacedMealDefinition definition) {
        validate(definition);
        return untouched() ? List.of(itemId) : definition.drops();
    }

    public String encode() { return JSON.toJson(this); }

    public static PlacedMealState decode(String encoded, PlacedMealDefinition definition) {
        PlacedMealState state = decode(encoded);
        state.validate(definition);
        return state;
    }

    public static PlacedMealState decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid placed meal save size");
        }
        try {
            JsonObject root = CatalogJson.read(new StringReader(encoded));
            if (!root.keySet().equals(FIELDS) || !(root.get("quality") instanceof JsonObject quality)
                    || !quality.keySet().equals(Set.of("name", "ratio"))) {
                throw new IllegalArgumentException("Unexpected placed meal save fields");
            }
            return new PlacedMealState(integer(root, "version"), CatalogJson.string(root, "sourceCommit"),
                    CatalogJson.string(root, "itemId"), integer(root, "maxBites"), integer(root, "consumed"),
                    new QualityProfile(CatalogJson.string(quality, "name"), CatalogJson.number(quality, "ratio")));
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid placed meal save", invalid);
        }
    }

    private static int integer(JsonObject root, String key) {
        if (!(root.get(key) instanceof JsonPrimitive value) || !value.isNumber()) {
            throw new IllegalArgumentException("Expected placed meal integer: " + key);
        }
        return value.getAsBigDecimal().intValueExact();
    }
}
