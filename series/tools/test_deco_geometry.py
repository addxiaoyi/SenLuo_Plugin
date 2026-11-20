import copy
import json
import unittest

from deco_geometry import merge_composite
from deco_fishrack import add_fishrack_variants


def leaf(name):
    return {'type': 'minecraft:model', 'path': 'demo:' + name}


def mesh(texture='demo:red', x=0):
    return {'textures': {'0': texture, 'alias': '#0', 'particle': '#alias'},
            'display': {'gui': {'scale': [1, 1, 1]}}, 'gui_light': 'front',
            'elements': [{'from': [x, 0, 0], 'to': [x+1, 1, 1], 'faces': {'north': {'texture': '#alias', 'uv': [0, 0, 1, 1]}}}]}


class CompositeTests(unittest.TestCase):
    def test_empty_layers_collapse_without_copying_geometry(self):
        node = {'type': 'composite', 'models': [{'type': 'empty'}, leaf('a'), {'type': 'empty'}]}
        assets = {}
        self.assertEqual(leaf('a'), merge_composite(node, assets))
        self.assertEqual({}, assets)

    def test_parent_geometry_replacement_and_texture_namespaces_are_preserved(self):
        assets = {'demo/models/base.json': json.dumps(mesh()).encode(),
                  'demo/models/child.json': json.dumps({'parent': 'demo:base', 'textures': {'0': 'demo:blue'},
                                                       'elements': mesh(x=4)['elements']}).encode()}
        original = copy.deepcopy(assets)
        result = merge_composite({'type': 'composite', 'models': [leaf('base'), leaf('child')]}, assets)
        file = result['path'].replace(':', '/models/') + '.json'
        combined = json.loads(assets[file])
        self.assertEqual([0, 4], [e['from'][0] for e in combined['elements']])
        self.assertEqual(['#p0_alias', '#p1_alias'], [e['faces']['north']['texture'] for e in combined['elements']])
        self.assertEqual('demo:red', combined['textures']['p0_alias'])
        self.assertEqual('demo:blue', combined['textures']['p1_alias'])
        self.assertEqual('demo:red', combined['textures']['particle'])
        self.assertEqual(original, {k: assets[k] for k in original})
        self.assertEqual(result, merge_composite({'type': 'composite', 'models': [leaf('base'), leaf('child')]}, assets))

    def test_different_transforms_tints_renderers_and_bad_parents_are_not_approximated(self):
        node = {'type': 'composite', 'models': [leaf('a'), leaf('b')]}
        for bad in ({**mesh(), 'display': {}}, {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'demo:a'}},
                    {'parent': 'demo:b'}, {**mesh(), 'loader': 'forge:obj'},
                    {**mesh(), 'textures': {'0': '#alias', 'alias': '#0'}}):
            assets = {'demo/models/a.json': json.dumps(mesh()).encode(), 'demo/models/b.json': json.dumps(bad).encode()}
            self.assertIsNone(merge_composite(node, assets))
        self.assertIsNone(merge_composite({'type': 'composite', 'models': [{**leaf('a'), 'tints': []}]}, {}))

    def test_fishrack_exports_all_ordered_three_slot_appearances(self):
        assets = {}
        for name in ['empty'] + [f'{fish}_{i}' for fish in ['cod', 'salmon', 'tropical'] for i in range(3)]:
            assets[f'kdeco_kaleidoscope_cookery_plus/models/fishrack/{name}.json'] = json.dumps(mesh(x=len(name))).encode()
        furniture = {f'kaleidoscope_deco:fishrack_{i}': {'settings': {'item': f'kaleidoscope_deco:fishrack_{i}', 'hit_times': 3},
                     'variants': {'ground': {'elements': [{'item': 'original'}]}}}
                     for i in range(4)}
        items = {}
        add_fishrack_variants(items, furniture, assets)
        self.assertEqual(40, len(items))
        self.assertTrue(all(len(v['variants']) == 41 for v in furniture.values()))
        for rack in furniture.values():
            self.assertEqual('kaleidoscope_deco:fishrack_0', rack['settings']['item'])
            self.assertEqual(1, rack['settings']['hit_times'])
            self.assertEqual([{'type': 'item', 'item': 'kaleidoscope_deco:fishrack_0'}], rack['loot']['pools'][0]['entries'])
        visual = items['kaleidoscope_deco:fishrack/display/cod/salmon/tropical']
        merged = json.loads(assets[visual['model']['path'].replace(':', '/models/') + '.json'])
        self.assertEqual(4, len(merged['elements']))
        self.assertEqual(visual['model']['path'], visual['legacy_model']['path'])


if __name__ == '__main__':
    unittest.main()
