import json
import tempfile
import unittest
from pathlib import Path

import yaml

from check_loaded_states import RegistrationError, check


class LoadedStateTests(unittest.TestCase):
    def test_axis_requires_all_three_orientations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.instance(root, {"axis": {"type": "axis", "default": "y"}},
                          {"kaleidoscopecookery:fixture[axis=y]": 0})
            report = check(root)
            self.assertFalse(report["complete"])
            self.assertEqual(3, report["expected"])
            self.assertEqual(["kaleidoscopecookery:fixture[axis=x]",
                              "kaleidoscopecookery:fixture[axis=z]"], report["missing"])

    def instance(self, root, properties, states, capacity=100):
        engine = root / "plugins/CraftEngine"
        (engine / "resources/cookery_kitchen/configuration").mkdir(parents=True)
        (engine / "cache").mkdir()
        (engine / "config.yml").write_text(yaml.safe_dump({"block": {"serverside-blocks": capacity}}), encoding="utf-8")
        kitchen = {"blocks": {"kaleidoscopecookery:fixture": {"states": {
            "properties": properties,
            "variants": {"face=floor": {"appearance": "floor"}, "face=wall": {"appearance": "wall"}}}}}}
        (engine / "resources/cookery_kitchen/configuration/kitchen.yml").write_text(yaml.safe_dump(kitchen), encoding="utf-8")
        (engine / "cache/custom_block_states.json").write_text(json.dumps(states), encoding="utf-8")
        return root

    def test_anchor_type_includes_implicit_ceiling(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            states = {f"kaleidoscopecookery:fixture[face={face}]": i for i, face in enumerate(("floor", "wall", "ceiling"))}
            self.instance(root, {"face": {"type": "anchor_type", "default": "floor"}}, states)
            report = check(root)
            self.assertTrue(report["complete"])
            self.assertEqual(3, report["expected"])
            self.assertEqual(3, report["loaded"])
            self.assertEqual([], report["missing"])

    def test_missing_middle_integer_state_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            states = {"kaleidoscopecookery:fixture[level=0]": 0, "kaleidoscopecookery:fixture[level=2]": 1}
            self.instance(root, {"level": {"type": "int", "range": "0~2"}}, states)
            report = check(root)
            self.assertFalse(report["complete"])
            self.assertEqual(["kaleidoscopecookery:fixture[level=1]"], report["missing"])

    def test_duplicate_id_anywhere_in_cache_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.instance(root, {}, {"kaleidoscopecookery:fixture": 0, "default:other": 0})
            with self.assertRaisesRegex(RegistrationError, "duplicate internal ID"):
                check(root)

    def test_out_of_capacity_id_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.instance(root, {}, {"kaleidoscopecookery:fixture": 10}, capacity=10)
            with self.assertRaisesRegex(RegistrationError, "outside capacity"):
                check(root)

    def test_explicit_enum_values_and_string_values_form_full_product(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            properties = {"face": {"type": "anchor_type", "values": ["floor", "wall"]},
                          "part": {"type": "string", "values": ["left", "right"]}}
            states = {f"kaleidoscopecookery:fixture[part={part},face={face}]": i
                      for i, (part, face) in enumerate((('left', 'floor'), ('left', 'wall'),
                                                        ('right', 'floor'), ('right', 'wall')))}
            self.instance(root, properties, states)
            self.assertEqual(4, check(root)["expected"])
            self.assertTrue(check(root)["complete"])

    def test_unknown_property_type_and_unexpected_kitchen_state_are_not_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.instance(root, {"shape": {"type": "invented"}}, {"kaleidoscopecookery:fixture": 0})
            with self.assertRaisesRegex(RegistrationError, "unknown property type"):
                check(root)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.instance(root, {}, {"kaleidoscopecookery:fixture": 0, "kaleidoscopecookery:unexpected": 1})
            report = check(root)
            self.assertFalse(report["complete"])
            self.assertEqual(["kaleidoscopecookery:unexpected"], report["extra"])


if __name__ == "__main__":
    unittest.main()
