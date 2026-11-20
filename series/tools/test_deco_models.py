import copy
import json
from types import SimpleNamespace
import unittest

from deco_interactions import selected_model, inventory_legacy_model
from import_sources import deco, model_tree


def leaf(name):
    return {'type': 'minecraft:model', 'model': 'demo:' + name}


def meal_tree():
    return {'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui', 'ground', 'firstperson_lefthand', 'firstperson_righthand',
                                'thirdperson_lefthand', 'thirdperson_righthand'], 'model': leaf('inventory')}],
            'fallback': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data',
                         'index': 1, 'scale': 2, 'fallback': leaf('empty'),
                         'entries': [{'threshold': 4, 'model': leaf('two')},
                                     {'threshold': 2, 'model': leaf('one')}]}}


class DecoModelTests(unittest.TestCase):
    def test_vanilla_normalized_tints_do_not_become_ce_byte_colors(self):
        node = {**leaf('sheep'), 'tints': [{'type': 'custom_model_data', 'default': [1, 1, 1], 'index': 0},
                                        {'type': 'constant', 'value': [1e-8, 0, .5]},
                                        {'type': 'constant', 'value': 0xffffff}]}
        result = model_tree(node, {'demo'})
        self.assertEqual(['1.0', '1.0', '1.0'], result['tints'][0]['default'])
        self.assertEqual(['0.00000001', '0.0', '0.5'], result['tints'][1]['value'])
        self.assertEqual(0xffffff, result['tints'][2]['value'])
        self.assertEqual([1, 1, 1], node['tints'][0]['default'])
        for value in ([255, 255, 255], [True, 0, 0], [1, 1], [float('nan'), 0, 0]):
            with self.assertRaisesRegex(ValueError, 'tint'):
                model_tree({**leaf('bad'), 'tints': [{'type': 'constant', 'value': value}]}, set())

    def test_nested_float_branch_is_resolved_before_ce_assigns_its_own_components(self):
        original = meal_tree()
        saved = copy.deepcopy(original)
        result = selected_model(original, [], [], [99, 1])
        self.assertEqual(leaf('one'), result['fallback'])
        self.assertEqual(leaf('inventory'), result['cases'][0]['model'])
        self.assertEqual(leaf('one'), selected_model(original, [], [], [99, 1], context='none'))
        self.assertEqual(saved, original)
        result['fallback']['model'] = 'demo:changed'
        self.assertEqual(saved, original)

    def test_float_threshold_equality_scale_default_and_below_range(self):
        node = meal_tree()['fallback']
        for values, expected in [([], 'empty'), ([99, -.5], 'empty'), ([99, .999], 'empty'),
                                 ([99, 1], 'one'), ([99, 1.999], 'one'), ([99, 2], 'two'), ([99, 20], 'two')]:
            self.assertEqual(leaf(expected), selected_model(node, [], [], values))

    def test_composite_and_unknown_live_conditions_keep_structure_and_tints(self):
        colored = {**leaf('red'), 'tints': [{'type': 'constant', 'value': 123}]}
        nested = {'type': 'select', 'property': 'custom_model_data', 'index': 0,
                  'cases': [{'when': 'red', 'model': colored}], 'fallback': leaf('blue')}
        model = {'type': 'composite', 'models': [{'type': 'condition', 'property': 'using_item',
                  'on_true': nested, 'on_false': leaf('idle')}]}
        resolved = selected_model(model, ['red'], [])
        self.assertEqual('using_item', resolved['models'][0]['property'])
        self.assertEqual(colored, resolved['models'][0]['on_true'])
        self.assertEqual(leaf('idle'), resolved['models'][0]['on_false'])

    def test_invalid_indices_numbers_and_duplicate_thresholds_do_not_silently_fallback(self):
        for index in [-1, True, .5]:
            node = meal_tree()['fallback'];node['index'] = index
            with self.assertRaisesRegex(ValueError, 'index'): selected_model(node, [], [], [1])
        for value in [True, float('nan'), float('inf'), 1e100]:
            with self.assertRaises(ValueError): selected_model(meal_tree()['fallback'], [], [], [0, value])
        node = meal_tree()['fallback'];node['entries'].append(node['entries'][0])
        with self.assertRaisesRegex(ValueError, 'Duplicate'): selected_model(node, [], [], [0, 1])

    def test_legacy_inventory_only_emits_when_all_six_contexts_agree(self):
        node = {'type': 'minecraft:select', 'property': 'minecraft:display_context',
                'cases': [{'when': 'none', 'model': {'type': 'minecraft:model', 'path': 'demo:display'}}],
                'fallback': {'type': 'minecraft:model', 'path': 'demo:inventory'}}
        self.assertEqual({'path': 'demo:inventory'}, inventory_legacy_model(node))
        node['cases'].append({'when': 'gui', 'model': {'type': 'minecraft:model', 'path': 'demo:gui'}})
        self.assertIsNone(inventory_legacy_model(node))
        self.assertIsNone(inventory_legacy_model({'type': 'minecraft:model', 'path': 'demo:a', 'tints': []}))

    def test_source_float_state_becomes_display_item_and_keeps_drop_identity(self):
        tree = meal_tree();tree['fallback']['index'] = 0;tree['fallback']['scale'] = 1
        tree['fallback']['entries'] = [{'threshold': 0, 'model': leaf('empty')}, {'threshold': 1, 'model': leaf('one')}]
        source = SimpleNamespace(files={
            'src/cookery/assets/demo/items/plate.json': json.dumps({'model': tree}).encode(),
            'src/cookery/data/dc/function/plate.mcfunction': b'data modify storage dc:index input.plate_1 set value '
                b'{item:{components:{"minecraft:item_model":"demo:plate","minecraft:custom_model_data":{floats:[1.0f]}}}}',
        })
        catalog, config, _, pending = deco(source)
        item = config['items']['kaleidoscope_deco:plate_1']
        self.assertEqual({'path': 'kdeco_demo:inventory'}, item['legacy_model'])
        visual = config['items']['kaleidoscope_deco:plate_1/ce_display']
        self.assertEqual({'type': 'minecraft:model', 'path': 'kdeco_demo:one'}, visual['model'])
        furniture = config['furniture']['kaleidoscope_deco:plate_1']
        self.assertEqual('kaleidoscope_deco:plate_1', furniture['settings']['item'])
        self.assertEqual('kaleidoscope_deco:plate_1/ce_display', furniture['variants']['ground']['elements'][0]['item'])
        self.assertEqual('kaleidoscope_deco:plate_1/ce_display', furniture['variants']['kaleidoscope_deco/plate_1']['elements'][0]['item'])
        self.assertEqual(['kaleidoscope_deco:plate_1'], [entry['id'] for entry in catalog['entries']])
        self.assertFalse(any('Component-selected' in text for text in pending))

    def test_snbt_byte_flags_select_nested_model_without_dropping_furniture(self):
        source = SimpleNamespace(files={
            'src/cookery/assets/demo/items/pot.json': json.dumps({'model': {
                'type': 'condition', 'property': 'custom_model_data', 'on_true': leaf('closed'), 'on_false': leaf('open')
            }}).encode(),
            'src/cookery/data/dc/function/pot.mcfunction': b'data modify storage dc:index input.pot set value '
                b'{item:{components:{"minecraft:item_model":"demo:pot","minecraft:custom_model_data":{flags:[1b]}}}}',
        })
        catalog, config, _, _ = deco(source)
        self.assertEqual(1, len(catalog['entries']))
        self.assertEqual('kdeco_demo:closed', config['items']['kaleidoscope_deco:pot']['model']['path'])


if __name__ == '__main__':
    unittest.main()
