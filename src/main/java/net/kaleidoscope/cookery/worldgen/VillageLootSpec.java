package net.kaleidoscope.cookery.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Source IDs and recipe choices; randomness is performed by the native nested loot table. */
public record VillageLootSpec(Map<String, String> itemMappings, List<Choice> recipes, Set<String> tables) {
    static final String SOURCE_COMMIT = "2f4e386ce23f49a385ddf003c67fc6415c55417a";
    // Exact SHA-256 of the generated resource. Keeping this pin in the runtime parser
    // prevents packaged or test-supplied changes to source provenance and choices.
    static final String RESOURCE_SHA256 = "2032a09155849f81000bd225780ccc526d775afb3e61b9d2f49440db6f92cf60";
    static final String TOKEN_KEY = "kaleidoscopecookery:village_loot";
    static final String TOKEN_PREFIX = "v1|";
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    static final String NORMAL_TABLE = "kaleidoscopecookery:chest/village_chest";
    static final String HIDDEN_TABLE = "kaleidoscopecookery:chest/village_hide_chest";
    private static final Set<String> TABLES = Set.of(NORMAL_TABLE, HIDDEN_TABLE);
    private static final Map<String, Set<String>> TABLE_ITEMS = Map.of(
            NORMAL_TABLE, Set.of("tomato", "red_chili", "oil", "tomato_seed", "chili_seed", "lettuce_seed",
                    "wild_rice", "rice_panicle", "straw_block", "iron_kitchen_knife", "straw_hat"),
            HIDDEN_TABLE, Set.of("seafood_miso_soup", "chicken_and_mushroom_stew", "pork_bone_soup",
                    "braised_beef_with_potatoes", "beef_meatball_soup", "egg_fried_rice", "slime_ball_meal"));

    public record Recipe(String id, String type, List<String> ingredients, String result, int resultCount, String soupBase) {
        public Recipe {
            require(id != null && id.startsWith("kaleidoscopecookery:"), "Invalid recipe ID");
            identifier(id); identifier(result);
            require(Set.of("pot", "stockpot").contains(type), "Invalid recipe type");
            require(ingredients != null && !ingredients.isEmpty() && ingredients.size() <= 9, "Invalid ingredients");
            ingredients = List.copyOf(ingredients); ingredients.forEach(VillageLootSpec::identifier);
            require(resultCount >= 1 && resultCount <= 9, "Invalid result count");
            if (soupBase != null) identifier(soupBase);
            require(type.equals("stockpot") == (soupBase != null), "Invalid soup base");
        }
    }
    public record Choice(int index, String result, Recipe recipe) {}
    public record Token(String sourceItem, Choice recipe) {}

    public VillageLootSpec {
        itemMappings = Map.copyOf(itemMappings); recipes = List.copyOf(recipes); tables = Set.copyOf(tables);
        require(tables.equals(TABLES) && recipes.size() == 37 && itemMappings.size() == 18, "Invalid village loot schema");
        itemMappings.forEach((source, target) -> {
            identifier(source); identifier(target);
            require(source.startsWith("kaleidoscope_cookery:") && target.startsWith("kaleidoscopecookery:"), "Invalid item mapping");
        });
        for (int i = 0; i < recipes.size(); i++) {
            Choice choice = recipes.get(i);
            require(choice.index() == i && choice.result().startsWith("kaleidoscopecookery:"), "Invalid recipe order");
            identifier(choice.result());
            require(choice.recipe() == null || choice.result().equals(choice.recipe().result()), "Recipe result changed");
        }
    }

    public static VillageLootSpec bundled() {
        try (InputStream input = VillageLootSpec.class.getResourceAsStream("/village-loot.json")) {
            if (input == null) throw new IllegalStateException("Missing village-loot.json");
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException("Cannot read village loot spec", failure); }
    }

    static VillageLootSpec parse(String json) {
        requirePinnedResource(json);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        require(root.get("schemaVersion").getAsInt() == 1 && SOURCE_COMMIT.equals(root.get("sourceCommit").getAsString()), "Unknown village loot source");
        require(TOKEN_KEY.equals(root.get("tokenKey").getAsString()) && TOKEN_PREFIX.equals(root.get("tokenPrefix").getAsString()), "Unknown token schema");
        require("1.21.11".equals(root.getAsJsonObject("target").get("minecraft").getAsString()), "Unknown village target");
        var mappings = new LinkedHashMap<String, String>();
        root.getAsJsonObject("itemMappings").entrySet().forEach(entry -> mappings.put(entry.getKey(), entry.getValue().getAsString()));
        var choices = new ArrayList<Choice>();
        for (var element : root.getAsJsonArray("recipes")) {
            JsonObject row = element.getAsJsonObject(); Recipe recipe = null;
            if (!row.get("recipe").isJsonNull()) {
                JsonObject value = row.getAsJsonObject("recipe"); var ingredients = new ArrayList<String>();
                value.getAsJsonArray("ingredients").forEach(item -> ingredients.add(item.getAsString()));
                recipe = new Recipe(value.get("id").getAsString(), value.get("type").getAsString(), ingredients,
                        value.get("result").getAsString(), value.get("resultCount").getAsInt(),
                        value.get("soupBase").isJsonNull() ? null : value.get("soupBase").getAsString());
            }
            choices.add(new Choice(row.get("index").getAsInt(), row.get("result").getAsString(), recipe));
        }
        return new VillageLootSpec(mappings, choices, root.getAsJsonObject("tables").keySet());
    }

    private static void requirePinnedResource(String json) {
        require(json != null, "Missing village loot specification");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            String actual = HexFormat.of().formatHex(digest);
            require(RESOURCE_SHA256.equals(actual), "Unexpected village loot specification digest");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    Token token(String value, String table) {
        require(tables.contains(table), "Foreign village loot table");
        require(value != null && value.startsWith(TOKEN_PREFIX) && value.length() <= 160, "Invalid village token");
        String body = value.substring(TOKEN_PREFIX.length());
        if (body.startsWith("item|")) {
            String item = body.substring(5);
            require(itemMappings.containsKey(item), "Unmapped village item token: " + item);
            require(TABLE_ITEMS.get(table).contains(item.substring("kaleidoscope_cookery:".length())), "Village item token belongs to another table");
            return new Token(item, null);
        }
        require(NORMAL_TABLE.equals(table), "Recipe token belongs to another table");
        require(body.startsWith("recipe|"), "Invalid village token kind");
        String raw = body.substring(7);
        require(raw.matches("0|[1-9][0-9]?"), "Invalid recipe token index");
        int index = Integer.parseInt(raw);
        require(index < recipes.size(), "Recipe token index out of bounds");
        return new Token(null, recipes.get(index));
    }

    private static void identifier(String value) {
        require(value != null && ID.matcher(value).matches() && !value.contains(".."), "Invalid resource ID");
    }
    static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}
