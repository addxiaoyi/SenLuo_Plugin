"""Guard state mapping, model dependencies and item conservation in the CE export."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml

from audit_upstream import AuditError
from export_cookery_catalog import COMMIT, export
from export_cookery_food_pack import source_assets
from cookery_placed_meals import HAM_SOURCE, ham_parts, meal_states
from export_cookery_kitchen_pack import (APPLIANCES, ART, NAMESPACE, PACK, build, source_model,
                                         source_states, write_archive)

SOURCE = Path(__file__).resolve().parents[2] / '.references' / 'KaleidoscopeCookery'


class KitchenPackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle, cls.manifest = build(SOURCE)
        cls.config = yaml.safe_load(cls.bundle[f'{PACK}/configuration/kitchen.yml'])

    def test_every_plugin_property_combination_has_an_appearance(self):
        self.assertEqual(63, len(self.config['blocks']))
        self.assertEqual(28, len(self.config['bamboo_tray_recipes']))
        for name in APPLIANCES:
            with self.subTest(name=name):
                block = self.config['blocks'][NAMESPACE + ':' + name]
                self.assertTrue(block['behavior'][0]['type'].startswith(NAMESPACE + ':'))
                expected = 4
                for values in APPLIANCES[name].values():
                    expected *= len(values)
                self.assertEqual(expected, len(block['states']['variants']))
                for selector, variant in block['states']['variants'].items():
                    self.assertEqual(set(block['states']['properties']), {p.split('=')[0] for p in selector.split(',')})
                    self.assertIn(variant['appearance'], block['states']['appearances'])

    def test_auxiliary_tools_and_farmer_wear_are_registered_for_existing_behaviors(self):
        expected = {
            'farmer_boots': 'leather_boots', 'farmer_leggings': 'leather_leggings',
            'farmer_chest_plate': 'leather_chestplate', 'straw_hat': 'leather_helmet',
            'straw_hat_flower': 'leather_helmet', 'kitchen_shovel_no_oil': 'iron_shovel',
            'kitchen_shovel_has_oil': 'iron_shovel', 'sickle': 'iron_hoe'}
        for name, material in expected.items():
            with self.subTest(item=name):
                item = self.config['items'][NAMESPACE + ':' + name]
                self.assertEqual(material, item['material'])
                self.assertEqual(1, item['data']['max_stack_size'])
        shovel = self.config['items'][NAMESPACE + ':kitchen_shovel_no_oil']
        self.assertEqual({'type': 'minecraft:model', 'path': ART + ':item/kitchen_shovel_no_oil'}, shovel['model'])
        oiled_shovel = self.config['items'][NAMESPACE + ':kitchen_shovel_has_oil']
        self.assertEqual({'type': 'minecraft:model', 'path': ART + ':item/kitchen_shovel_has_oil'},
                         oiled_shovel['model'])
        oil_model_path = f'{PACK}/resourcepack/assets/{NAMESPACE}/items/kitchen_shovel_has_oil.json'
        self.assertTrue(oil_model_path not in self.bundle)
        self.assertIn(f'{PACK}/resourcepack/assets/{ART}/models/item/kitchen_shovel_has_oil.json', self.bundle)
        self.assertEqual(NAMESPACE + ':sickle_range_harvest',
                         self.config['items'][NAMESPACE + ':sickle']['behavior']['type'])
        parity = json.loads(self.bundle[f'{PACK}/recipe-parity.json'])
        imported = {row['sourceId'] for row in parity['recipes'] if row['status'] == 'imported'}
        self.assertTrue({'kaleidoscope_cookery:sickle', 'kaleidoscope_cookery:straw_hat',
                         'kaleidoscope_cookery:straw_hat_flower'} <= imported)

    def test_tea_banner_has_stateful_block_item_and_no_duplicate_base_drop(self):
        key = NAMESPACE + ':tea_banner'
        item = self.config['items'][key]
        block = self.config['blocks'][key]
        self.assertEqual({'type': 'block_item', 'block': key}, item['behavior'])
        self.assertEqual(1, item['data']['max_stack_size'])
        self.assertEqual({'type': key}, block['behavior'])
        self.assertEqual({'pools': []}, block['loot'])
        self.assertEqual({'color', 'pattern'}, set(block['states']['properties']))
        self.assertEqual(14, block['states']['properties']['color']['default'])
        self.assertEqual(0, block['states']['properties']['pattern']['default'])
        self.assertEqual(16 * 7, len(block['states']['variants']))
        self.assertEqual('kaleidoscopecookery:block/imported/tea_banner/red_tea',
                         block['states']['appearances']['color_14_pattern_0']['model']['path'])
        self.assertEqual('kaleidoscopecookery:block/imported/tea_banner/black_piglin',
                         block['states']['appearances']['color_15_pattern_6']['model']['path'])

    def test_bamboo_tray_sources_are_accounted_as_imported(self):
        self.assertEqual(536, self.manifest['recipeStatuses']['imported'])
        self.assertEqual(76, self.manifest['recipeStatuses']['pending'])
        parity = json.loads(self.bundle[f'{PACK}/recipe-parity.json'])
        tray_records = [row for row in parity['recipes']
                        if row.get('section') == 'bamboo_tray_recipes']
        self.assertEqual(28, len(tray_records))
        self.assertTrue(all(row['status'] == 'imported' for row in tray_records))

    def test_official_plates_use_serving_states_and_runtime_controller(self):
        expected = {
            'apple_platter': (4, ['minecraft:apple']),
            'berry_platter': (4, ['minecraft:sweet_berries', 'minecraft:glow_berries']),
            'baozi_plate': (5, ['kaleidoscopecookery:baozi']),
            'chorus_fruit_platter': (5, ['minecraft:chorus_fruit']),
            'qingtuan_plate': (4, ['kaleidoscopecookery:qingtuan']),
            'shengjian_mantou_plate': (5, ['kaleidoscopecookery:shengjian_mantou']),
            'sticky_candy_plate': (4, ['kaleidoscopecookery:sticky_candy']),
            'sticky_rice_cake_plate': (5, ['kaleidoscopecookery:sticky_rice_cake']),
            'tomato_platter': (5, ['kaleidoscopecookery:tomato']),
            'watermelon_platter': (3, ['minecraft:melon_slice']),
            'zongzi_plate': (4, ['kaleidoscopecookery:zongzi']),
        }
        plates = {key for key, block in self.config['blocks'].items()
                  if isinstance(block['behavior'], list) and block['behavior'][0]['type'] == NAMESPACE + ':plate'}
        self.assertEqual({NAMESPACE + ':' + name for name in expected}, plates)
        for name, (maximum, servings) in expected.items():
            with self.subTest(name=name):
                block = self.config['blocks'][NAMESPACE + ':' + name]
                self.assertEqual(NAMESPACE + ':plate', block['behavior'][0]['type'])
                self.assertEqual(maximum + 1, len(block['states']['variants']) // 4)
                self.assertEqual(maximum, block['behavior'][0]['max_servings'])
                self.assertEqual(servings, block['behavior'][0]['serving_items'])
                self.assertEqual({'facing', 'servings'}, set(block['states']['properties']))

    def test_placed_plates_start_with_all_official_portions(self):
        for key, block in self.config['blocks'].items():
            if not isinstance(block['behavior'], list) or block['behavior'][0]['type'] != NAMESPACE + ':plate':
                continue
            with self.subTest(plate=key):
                maximum = block['behavior'][0]['max_servings']
                self.assertEqual(maximum, block['states']['properties']['servings']['default'])
                self.assertIn(f'facing=north,servings={maximum}', block['states']['variants'])
                self.assertIn('facing=north,servings=0', block['states']['variants'])

    def test_plate_removal_has_one_owner_for_portions_and_bowls(self):
        for key, block in self.config['blocks'].items():
            if not isinstance(block['behavior'], list) or block['behavior'][0]['type'] != NAMESPACE + ':plate':
                continue
            with self.subTest(plate=key):
                # A base item drop would recreate a full plate after the controller returns its remaining food.
                self.assertEqual({'pools': []}, block['loot'])
                self.assertEqual(['minecraft:bowl'], block['behavior'][0]['drops'])

    def test_plate_manifest_preserves_every_catalog_portion_and_drop(self):
        catalog = export(SOURCE, COMMIT)
        expected = {entry['pluginId']: {field: entry['plate'][field] for field in ('maxServings', 'servings', 'drops')}
                    for entry in catalog['entries'] if entry.get('plate')}
        self.assertEqual(11, len(expected))
        self.assertEqual(expected, self.manifest.get('plates'))
        for key, plate in expected.items():
            with self.subTest(plate=key):
                behavior = self.config['blocks'][key]['behavior'][0]
                self.assertEqual(plate['maxServings'], behavior['max_servings'])
                self.assertEqual(plate['servings'], behavior['serving_items'])
                self.assertEqual(plate['drops'], behavior['drops'])

    def test_missing_plate_blockstate_rejects_the_pack(self):
        assets, licenses = source_assets(SOURCE)
        del assets[f'{ART}/blockstates/apple_platter.json']
        with patch('export_cookery_kitchen_pack.source_assets', return_value=(assets, licenses)):
            with self.assertRaisesRegex(AuditError, 'missing plate blockstate: apple_platter'):
                build(SOURCE)

    def test_all_catalogued_placed_meals_are_placeable_with_one_loot_owner(self):
        catalog = export(SOURCE, COMMIT)
        meals = {entry['pluginId']: entry for entry in catalog['entries'] if entry.get('bites')}
        self.assertEqual(38, len(meals))
        self.assertEqual(set(meals), set(self.manifest.get('placedMeals', {})))
        counts = {'1x1': 0, '1x2': 0, '3x3': 0}
        for key, entry in meals.items():
            with self.subTest(meal=key):
                bite = entry['bites']
                counts[bite['layout']] += 1
                block = self.config['blocks'][key]
                behavior = block['behavior'][0]
                self.assertEqual(NAMESPACE + ':placed_meal', behavior['type'])
                self.assertEqual(key, behavior['food_id'])
                self.assertEqual(bite['maxBites'], behavior['max_bites'])
                self.assertEqual(bite['layout'], behavior['layout'])
                self.assertEqual(bite['drops'], behavior['drops'])
                self.assertEqual(bite['animation'], behavior['animation'])
                self.assertEqual({'pools': []}, block['loot'])
                self.assertEqual({'type': NAMESPACE + ':placed_meal_item', 'block': key},
                                 self.config['items'][key]['behavior'])
                properties = block['states']['properties']
                self.assertNotIn('quality', properties)
                self.assertEqual(0, properties['bites']['default'])
                cells = {'1x1': 1, '1x2': 2, '3x3': 9}[bite['layout']]
                self.assertEqual(cells * 4 * (bite['maxBites'] + 1), len(block['states']['variants']))
                self.assertEqual(cells, self.manifest['placedMeals'][key]['cells'])
        self.assertEqual({'1x1': 34, '1x2': 3, '3x3': 1}, counts)

    def test_cold_cut_ham_has_nine_real_mesh_stages_only_on_the_center(self):
        key = NAMESPACE + ':cold_cut_ham_slices'
        self.assertIn(key, self.config['blocks'])
        block = self.config['blocks'][key]
        self.assertEqual('center', block['states']['properties']['part']['default'])
        self.assertEqual(9, len(block['states']['properties']['part']['values']))
        for bites in range(9):
            with self.subTest(bites=bites):
                variant = block['states']['variants'][f'facing=south,bites={bites},part=center']
                renderer = block['states']['appearances'][variant['appearance']]['entity_renderer']
                identifier = self.config['items'][renderer['item']]['model']['path']
                namespace, model = identifier.split(':', 1)
                mesh = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/models/{model}.json'])
                self.assertNotIn('display', mesh)
                self.assertNotIn('loader', mesh)
                visible = {'base'} | {f'bite{number}' for number in range(1, 9) if bites < 9 - number}
                self.assertEqual(visible, {cube['name'] for cube in mesh['elements']})
                expected_cubes = 2 + sum(2 if number % 2 else 3 for number in range(1, 9) if bites < 9 - number)
                self.assertEqual(expected_cubes, len(mesh['elements']))
                side = block['states']['variants'][f'facing=south,bites={bites},part=left_up']
                appearance = block['states']['appearances'][side['appearance']]
                self.assertIn('auto_state', appearance)
                self.assertNotIn('entity_renderer', appearance)

    def test_placed_meal_import_rejects_missing_states_and_quality_visual_changes(self):
        path = ART + '/blockstates/test_dish.json'
        bite = {'maxBites': 1, 'layout': '1x1'}
        with self.assertRaisesRegex(AuditError, 'missing placed meal asset'):
            meal_states({}, 'test_dish', bite)
        variants = {f'facing={facing},bites={bites},quality={quality}': {'model': ART + f':block/test_{bites}'}
                    for facing in ('north', 'east', 'south', 'west') for bites in range(2) for quality in range(5)}
        assets = {path: json.dumps({'variants': variants}).encode()}
        collapsed, count = meal_states(assets, 'test_dish', bite)
        self.assertEqual(40, count)
        self.assertEqual(8, len(collapsed))
        changed = copy.deepcopy(variants)
        changed['facing=north,bites=0,quality=0']['model'] = ART + ':block/quality_specific'
        with self.assertRaisesRegex(AuditError, 'quality changes placed meal visual'):
            meal_states({path: json.dumps({'variants': changed}).encode()}, 'test_dish', bite)
        del variants['facing=north,bites=0,quality=0']
        with self.assertRaisesRegex(AuditError, 'incomplete placed meal states'):
            meal_states({path: json.dumps({'variants': variants}).encode()}, 'test_dish', bite)

    def test_cold_cut_ham_mesh_is_from_the_java_renderer_and_locked_to_source(self):
        key = NAMESPACE + ':cold_cut_ham_slices'
        provenance = self.manifest['placedMeals'][key]['javaModel']
        self.assertEqual(HAM_SOURCE, provenance['path'])
        self.assertEqual(22, provenance['sourceCubes'])
        first = provenance['stageModels'][0]
        namespace, model = first.split(':', 1)
        mesh = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/models/{model}.json'])
        self.assertEqual([-12, 0, -12], mesh['elements'][1]['from'])
        self.assertEqual([28, 2, 28], mesh['elements'][1]['to'])
        # This differs from the inventory JSON geometry; source Java parent pivots must not be skipped.
        first_bite = mesh['elements'][2]
        self.assertEqual([0, 3, 17], first_bite['from'])
        self.assertEqual([9, 5, 24], first_bite['to'])
        self.assertEqual({'origin': [3, 4, 21], 'axis': 'z', 'angle': 22.5, 'rescale': False}, first_bite['rotation'])
        self.assertTrue(all(len(cube['faces']) == 6 for cube in mesh['elements']))
        with self.assertRaisesRegex(AuditError, 'Java mesh changed'):
            ham_parts(b'changed model')

    def test_cold_cut_ham_recipe_keeps_all_eight_meats_and_bowl(self):
        recipe = self.config['recipes'][NAMESPACE + ':upstream/kaleidoscope_cookery/cold_cut_ham_slices']
        self.assertEqual('shapeless', recipe['type'])
        self.assertEqual([NAMESPACE + ':cooked_pork_belly'] * 8 + ['minecraft:bowl'], recipe['ingredients'])
        self.assertEqual({'id': NAMESPACE + ':cold_cut_ham_slices', 'count': 1}, recipe['result'])

    def test_basin_visual_levels_keep_old_plugin_oil_semantics(self):
        block = self.config['blocks'][NAMESPACE + ':enamel_basin']['states']
        for level, expected in enumerate(('base', 'empty', 'low', 'middle', 'high')):
            variant = block['variants'][f'facing=north,oil_level={level}']
            display = block['appearances'][variant['appearance']]['entity_renderer']['item']
            self.assertEqual(ART + ':block/enamel_basin/' + expected, self.config['items'][display]['model']['path'])

    def test_steamer_maps_slab_type_and_preserves_double_drop(self):
        block = self.config['blocks'][NAMESPACE + ':steamer']
        for slab, suffix, lift in (('bottom', 'half', '0,0,0'), ('top', 'half', '0,0.5,0'), ('double', 'full', '0,0,0')):
            variant = block['states']['variants'][f'facing=north,type={slab},has_base=false,has_lid=false']
            renderer = block['states']['appearances'][variant['appearance']]['entity_renderer']
            self.assertEqual(lift, renderer['translation'])
            self.assertEqual(ART + ':block/steamer_' + suffix, self.config['items'][renderer['item']]['model']['path'])
        self.assertEqual('double', block['behavior'][1]['extra_drops'][0]['value'])

    def test_filled_containers_and_double_block_do_not_duplicate_base_drops(self):
        for name in ('teapot', 'oil_pot'):
            self.assertEqual([], self.config['blocks'][NAMESPACE + ':' + name]['loot']['pools'])
        spit = self.config['blocks'][NAMESPACE + ':shawarma_spit']
        self.assertIn({'type': 'double_high_block'}, spit['behavior'])
        self.assertEqual({'half': 'lower'}, spit['loot']['pools'][0]['conditions'][0]['properties'])
        self.assertEqual('double_high_block_item', self.config['items'][NAMESPACE + ':shawarma_spit']['behavior']['type'])

    def test_teapot_has_separate_parts_and_preserves_gui_perspective(self):
        node = self.config['items'][NAMESPACE + ':teapot']['model']
        self.assertEqual('minecraft:display_context', node['property'])
        self.assertEqual({'fixed', 'gui', 'ground'}, {case['when'][0] for case in node['cases']})
        parts = []
        for name in ('teapot_1', 'teapot_2'):
            identifier = self.config['items']['show:' + name]['model']['path']
            namespace, path = identifier.split(':', 1)
            model = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/models/{path}.json'])
            self.assertNotIn('display', model)
            parts.append(len(model['elements']))
        self.assertEqual([2, 10], parts)

    def test_model_loaders_and_sound_files_are_resolved(self):
        for path, contents in self.bundle.items():
            if '/models/' in path and path.endswith('.json'):
                self.assertNotIn('loader', json.loads(contents), path)
        sounds = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{NAMESPACE}/sounds.json'])
        self.assertEqual(18, len(sounds))
        for event in sounds.values():
            namespace, path = event['sounds'][0].split(':', 1)
            self.assertIn(f'{PACK}/resourcepack/assets/{namespace}/sounds/{path}.ogg', self.bundle)

    def test_millstone_is_furniture_with_all_twelve_source_cubes(self):
        key = NAMESPACE + ':millstone'
        self.assertNotIn(key, self.config['blocks'])
        furniture = self.config['furniture'][key]
        self.assertEqual(1, self.manifest['furniture'])
        self.assertEqual(key, furniture['behavior'][0]['type'])
        self.assertEqual('furniture_item', self.config['items'][key]['behavior']['type'])
        counts = []
        for name in ('millstone_base', 'new_millstone_stick', 'new_millstone_stick2', 'new_millstone_stone'):
            ref = self.config['items']['show:' + name]['model']['path']
            namespace, path = ref.split(':', 1)
            model = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/models/{path}.json'])
            counts.append(len(model['elements']))
            for cube in model['elements']:
                self.assertEqual(6, len(cube['faces']))
                self.assertTrue(all(-16 <= v <= 32 for corner in ('from', 'to') for v in cube[corner]))
        self.assertEqual([6, 4, 1, 1], counts)
        provenance = self.manifest['millstoneModel']
        self.assertEqual(self.manifest['sourceCommit'], provenance['sourceCommit'])
        self.assertEqual(64, len(provenance['sha256']))
        self.assertEqual('NOT_TESTED', provenance['clientVisual'])

    def test_unknown_source_state_fails_instead_of_dropping_variants(self):
        source = {'variants': {'facing=north,unknown=true': {'model': ART + ':block/pot'}}}
        with patch('export_cookery_kitchen_pack.read_json', return_value=source), self.assertRaisesRegex(AuditError, 'dimensions'):
            source_states({}, 'pot')
        with self.assertRaisesRegex(AuditError, 'expected one model'):
            source_model('stove', {'facing': 'north', 'lit': 'true'}, [])

    def test_manifest_and_zip_are_reproducible_and_refuse_overwrite(self):
        for path, digest in self.manifest['files'].items():
            self.assertEqual(digest, hashlib.sha256(self.bundle[path]).hexdigest())
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / 'one.zip', Path(directory) / 'two.zip'
            write_archive(first, self.bundle)
            write_archive(second, self.bundle)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            before = first.read_bytes()
            with self.assertRaises(FileExistsError):
                write_archive(first, {'wrong': b'changed'})
            self.assertEqual(before, first.read_bytes())


if __name__ == '__main__':
    unittest.main()
