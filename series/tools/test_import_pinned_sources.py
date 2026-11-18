"""Validate resource and recipe compatibility against fixed upstream checkouts."""
import unittest

from import_sources import SourceSnapshot, crafting, deco, json_bytes, parse_json, tavern


class PinnedImportTests(unittest.TestCase):
    def test_pinned_deco_legacy_item_ids_resolve_to_existing_geometry(self):
        source = SourceSnapshot("KaleidoscopeDeco", "6ce180e1226ce93b0fb0d45ea80e0ed5b01b1dc0")
        _, _, assets, _ = deco(source)
        namespace = "kdeco_kaleidoscope_cookery"
        for name in ("slime_ball_meal", "mysterious"):
            legacy = parse_json(assets[f"{namespace}/items/{name}.json"])["model"]["model"]
            self.assertEqual(f"{namespace}:item/{name}", legacy)
            model = parse_json(assets[f"{namespace}/models/item/{name}.json"])
            self.assertEqual(f"{namespace}:meals/item/{name}", model["parent"])
            self.assertIn(f"{namespace}/models/meals/item/{name}.json", assets)
        gui = parse_json(assets[f"{namespace}/models/meals/gui/frogspawn_jelly.json"])
        self.assertTrue(gui["elements"])
        self.assertIn("gui", gui["display"])
        dark = parse_json(assets[f"{namespace}/models/meals/gui/dark.json"])
        self.assertEqual(f"{namespace}:block/meals/plate", dark["textures"]["particle"])
        self.assertIn(f"{namespace}/textures/block/meals/plate.png", assets)

    def test_pinned_tavern_lamp_uses_target_chain_texture(self):
        source = SourceSnapshot("KaleidoscopeTavern", "c4ec1880bd44cf3139d3ba744ab30bb379cf1416")
        _, _, assets, _ = tavern(source)
        lamps = [parse_json(content) for path, content in assets.items()
                 if "/models/block/deco/" in path and "/bell_pendant_lamp/" in path and path.endswith(".json")]
        self.assertTrue(lamps)
        self.assertTrue(any("minecraft:block/iron_chain" in model.get("textures", {}).values() for model in lamps))
        self.assertFalse(any("minecraft:block/chain" in model.get("textures", {}).values() for model in lamps))

    def test_pinned_tavern_recipes_use_target_1_21_11_ingredient_names(self):
        source = SourceSnapshot("KaleidoscopeTavern", "c4ec1880bd44cf3139d3ba744ab30bb379cf1416")
        tags = source.resources("data")
        recipe_paths = ("bell_pendant_lamp", "grass_sandwich_board_from_grass")
        recipes = {f"kaleidoscope_tavern/recipes/{name}.json":
                   tags[f"kaleidoscope_tavern/recipes/{name}.json"] for name in recipe_paths}
        items = {"kaleidoscope_tavern:" + name: {} for name in
                 ("bell_pendant_lamp", "base_sandwich_board", "grass_sandwich_board")}
        converted, pending = crafting(recipes, items)
        self.assertEqual([], pending)
        lamp = converted["kaleidoscope_tavern:bell_pendant_lamp"]
        self.assertEqual(["C", "C", "B"], lamp["pattern"])
        self.assertEqual({"B": "minecraft:bell", "C": "minecraft:iron_chain"}, lamp["ingredients"])
        self.assertEqual({"id": "kaleidoscope_tavern:bell_pendant_lamp", "count": 8}, lamp["result"])
        board = converted["kaleidoscope_tavern:grass_sandwich_board_from_grass"]
        self.assertEqual(["kaleidoscope_tavern:base_sandwich_board", "minecraft:short_grass"],
                         board["ingredients"])
        self.assertEqual({"id": "kaleidoscope_tavern:grass_sandwich_board", "count": 1}, board["result"])


if __name__ == "__main__":
    unittest.main()
