import copy
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from deco_seating import ASSET_NS, COLORS, SOURCE, WOODS, append_seating, static_model
import import_sources
from import_sources import furniture, model_tree, placeable


def encoded(value):
    return json.dumps(value).encode()


def fixture():
    files = {SOURCE: b'input.chair set value input.cook_stool set value '
             b'func:"kaleidoscope:cookery_plus/events/carpets" '
             b'func:"kaleidoscope:cookery_plus/events/shear" fallback:{event:"sit"}'}
    original = {"kaleidoscope/lang/zh_cn.json": encoded({"chair.oak": "橡木椅子"})}
    for kind in ("chair", "cook_stool"):
        model = f"{ASSET_NS}:{kind}"
        wood_selector = {"type": "select", "property": "custom_model_data", "index": 0,
                         "cases": [{"when": wood, "model": {"type": "model", "model": model + "/" + wood}}
                                   for wood in WOODS], "fallback": {"type": "empty"}}
        carpet_selector = {"type": "select", "property": "custom_model_data", "index": 1,
                           "cases": [{"when": color, "model": {"type": "model", "model": model + "/" + color}}
                                     for color in COLORS], "fallback": {"type": "empty"}}
        original[f"{ASSET_NS}/items/{kind}.json"] = encoded({"model": {"type": "composite", "models":
            [wood_selector, carpet_selector]} if kind == "chair" else wood_selector})
        for wood in WOODS:
            ingredient = "#" + wood + ("_blocks" if wood == "bamboo" else
                                         "_stems" if wood in ("crimson", "warped") else "_logs")
            components = {"item_model": model, "custom_model_data": {"strings": [wood, "empty"] if kind == "chair" else [wood]},
                          "item_name": {"translate": kind + "." + wood},
                          "entity_data": {"data": {"index": kind, "inheritance": {"item": {"components": {
                              "minecraft:item_model": model, "minecraft:custom_model_data": {"strings": [wood, "empty"]}}}}}}}
            files[f"src/kaleidoscope_cookery_plus/data/kaleidoscope/recipe/cookery_plus/{kind}/{wood}.json"] = encoded(
                {"type": "stonecutting", "ingredient": ingredient,
                 "result": {"id": "item_frame", "count": 1, "components": components}})
    return files, original


def convert(files=None, original=None):
    if files is None:
        files, original = fixture()
    items, furnishings, entries = {}, {}, []
    recipes = append_seating(files, original, {ASSET_NS}, items, furnishings, entries, model_tree, furniture, placeable)
    return items, furnishings, entries, recipes


