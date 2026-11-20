"""Guard pinned crop overrides and lossless CE loot/config conversion without a JVM."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from audit_upstream import AuditError, read_blobs
from cookery_models import convert_models
from cookery_source_crops import (ART, PLUGIN, COMMIT, JAVA_HASHES, CROPS, crop_loot,
                                 source_crops, source_loot, validate_sources)
from export_cookery_food_pack import source_assets

SOURCE = Path(__file__).resolve().parents[2] / '.references' / 'KaleidoscopeCookery'


class SourceCropsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.assets, _ = source_assets(SOURCE)
        cls.definitions, _ = convert_models(cls.assets)
        cls.names = json.loads(cls.assets[ART + '/lang/zh_cn.json'])
        cls.items = {PLUGIN + ':' + name: {} for row in CROPS.values() for name in row[:2] if name}
        cls.paths = list(JAVA_HASHES) + ['src/generated/resources/data/' + ART + '/loot_tables/blocks/' + n + '_crop.json' for n in CROPS]
        cls.sources = read_blobs(SOURCE, COMMIT, cls.paths)

    def export(self, assets=None, items=None):
        return source_crops(SOURCE, self.assets if assets is None else assets,
                            copy.deepcopy(self.items) if items is None else items, self.definitions, self.names)

    def test_exact_source_states_seed_placement_and_stage_models(self):
        items = copy.deepcopy(self.items)
        blocks, manifest = self.export(items=items)
        self.assertEqual(3, len(blocks))
        self.assertEqual(24, sum(len(b['states']['variants']) for b in blocks.values()))
        allocations = set()
        for name in CROPS:
            key, seed = PLUGIN + ':' + name + '_crop', PLUGIN + ':' + name + '_seed'
            block = blocks[key]
            self.assertEqual({'type': 'block_item', 'block': key}, items[seed]['behavior'])
            self.assertEqual(seed, block['settings']['item'])
            self.assertEqual(0, block['settings']['hardness'])
            self.assertEqual('destroy', block['settings']['push_reaction'])
            self.assertEqual([{'type': PLUGIN + ':source_crop', 'crop': name}], block['behavior'])
            self.assertEqual({'age': {'type': 'int', 'range': '0~7', 'default': 0}}, block['states']['properties'])
            for age in range(8):
                app = block['states']['appearances'][block['states']['variants'][f'age={age}']['appearance']]
                self.assertEqual(f'{ART}:block/crop/{name}/stage{age}', app['model']['path'])
                self.assertNotIn('entity_renderer', app)
                self.assertNotIn('wheat', json.dumps(app))
                allocations.add(app['auto_state']['id'])
        self.assertEqual(24, len(allocations))
        self.assertEqual('PASS', manifest['harvest']['lettuce']['rightClick'])
        self.assertEqual(['MAIN_HAND', 'OFF_HAND'], manifest['harvest']['hands'])
        self.assertIn('without consuming bonemeal', manifest['harvest']['matureBonemeal'])
        self.assertEqual(5, manifest['harvest']['tomato']['rightClick']['resetAge'])
        self.assertEqual({'kaleidoscopecookery:green_chili': .2, 'kaleidoscopecookery:red_chili': .8},
                         manifest['harvest']['chili']['rightClick']['results'])
        self.assertEqual('EMPTY', manifest['harvest']['sickle']['tool'])
        self.assertEqual(0, manifest['harvest']['sickle']['resetAge'])
        self.assertEqual('NOT_TESTED', manifest['runtime'])
        self.assertTrue(any('selection outline' in row for row in manifest['open']))

    def test_break_loot_keeps_mature_conditions_fortune_formula_and_independent_bonus(self):
        for name, (result, extra, chance) in CROPS.items():
            loot = crop_loot(name)
            self.assertEqual([{'type': 'explosion_decay'}], loot['functions'])
            children = loot['pools'][0]['entries'][0]['children']
            self.assertEqual(PLUGIN + ':' + result, children[0]['item'])
            self.assertEqual([{'type': 'match_block_property', 'properties': {'age': '7'}}], children[0]['conditions'])
            self.assertEqual(PLUGIN + ':' + name + '_seed', children[1]['item'])
            bonus = loot['pools'][1]['entries'][0]['functions'][0]
            self.assertEqual({'type': 'binomial_with_bonus_count', 'extra': 3, 'probability': .5714286}, bonus['formula'])
            self.assertEqual('minecraft:fortune', bonus['enchantment'])
            if extra:
                entry = loot['pools'][2]['entries'][0]
                self.assertEqual(PLUGIN + ':' + extra, entry['item'])
                self.assertEqual([{'type': 'random', 'value': chance}], entry['conditions'])
            else:
                self.assertEqual(2, len(loot['pools']))
            path = 'src/generated/resources/data/' + ART + '/loot_tables/blocks/' + name + '_crop.json'
            self.assertEqual(json.loads(self.sources[path]), source_loot(name))

    def test_native_farmland_tag_preserves_dry_soil_under_all_source_crop_states(self):
        # In 1.21.11 FarmBlock only retains dry farmland when the block above is
        # in this native tag; a CE behavior class alone does not satisfy it.
        blocks, manifest = self.export()
        for name in CROPS:
            block = blocks[PLUGIN + ':' + name + '_crop']
            self.assertEqual(['minecraft:maintains_farmland'], block['settings']['tags'])
            self.assertEqual(8, len(block['states']['variants']))
            for variant in block['states']['variants'].values():
                self.assertNotIn('tags', variant.get('settings', {}))
        self.assertEqual('minecraft:maintains_farmland', manifest['growth']['nativeFarmlandTag'])

    def test_unknown_java_rules_cannot_silently_change_harvest_semantics(self):
        validate_sources(self.sources)
        for path in JAVA_HASHES:
            with self.subTest(path=path):
                changed = {**self.sources, path: self.sources[path] + b'\n// changed rules\n'}
                with self.assertRaisesRegex(AuditError, 'unsupported Java rules'):
                    validate_sources(changed)

    def test_changed_loot_formula_or_bonus_chance_fails_closed(self):
        for name in CROPS:
            path = 'src/generated/resources/data/' + ART + '/loot_tables/blocks/' + name + '_crop.json'
            changed = json.loads(self.sources[path])
            changed['pools'][1]['entries'][0]['functions'][0]['parameters']['extra'] = 4
            with self.assertRaisesRegex(AuditError, 'unsupported loot'):
                validate_sources({**self.sources, path: json.dumps(changed).encode()})

    def test_missing_age_wrong_stage_or_duplicate_seed_fails_closed(self):
        with patch('cookery_source_crops.read_blobs', return_value=self.sources):
            changed = copy.deepcopy(self.assets)
            path = ART + '/blockstates/tomato_crop.json'
            states = json.loads(changed[path]); del states['variants']['age=7']
            changed[path] = json.dumps(states).encode()
            with self.assertRaisesRegex(AuditError, 'unsupported states'):
                self.export(assets=changed)
            changed = copy.deepcopy(self.assets)
            path = ART + '/models/block/crop/chili/stage3.json'
            model = json.loads(changed[path]); model['textures']['cross'] = ART + ':block/crop/chili/stage2'
            changed[path] = json.dumps(model).encode()
            with self.assertRaisesRegex(AuditError, 'unsupported stage model'):
                self.export(assets=changed)
            with self.assertRaisesRegex(AuditError, 'duplicate seed'):
                self.export(items={**self.items, PLUGIN + ':tomato_seed': {}})
            missing = copy.deepcopy(self.items); del missing[PLUGIN + ':caterpillar']
            with self.assertRaisesRegex(AuditError, 'uninstalled result caterpillar'):
                self.export(items=missing)


if __name__ == '__main__':
    unittest.main()
