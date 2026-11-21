import json
import unittest

from tavern_furniture import VANILLA_BOTTLES, BOARD_STYLES, add_sandwich_boards, add_molotov_furniture, add_variants


class TavernFurnitureTests(unittest.TestCase):
    def test_molotov_and_four_storage_shapes_use_cardinal_rotation_and_permanent_spear_component(self):
        from import_sources import furniture, item, placeable
        items, definitions = {}, {}
        for name in ("molotov", "holder", "tilted_rack", "circular_rack", "cellar_cabinet"):
            identifier = "kaleidoscope_tavern:" + name
            items[identifier] = item(identifier, identifier, name)
            placeable(items[identifier], identifier)
            definitions[identifier] = furniture(identifier, "kaleidoscope_tavern:molotov")
        add_molotov_furniture(items, definitions)
        for definition in items.values():
            self.assertEqual("four", definition["behavior"]["rules"]["ground"]["rotation"])
        component = items["kaleidoscope_tavern:molotov"]["data"]["components"]["minecraft:consumable"]
        self.assertEqual({"consume_seconds": 3600, "animation": "spear", "has_consume_particles": False}, component)
        heights = {name: definition["variants"]["ground"]["hitboxes"][0]["height"] for name, definition in definitions.items()}
        self.assertEqual(.875, heights["kaleidoscope_tavern:molotov"])
        self.assertEqual(.125, heights["kaleidoscope_tavern:circular_rack"])
        self.assertEqual(2, len(definitions["kaleidoscope_tavern:holder"]["variants"]["ground"]["hitboxes"]))

    def test_all_fourteen_board_types_have_every_pattern_and_full_height_hitbox(self):
        boards = {f"kaleidoscope_tavern:{style}_sandwich_board": {"variants": {"ground": {
            "elements": [{"item": style}], "hitboxes": [{}]}}} for style in BOARD_STYLES}
        add_sandwich_boards(boards)
        self.assertEqual(14, len(boards))
        for board in boards.values():
            self.assertEqual(15, len(board["variants"]))
            ground_hitboxes = board["variants"]["ground"]["hitboxes"]
            for style in BOARD_STYLES:
                variant = board["variants"][style]
                # Decoration switches bypass a second placement collision test only because geometry is identical.
                self.assertEqual(ground_hitboxes, variant["hitboxes"])
                self.assertEqual(f"kaleidoscope_tavern:{style}_sandwich_board", variant["elements"][0]["item"])
                self.assertEqual(1.375, variant["hitboxes"][0]["height"])
                self.assertEqual(.75, variant["hitboxes"][0]["width"])

    def test_partial_board_group_is_rejected_instead_of_silent_missing_styles(self):
        with self.assertRaisesRegex(ValueError, "All 14"):
            add_sandwich_boards({"kaleidoscope_tavern:base_sandwich_board": {"variants": {"ground": {}}}})

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