class DecoSeatingTests(unittest.TestCase):
    def test_main_converter_exports_seating_catalog_definitions_and_recipes(self):
        files, original = fixture()
        files.update({f"src/{ASSET_NS}/assets/{path}": content for path, content in original.items()})
        source = SimpleNamespace(files=files)

        with patch.object(import_sources, "deco_indexes", return_value=({}, {}, {}, [])), \
                patch("deco_fishrack.add_fishrack_variants"):
            catalog, definitions, _, _ = import_sources.deco(source)

        seating = [entry for entry in catalog["entries"] if entry["source"] == SOURCE]
        self.assertEqual(24, len(seating))
        self.assertIn("kaleidoscope_deco:chair/oak", definitions["furniture"])
        self.assertIn("kaleidoscope_deco:cook_stool/warped", definitions["items"])
        self.assertEqual(24, len(definitions["recipes"]))

    def test_all_wood_families_and_recipes_are_explicit(self):
        items, furnishings, entries, recipes = convert()
        self.assertEqual((216, 24, 24, 24), tuple(map(len, (items, furnishings, entries, recipes))))
        self.assertEqual(set(furnishings), set(recipes))
        for kind in ("chair", "cook_stool"):
            self.assertEqual(set(WOODS), {key.rsplit("/", 1)[1] for key in furnishings if key.startswith(f"kaleidoscope_deco:{kind}/")})

    def test_chairs_have_seventeen_states_and_stools_have_no_invented_seat(self):
        items, furnishings, _, _ = convert()
        for wood in WOODS:
            chair = furnishings[f"kaleidoscope_deco:chair/{wood}"]
            self.assertEqual({"ground", *["cushion/" + color for color in COLORS]}, set(chair["variants"]))
            self.assertEqual(["0,0.4,0 0"], chair["variants"]["ground"]["hitboxes"][0]["seats"])
            # Existing cushion changes may force a same-geometry CE variant switch.
            for variant in chair["variants"].values():
                self.assertEqual(chair["variants"]["ground"]["hitboxes"], variant["hitboxes"])
            stool = furnishings[f"kaleidoscope_deco:cook_stool/{wood}"]
            self.assertEqual({"ground"}, set(stool["variants"]))
            self.assertNotIn("seats", stool["variants"]["ground"]["hitboxes"][0])
            self.assertEqual([{"type": "kaleidoscope_deco:wooden_seat"}], chair["behaviors"])
            self.assertEqual("橡木椅子", items["kaleidoscope_deco:chair/oak"]["data"]["item_name"])

    def test_models_resolve_both_composite_layers_without_dynamic_cmd(self):
        items, furnishings, _, _ = convert()
        for wood in WOODS:
            base = f"kaleidoscope_deco:chair/{wood}"
            self.assertEqual(f"kdeco_{ASSET_NS}:chair/{wood}", items[base]["model"]["models"][0]["path"])
            self.assertEqual({"type": "minecraft:empty"}, items[base]["model"]["models"][1])
            for color in COLORS:
                visual = base + "/cushion/" + color
                self.assertEqual(f"kdeco_{ASSET_NS}:chair/{color}", items[visual]["model"]["models"][1]["path"])
                self.assertEqual(visual, furnishings[base]["variants"]["cushion/" + color]["elements"][0]["item"])
        self.assertNotIn("custom_model_data", json.dumps(items))
        self.assertNotIn('"behavior"', json.dumps({key: value for key, value in items.items() if "/cushion/" in key}))

    def test_collision_uses_native_invisible_shulker_and_source_dimensions(self):
        _, furnishings, _, _ = convert()
        for kind, width, height, scale, position in (("chair", 1., 1., .8, "0,-0.2,0"), ("cook_stool", .8, .5, .6, "0,-0.18,0")):
            hitboxes = furnishings[f"kaleidoscope_deco:{kind}/oak"]["variants"]["ground"]["hitboxes"]
            self.assertEqual((width, height), (hitboxes[0]["width"], hitboxes[0]["height"]))
            self.assertEqual(("shulker", scale, position, 0), tuple(hitboxes[1][key] for key in ("type", "scale", "position", "peek")))
            self.assertTrue(hitboxes[1]["invisible"])
            self.assertFalse(hitboxes[1]["interaction_entity"])

    def test_stonecutting_tags_preserve_bamboo_and_nether_inputs(self):
        _, _, _, recipes = convert()
        for kind in ("chair", "cook_stool"):
            for wood, ingredient in (("oak", "oak_logs"), ("bamboo", "bamboo_blocks"), ("crimson", "crimson_stems"), ("warped", "warped_stems")):
                key = f"kaleidoscope_deco:{kind}/{wood}"
                self.assertEqual({"type": "stonecutting", "ingredients": ["#minecraft:" + ingredient], "result": {"id": key, "count": 1}}, recipes[key])

    def test_missing_or_new_wood_cannot_silently_change_family(self):
        files, original = fixture()
        files.pop("src/kaleidoscope_cookery_plus/data/kaleidoscope/recipe/cookery_plus/chair/oak.json")
        with self.assertRaisesRegex(ValueError, "wood recipes changed"):
            convert(files, original)

    def test_changed_recipe_result_ingredient_and_inheritance_fail_closed(self):
        baseline, original = fixture()
        path = "src/kaleidoscope_cookery_plus/data/kaleidoscope/recipe/cookery_plus/chair/oak.json"
        for change in (lambda r: r.update(ingredient="#oak_planks"),
                       lambda r: r["result"].update(count=2),
                       lambda r: r["result"]["components"]["custom_model_data"].update(strings=["oak", "red"]),
                       lambda r: r["result"]["components"]["entity_data"]["data"]["inheritance"]["item"]["components"].update({"minecraft:custom_model_data": {"strings": ["birch", "empty"]}})):
            files = copy.deepcopy(baseline)
            raw = json.loads(files[path])
            change(raw)
            files[path] = encoded(raw)
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                convert(files, original)

    def test_changed_source_behavior_fails_closed(self):
        files, original = fixture()
        files[SOURCE] = files[SOURCE].replace(b'fallback:{event:"sit"}', b'fallback:{event:"__nothing__"}')
        with self.assertRaisesRegex(ValueError, "behavior changed"):
            convert(files, original)

    def test_static_model_fallback_and_lists_are_resolved_without_mutating_source(self):
        selector = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 1,
                    "cases": [{"when": ["red", "blue"], "model": {"type": "model", "model": "test:pad"}}],
                    "fallback": {"type": "empty"}}
        before = copy.deepcopy(selector)
        self.assertEqual({"type": "model", "model": "test:pad"}, static_model(selector, ["oak", "red"]))
        self.assertEqual({"type": "empty"}, static_model(selector, ["oak"]))
        self.assertEqual(before, selector)

    def test_unknown_model_nodes_are_not_silently_exported(self):
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            static_model({"type": "condition", "property": "using_item"}, [])


if __name__ == "__main__":
    unittest.main()
