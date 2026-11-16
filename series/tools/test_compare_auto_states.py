import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from compare_auto_states import ComparisonError, compare


class AutoStateComparisonTests(unittest.TestCase):
    def instance(self, root, name, cache, variants):
        instance = root / name
        engine = instance / "plugins/CraftEngine"
        (engine / "cache").mkdir(parents=True)
        (engine / "generated").mkdir()
        (engine / "cache/visual_block_states.json").write_text(json.dumps(cache), encoding="utf-8")
        with ZipFile(engine / "generated/resource_pack.zip", "w") as archive:
            archive.writestr("assets/minecraft/blockstates/brown_mushroom_block.json",
                             json.dumps({"variants": variants}))
        return instance

    def test_swapped_slots_preserve_each_appearance_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = self.instance(root, "paper", {
                "test:log": "minecraft:brown_mushroom_block[up=true,down=false]",
                "test:ore": "minecraft:brown_mushroom_block[up=false,down=true]"}, {
                "down=false,up=true": {"model": "test:block/log", "y": 90},
                "down=true,up=false": {"model": "test:block/ore"}})
            right = self.instance(root, "folia", {
                "test:log": "minecraft:brown_mushroom_block[up=false,down=true]",
                "test:ore": "minecraft:brown_mushroom_block[up=true,down=false]"}, {
                "down=false,up=true": {"model": "test:block/ore", "x": 0, "uvlock": False},
                "down=true,up=false": {"model": "test:block/log", "y": 90}})
            report = compare(left, right)
            self.assertTrue(report["equivalent"])
            self.assertEqual(2, report["appearances"])
            self.assertEqual(["test:log", "test:ore"], report["changedSlots"])
            self.assertEqual([], report["differences"])

    def test_changed_model_is_a_semantic_difference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = {"test:log": "minecraft:brown_mushroom_block[up=true]"}
            left = self.instance(root, "paper", cache, {"up=true": {"model": "test:block/log"}})
            right = self.instance(root, "folia", cache, {"up=true": {"model": "test:block/ore"}})
            report = compare(left, right)
            self.assertFalse(report["equivalent"])
            self.assertEqual("test:log", report["differences"][0]["appearance"])

    def test_missing_generated_selector_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = {"test:log": "minecraft:brown_mushroom_block[up=true]"}
            left = self.instance(root, "paper", cache, {"up=true": {"model": "test:block/log"}})
            right = self.instance(root, "folia", cache, {"up=false": {"model": "test:block/log"}})
            with self.assertRaisesRegex(ComparisonError, "missing variant.*test:log"):
                compare(left, right)

    def test_missing_appearance_on_one_side_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = self.instance(root, "paper", {"test:log": "minecraft:brown_mushroom_block[up=true]"},
                                 {"up=true": {"model": "test:block/log"}})
            right = self.instance(root, "folia", {"test:ore": "minecraft:brown_mushroom_block[up=true]"},
                                  {"up=true": {"model": "test:block/log"}})
            with self.assertRaisesRegex(ComparisonError, "appearance sets differ"):
                compare(left, right)

    def test_rotation_and_uvlock_changes_are_semantic_differences(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = {"test:log": "minecraft:brown_mushroom_block[up=true]"}
            left = self.instance(root, "paper", cache, {"up=true": {"model": "test:block/log"}})
            right = self.instance(root, "folia", cache, {
                "up=true": {"model": "test:block/log", "x": 90, "uvlock": True}})
            self.assertFalse(compare(left, right)["equivalent"])


if __name__ == "__main__":
    unittest.main()
