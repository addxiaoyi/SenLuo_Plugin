import json
import unittest

from tavern_furniture import VANILLA_BOTTLES, add_variants


class TavernFurnitureTests(unittest.TestCase):
    def fixtures(self):
        furniture = {"kaleidoscope_tavern:" + name: {"variants": {"ground": {
            "elements": [{"item": "kaleidoscope_tavern:" + name}], "hitboxes": []}}}
            for name in ("trellis", "empty_bottle", "empty_glassware", "pressing_tub")}
        assets = {}
        for fruit in ("grape", "ice_grape", "gold_grape"):
            plant = fruit.replace("grape", "grapevine_trellis")
            for age in range(4):
                assets[f"kaleidoscope_tavern/models/block/plant/{plant}/single_stage{age}.json"] = b"{}"
            for age in range(6):
                assets[f"kaleidoscope_tavern/models/block/plant/{fruit}_crop/stage{age}.json"] = b"{}"
        for bottle in VANILLA_BOTTLES.values():
            assets[f"kaleidoscope_tavern/models/block/brew/{bottle}.json"] = b"{}"
        return furniture, assets

    def test_vanilla_drinks_use_block_models_without_fake_custom_item_ids(self):
        furniture, assets = self.fixtures()
        items = {}
        add_variants(items, furniture, {"effects": {}, "barrel": [], "pressing": []}, assets)
        for carrier in ("empty_bottle", "empty_glassware"):
            variants = furniture["kaleidoscope_tavern:" + carrier]["variants"]
            for output, model in VANILLA_BOTTLES.items():
                display = variants[output.replace(":", "/")]["elements"][0]["item"]
                self.assertEqual(f"kaleidoscope_tavern:block/brew/{model}", items[display]["model"]["path"])
                self.assertNotIn("kaleidoscope_tavern:" + model, items)
            self.assertEqual("kaleidoscope_tavern:" + carrier, variants["ground"]["elements"][0]["item"])

    def test_missing_bottle_model_rejects_export(self):
        furniture, assets = self.fixtures()
        del assets["kaleidoscope_tavern/models/block/brew/water_bottle.json"]
        with self.assertRaisesRegex(ValueError, "water_bottle"):
            add_variants({}, furniture, {"effects": {}, "barrel": [], "pressing": []}, assets)

    def test_pressing_liquid_uses_source_texture_and_rises_in_eight_steps(self):
        furniture, assets = self.fixtures()
        items = {}
        assets["kaleidoscope_tavern/textures/block/grape_juice_still.png"] = b"fixture"
        add_variants(items, furniture, {"effects": {}, "barrel": [],
                     "pressing": [{"fluid": "kaleidoscope_tavern:grape_juice"}]}, assets)
        tub = furniture["kaleidoscope_tavern:pressing_tub"]["variants"]
        self.assertEqual(9, len(tub))
        for level in range(1, 9):
            variant = tub[f"kaleidoscope_tavern/grape_juice/{level}"]
            display = variant["elements"][1]["item"]
            model = items[display]["model"]["path"].split(":")[1]
            geometry = json.loads(assets[f"kaleidoscope_tavern/models/{model}.json"])
            self.assertEqual(2 + level / 2, geometry["elements"][0]["from"][1])
            self.assertEqual("kaleidoscope_tavern:block/grape_juice_still", geometry["textures"]["liquid"])


if __name__ == "__main__":
    unittest.main()
