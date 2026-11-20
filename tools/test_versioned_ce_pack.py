import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

import yaml

from export_versioned_ce_pack import adapt_configuration, adapt_model_textures, build


class VersionedPackTest(unittest.TestCase):
    def test_chain_texture_rename_is_versioned_and_does_not_change_geometry_or_custom_overrides(self):
        model = {'parent': 'demo:lamp', 'textures': {'particle': 'block/chain', 'face': '#particle',
                 'custom': 'demo:block/chain', 'new': 'minecraft:item/iron_chain'},
                 'elements': [{'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {'up': {'texture': '#face'}}}]}
        for version in ('1.21.1', '1.21.8'):
            actual, count = adapt_model_textures(model, version, set())
            self.assertEqual('minecraft:item/chain', actual['textures']['new'])
            self.assertEqual('block/chain', actual['textures']['particle'])
            self.assertEqual(1, count)
        for version in ('1.21.9', '26.3'):
            actual, count = adapt_model_textures(model, version, set())
            self.assertEqual('minecraft:block/iron_chain', actual['textures']['particle'])
            self.assertEqual(model['elements'], actual['elements'])
            self.assertEqual('demo:block/chain', actual['textures']['custom'])
            self.assertEqual('#particle', actual['textures']['face'])
            self.assertEqual(1, count)
            overridden, count = adapt_model_textures(model, version, {'minecraft:block/chain'})
            self.assertEqual(model, overridden)
            self.assertEqual(0, count)
        self.assertEqual('block/chain', model['textures']['particle'])

    def test_model_only_adaptation_updates_manifest_and_is_independent_of_food_conversion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / 'source.zip'; output = root / 'modern.zip'
            model = 'resourcepack/assets/test/models/lamp.json'
            contents = {model: json.dumps({'textures': {'particle': 'block/chain'}}).encode(),
                        'pack.yml': b'namespace: test\n'}
            manifest = {'target': {}, 'files': {key: hashlib.sha256(raw).hexdigest() for key, raw in contents.items()}}
            with ZipFile(source, 'w') as archive:
                for key, raw in contents.items(): archive.writestr(key, raw)
                archive.writestr('manifest.json', json.dumps(manifest))
            report = build(source, output, '26.3')
            self.assertEqual(1, report['changes'][0]['vanillaChainTextures'])
            self.assertEqual([], report['limitations'])
            with ZipFile(output) as archive:
                updated = json.loads(archive.read('manifest.json'))
                self.assertEqual(hashlib.sha256(archive.read(model)).hexdigest(), updated['files'][model])
                self.assertEqual('minecraft:block/iron_chain', json.loads(archive.read(model))['textures']['particle'])

    def test_old_lunch_bag_does_not_require_experimental_bundle_feature(self):
        original = {'items': {'kaleidoscopecookery:transmutation_lunch_bag': {
            'material': 'bundle', 'data': {'components': {'minecraft:bundle_contents': []}}}}}
        legacy, changes = adapt_configuration(original, '1.21.1')
        self.assertEqual('paper', legacy['items']['kaleidoscopecookery:transmutation_lunch_bag']['material'])
        self.assertEqual(1, changes['legacyLunchBagMaterial'])
        modern, _ = adapt_configuration(original, '1.21.2')
        self.assertEqual(original, modern)

    def fixture(self):
        return {"items": {"test:tea": {"material": "paper", "data": {
            "food": {"nutrition": 2, "saturation": 3.5, "can_always_eat": True},
            "pdc": {"test:original": '{"sourceCommit":"fixed","container":"test:cup"}'},
            "components": {"minecraft:consumable": {"consume_seconds": 0.8, "animation": "drink",
                "on_consume_effects": [{"type": "minecraft:apply_effects", "probability": 0.25,
                    "effects": [{"id": "minecraft:speed", "duration": 600, "amplifier": 1}]}]}}}}},
            "recipes": {"test:lamp": {"ingredients": {"C": "minecraft:iron_chain"}, "result": {"id": "test:lamp", "count": 2}}}}

    def test_legacy_food_keeps_nutrition_duration_effect_probability_and_opaque_snapshot(self):
        original = self.fixture()
        adapted, changes = adapt_configuration(original, "1.21.1")
        data = adapted["items"]["test:tea"]["data"]
        food = data["components"]["minecraft:food"]
        self.assertEqual({"nutrition": 2, "saturation": 3.5, "can_always_eat": True, "eat_seconds": 0.8,
            "effects": [{"effect": {"id": "minecraft:speed", "duration": 600, "amplifier": 1}, "probability": 0.25}]}, food)
        self.assertNotIn("food", data)
        self.assertNotIn("minecraft:consumable", data["components"])
        self.assertEqual(original["items"]["test:tea"]["data"]["pdc"], data["pdc"])
        self.assertIn("minecraft:consumable", original["items"]["test:tea"]["data"]["components"])
        self.assertEqual({"ironChainAliases": 1, "legacyFoods": 1, "legacyDrinkAnimations": 1}, changes)

    def test_modern_component_and_chain_cutovers_are_independent(self):
        for version in ("1.21.2", "1.21.8"):
            output, _ = adapt_configuration(self.fixture(), version)
            self.assertIn("minecraft:consumable", output["items"]["test:tea"]["data"]["components"])
            self.assertEqual("minecraft:chain", output["recipes"]["test:lamp"]["ingredients"]["C"])
        for version in ("1.21.9", "1.21.11", "26.3"):
            output, _ = adapt_configuration(self.fixture(), version)
            self.assertEqual(self.fixture(), output)

    def test_tavern_drinks_without_nutrition_are_usable_at_full_hunger(self):
        config = self.fixture()
        del config["items"]["test:tea"]["data"]["food"]
        output, _ = adapt_configuration(config, "1.21.1")
        food = output["items"]["test:tea"]["data"]["components"]["minecraft:food"]
        self.assertEqual(0, food["nutrition"])
        self.assertEqual(0, food["saturation"])
        self.assertTrue(food["can_always_eat"])

    def test_unsupported_effect_and_correlated_probability_are_not_silently_lost(self):
        for effect in ({"type": "minecraft:teleport_randomly", "diameter": 16},
                       {"type": "minecraft:apply_effects", "probability": 0.5,
                        "effects": [{"id": "minecraft:speed"}, {"id": "minecraft:haste"}]}):
            config = self.fixture()
            config["items"]["test:tea"]["data"]["components"]["minecraft:consumable"]["on_consume_effects"] = [effect]
            with self.assertRaises(ValueError):
                adapt_configuration(config, "1.21.1")

    def test_archive_updates_hashes_and_preserves_assets_and_original(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source.zip", root / "output.zip"
            entries = {"test/pack.yml": b"namespace: test\n", "test/configuration/content.yml": json.dumps(self.fixture()).encode(),
                       "test/resourcepack/assets/test/texture.png": b"opaque-image", "test/licenses/LICENSE": b"unchanged"}
            manifest = {"target": {"minecraft": "1.21.11"}, "files": {n: hashlib.sha256(v).hexdigest() for n,v in entries.items()}}
            with ZipFile(source, 'w') as archive:
                for name, raw in entries.items(): archive.writestr(name, raw)
                archive.writestr("manifest.json", json.dumps(manifest))
            source_bytes = source.read_bytes()
            report = build(source, output, "1.21.1")
            self.assertEqual(source_bytes, source.read_bytes())
            with ZipFile(output) as archive:
                actual = json.loads(archive.read("manifest.json"))
                self.assertEqual("1.21.1", actual["target"]["minecraft"])
                for name, digest in actual["files"].items(): self.assertEqual(digest, hashlib.sha256(archive.read(name)).hexdigest())
                self.assertEqual(b"opaque-image", archive.read("test/resourcepack/assets/test/texture.png"))
                self.assertEqual(b"unchanged", archive.read("test/licenses/LICENSE"))
            self.assertEqual("NOT_TESTED", report["runtime"])
            with self.assertRaisesRegex(ValueError, "overwrite"): build(source, output, "1.21.1")
            with self.assertRaisesRegex(ValueError, "already adapted"): build(output, root / "again.zip", "26.3")

    def test_corrupted_source_manifest_is_rejected_before_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source.zip", root / "output.zip"
            with ZipFile(source, 'w') as archive:
                archive.writestr("test/pack.yml", b"changed")
                archive.writestr("manifest.json", json.dumps({"files": {"test/pack.yml": "wrong"}}))
            with self.assertRaisesRegex(ValueError, "manifest mismatch"): build(source, output, "1.21.1")
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
