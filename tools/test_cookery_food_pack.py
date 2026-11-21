"""Pin exact food_v1 strings shared by CE definitions and Java food factories."""

import copy
import json
from pathlib import Path
import unittest

from cookery_models import convert_models
from export_cookery_catalog import COMMIT, export
from export_cookery_food_pack import baked, food_items, source_assets


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / '.references' / 'KaleidoscopeCookery'
FIXTURE = ROOT / 'src/test/resources/catalog/base-food-v1.json'
FOOD_KEY = 'kaleidoscopecookery:food_v1'


class CookeryFoodPackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = export(SOURCE, COMMIT)
        cls.assets, _ = source_assets(SOURCE)
        cls.definitions, _ = convert_models(cls.assets)

    def test_exported_base_food_strings_match_the_shared_java_fixture(self):
        expected = json.loads(FIXTURE.read_text('utf-8'))
        items = food_items(self.catalog, self.assets, self.definitions)
        actual = {identifier: item['data']['pdc'][FOOD_KEY]
                  for identifier, item in items.items() if FOOD_KEY in item['data'].get('pdc', {})}
        self.assertEqual(103, len(expected))
        self.assertEqual(set(expected), set(actual))
        for identifier, encoded in expected.items():
            with self.subTest(item=identifier):
                # Parsed object equality misses PDC STRING and stackability drift.
                self.assertEqual(encoded, actual[identifier])

    def test_food_and_effect_order_and_float_spelling_ignore_input_dictionary_order(self):
        entry = copy.deepcopy(next(entry for entry in self.catalog['entries']
                                   if entry['pluginId'] == 'kaleidoscopecookery:caterpillar'))
        entry['food']['saturation'] = 0
        entry['food']['effects'][0]['probability'] = 1
        permuted = copy.deepcopy(entry)
        permuted['food'] = dict(reversed(list(permuted['food'].items())))
        permuted['food']['effects'] = [dict(reversed(list(effect.items())))
                                       for effect in permuted['food']['effects']]
        first, second = baked(entry), baked(permuted)
        encode = lambda value: json.dumps(value, ensure_ascii=False, separators=(',', ':'))
        self.assertEqual(encode(first), encode(second))
        self.assertEqual(['nutrition', 'saturation', 'alwaysEat', 'meat', 'consumeTicks', 'effects'],
                         list(first['food']))
        self.assertEqual(['id', 'duration', 'amplifier', 'probability'], list(first['food']['effects'][0]))
        self.assertIs(type(first['food']['saturation']), float)
        self.assertIs(type(first['food']['effects'][0]['probability']), float)
        self.assertIn('"saturation":0.0', encode(first))
        self.assertIn('"probability":1.0', encode(first))
        self.assertIs(type(entry['food']['saturation']), int)
        self.assertIs(type(entry['food']['effects'][0]['probability']), int)


if __name__ == '__main__':
    unittest.main()
