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
        self.assertEqual(81, len(self.config['blocks']))
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

    def test_lunch_bag_has_real_storage_and_consumption_forms_and_source_recipe(self):
        key = NAMESPACE + ':transmutation_lunch_bag'
        bag, eating = self.config['items'][key], self.config['items'][key + '_eating']
        self.assertEqual('bundle', bag['material'])
        self.assertEqual({'type': key}, bag['behavior'])
        self.assertEqual([], bag['data']['components']['minecraft:bundle_contents'])
        self.assertEqual(1, bag['data']['max_stack_size'])
        self.assertEqual('minecraft:bundle/fullness', bag['model']['property'])
        self.assertTrue(bag['model']['fallback']['path'].endswith('transmutation_lunch_bag_no_items'))
        self.assertTrue(bag['model']['entries'][0]['model']['path'].endswith('transmutation_lunch_bag_has_items'))
        self.assertEqual('paper', eating['material'])
        self.assertEqual(1, eating['data']['max_stack_size'])
        self.assertEqual(1.6, eating['data']['components']['minecraft:consumable']['consume_seconds'])
        self.assertEqual(0, eating['data']['food']['nutrition'])
        self.assertTrue(eating['data']['food']['can_always_eat'])
        recipe = self.config['recipes'][NAMESPACE + ':upstream/' + ART + '/transmutation_lunch_bag']
        self.assertEqual([' L ', 'LSL', 'LLL'], recipe['pattern'])
        self.assertEqual({'L': 'minecraft:leather', 'S': 'minecraft:amethyst_shard'}, recipe['ingredients'])

    def test_storage_blocks_have_source_physics_axes_and_reversible_recipes(self):
        oil = self.config['blocks'][NAMESPACE + ':oil_block']
        straw = self.config['blocks'][NAMESPACE + ':straw_block']
        self.assertEqual(.985, oil['settings']['friction'])
        self.assertEqual(0, oil['settings']['hardness'])
        self.assertEqual(['minecraft:infiniburn_' + d for d in ('overworld', 'nether', 'end')], oil['settings']['tags'])
        self.assertEqual([{'type': NAMESPACE + ':oil_block'}], oil['behavior'])
        self.assertEqual([{'type': NAMESPACE + ':straw_block'}], straw['behavior'])
        self.assertEqual({'axis': {'type': 'axis', 'default': 'y'}}, straw['states']['properties'])
        self.assertEqual({'axis=x', 'axis=y', 'axis=z'}, set(straw['states']['variants']))
        for axis in ('x', 'y', 'z'):
            app = straw['states']['appearances'][straw['states']['variants']['axis=' + axis]['appearance']]
            self.assertEqual('note_block', app['auto_state'])
            self.assertEqual(0 if axis == 'y' else 90, app['model'].get('x', 0))
            self.assertEqual(90 if axis == 'x' else 0, app['model'].get('y', 0))
        for raw, packed in [('oil', 'oil_block'), ('rice_panicle', 'straw_block')]:
            base = NAMESPACE + ':upstream/' + ART + '/'
            compression = self.config['recipes'][base + packed]
            expansion = self.config['recipes'][base + raw]
            self.assertEqual({'id': NAMESPACE + ':' + packed, 'count': 1}, compression['result'])
            self.assertEqual({'id': NAMESPACE + ':' + raw, 'count': 9}, expansion['result'])
            self.assertEqual(9, sum(len(row.strip()) for row in compression['pattern']))
        self.assertEqual('NOT_TESTED', self.manifest['storageBlocks']['runtime'])

    def test_hanging_strings_preserve_stages_harvest_loot_and_recipes(self):
        for name, harvest in [('chili_ristra', NAMESPACE + ':red_chili'),
                              ('strung_mushrooms', 'minecraft:brown_mushroom')]:
            key = NAMESPACE + ':' + name
            block = self.config['blocks'][key]
            self.assertEqual({'type': NAMESPACE + ':hanging_string', 'harvest_item': harvest,
                              'harvest_amount': 3}, block['behavior'][0])
            self.assertEqual('destroy', block['settings']['push_reaction'])
            self.assertEqual(0, block['settings']['hardness'])
            self.assertEqual(4, len(block['states']['variants']))
            for head in ('false', 'true'):
                for sheared in ('false', 'true'):
                    app = block['states']['appearances'][block['states']['variants'][
                        f'is_head={head},sheared={sheared}']['appearance']]
                    model_item = self.config['items'][app['entity_renderer']['item']]
                    self.assertEqual(ART + ':block/' + name + '/' + ('head' if head == 'true' else 'body')
                                     + ('_sheared' if sheared == 'true' else ''), model_item['model']['path'])
            for sheared, count in [('false', 6), ('true', 3)]:
                matches = [p for p in block['loot']['pools']
                           if {'type': 'match_block_property', 'properties': {'sheared': sheared}} in p['conditions']]
                self.assertEqual(1, len(matches))
                self.assertIn({'type': 'survives_explosion'}, matches[0]['conditions'])
                self.assertEqual([{'type': 'item', 'item': harvest, 'functions': [{'type': 'set_count', 'count': count}]}],
                                 matches[0]['entries'])
            self.assertEqual({'id': key, 'count': 1}, self.config['recipes'][NAMESPACE + ':upstream/' + ART + '/' + name]['result'])

    def test_hanging_strings_reject_unhandled_source_visual_state(self):
        from cookery_hanging_strings import hanging_strings
        assets, _ = source_assets(SOURCE)
        states = json.loads(assets[ART + '/blockstates/chili_ristra.json'])
        states['variants']['is_head=true,sheared=true']['y'] = 90
        assets[ART + '/blockstates/chili_ristra.json'] = json.dumps(states).encode()
        with self.assertRaisesRegex(AuditError, 'unsupported source states'):
            hanging_strings(SOURCE, assets, {}, {}, {}, None)

    def test_storage_blocks_reject_unhandled_source_axis_rotation(self):
        from cookery_storage_blocks import storage_blocks
        from cookery_models import convert_models
        assets, _ = source_assets(SOURCE)
        definitions, _ = convert_models(assets)
        states = json.loads(assets[ART + '/blockstates/oil_block.json'])
        states['variants']['']['y'] = 90
        assets[ART + '/blockstates/oil_block.json'] = json.dumps(states).encode()
        with self.assertRaisesRegex(AuditError, 'unsupported source states'):
            storage_blocks(SOURCE, assets, {}, definitions, {})

    def test_all_tables_preserve_connections_carpet_models_and_recipes(self):
        from cookery_seating import WOODS, COLORS
        for wood in WOODS:
            key = NAMESPACE + ':table_' + wood
            table = self.config['blocks'][key]
            self.assertEqual([{'type': NAMESPACE + ':table'}], table['behavior'])
            self.assertEqual({'type': 'block_item', 'block': key}, self.config['items'][key]['behavior'])
            self.assertEqual(key, table['loot']['pools'][0]['entries'][0]['item'])
            self.assertEqual(8, len(table['states']['variants']))
            self.assertEqual('0~1', table['states']['properties']['line']['range'])
            self.assertEqual('0~3', table['states']['properties']['position']['range'])
            for line, axis in enumerate(('x', 'z')):
                for pos, shape in enumerate(('single', 'right', 'middle', 'left')):
                    variant = table['states']['variants'][f'line={line},position={pos}']
                    appearance = table['states']['appearances'][variant['appearance']]
                    model = self.config['items'][appearance['entity_renderer']['item']]['model']['path']
                    expected = ART + ':block/table/' + wood + '_' + shape
                    if axis == 'z' and pos: expected += '_rot'
                    self.assertEqual(expected, model)
            recipe = self.config['recipes'][NAMESPACE + ':upstream/' + ART + '/table_' + wood]
            self.assertEqual({'id': key, 'count': 1}, recipe['result'])
        for color in COLORS:
            for shape in ('single', 'left', 'middle', 'right'):
                model = self.config['items'][f'show:table_carpet_{color}_{shape}']['model']['path']
                self.assertEqual(f'{ART}:block/carpet/table/{color}_{shape}', model)
        self.assertEqual(11, self.manifest['tables']['tables'])
        self.assertEqual('NOT_TESTED', self.manifest['tables']['runtime'])

    def test_long_bench_preserves_horizontal_axis_source_end_signs_loot_and_recipe(self):
        key = NAMESPACE + ':long_bench'
        bench = self.config['blocks'][key]
        self.assertEqual([{'type': key}], bench['behavior'])
        self.assertEqual({'type': 'axis', 'values': ['x', 'z'], 'default': 'x'}, bench['states']['properties']['axis'])
        self.assertEqual(8, len(bench['states']['variants']))
        for axis in ('x', 'z'):
            for position, shape in enumerate(('single', 'left', 'middle', 'right')):
                app = bench['states']['variants'][f'axis={axis},position={position}']['appearance']
                renderer = bench['states']['appearances'][app]['entity_renderer']
                self.assertEqual(0 if axis == 'x' else 90, renderer['yaw'])
                self.assertEqual(ART + ':block/long_bench/' + shape,
                                 self.config['items'][renderer['item']]['model']['path'])
        pool = bench['loot']['pools'][0]
        self.assertEqual([{'type': 'survives_explosion'}], pool['conditions'])
        self.assertEqual([{'type': 'item', 'item': key}], pool['entries'])
        self.assertEqual({'id': key, 'count': 1}, self.config['recipes'][NAMESPACE + ':upstream/' + ART + '/long_bench']['result'])
        self.assertEqual('NOT_TESTED', self.manifest['longBench']['runtime'])

    def test_long_bench_rejects_changed_rotation_and_new_source_axes(self):
        from cookery_long_bench import long_bench
        for selector in ('axis=z,position=1', 'axis=y,position=0'):
            assets, _ = source_assets(SOURCE)
            states = json.loads(assets[ART + '/blockstates/long_bench.json'])
            states['variants'][selector] = {'model': ART + ':block/long_bench/left', 'y': 270}
            assets[ART + '/blockstates/long_bench.json'] = json.dumps(states).encode()
            with self.assertRaisesRegex(AuditError, 'unsupported source states'):
                long_bench(SOURCE, assets, {}, {}, {}, None)

    def test_eight_immortals_table_exports_all_parts_with_transactional_item_and_source_loot(self):
        key = NAMESPACE + ':eight_immortals_table'
        table = self.config['blocks'][key]
        self.assertEqual({'type': NAMESPACE + ':eight_immortals_table_item', 'block': key}, self.config['items'][key]['behavior'])
        self.assertEqual([{'type': key}], table['behavior'])
        self.assertEqual('block', table['settings']['push_reaction'])
        self.assertEqual('minecraft:block.bamboo_wood.break', table['settings']['sounds']['break'])
        self.assertEqual('right_bottom', table['states']['properties']['part']['default'])
        self.assertEqual(16, len(table['states']['variants']))
        for facing, yaw in [('north', 180), ('east', 270), ('south', 0), ('west', 90)]:
            for part in ('right_bottom', 'left_bottom', 'right_top', 'left_top'):
                app = table['states']['variants'][f'facing={facing},part={part}']['appearance']
                renderer = table['states']['appearances'][app]['entity_renderer']
                self.assertEqual(yaw, renderer['yaw'])
                self.assertEqual(ART + ':block/eight_immortals_table/' + part, self.config['items'][renderer['item']]['model']['path'])
        self.assertEqual([{'type': 'survives_explosion'}], table['loot']['pools'][0]['conditions'])
        self.assertEqual([{'type': 'item', 'item': key}], table['loot']['pools'][0]['entries'])
        self.assertEqual({'id': key, 'count': 1}, self.config['recipes'][NAMESPACE + ':upstream/' + ART + '/eight_immortals_table']['result'])
        self.assertEqual('NOT_TESTED', self.manifest['eightImmortalsTable']['runtime'])

    def test_eight_immortals_table_rejects_missing_part_and_changed_source_loot(self):
        from cookery_eight_immortals_table import eight_immortals_table
        assets, _ = source_assets(SOURCE)
        states = json.loads(assets[ART + '/blockstates/eight_immortals_table.json'])
        del states['variants']['facing=north,part=left_top']
        assets[ART + '/blockstates/eight_immortals_table.json'] = json.dumps(states).encode()
        with self.assertRaisesRegex(AuditError, 'unsupported source states'):
            eight_immortals_table(SOURCE, assets, {}, {}, {}, None)
        assets, _ = source_assets(SOURCE)
        from cookery_eight_immortals_table import SOURCES
        with patch('cookery_eight_immortals_table.read_blobs', return_value={SOURCES[-1]: b'{}'}):
            with self.assertRaisesRegex(AuditError, 'unsupported source loot'):
                eight_immortals_table(SOURCE, assets, {}, {}, {}, None)

    def test_red_lantern_has_source_light_sound_model_loot_and_versioned_chain_recipe(self):
        key = NAMESPACE + ':red_lantern'
        block = self.config['blocks'][key]
        self.assertEqual(14, block['settings']['luminance'])
        self.assertEqual(.3, block['settings']['hardness'])
        self.assertEqual(.3, block['settings']['resistance'])
        self.assertEqual('normal', block['settings']['push_reaction'])
        self.assertEqual('minecraft:block.wool.break', block['settings']['sounds']['break'])
        self.assertEqual({}, block['states']['properties'])
        self.assertEqual([{'type': 'survives_explosion'}], block['loot']['pools'][0]['conditions'])
        self.assertEqual([{'type': 'item', 'item': key}], block['loot']['pools'][0]['entries'])
        renderer = block['states']['appearances']['default']['entity_renderer']
        self.assertEqual(ART + ':block/red_lantern', self.config['items'][renderer['item']]['model']['path'])
        recipe_id = NAMESPACE + ':upstream/' + ART + '/red_lantern'
        recipe = self.config['recipes'][recipe_id]
        self.assertEqual(['PCP', 'DLD', 'PDP'], recipe['pattern'])
        self.assertEqual({'id': key, 'count': 1}, recipe['result'])
        self.assertEqual('minecraft:iron_chain', recipe['ingredients']['C'])
        self.assertEqual('NOT_TESTED', self.manifest['redLantern']['runtime'])

    def test_red_lantern_rejects_state_and_loot_changes(self):
        from cookery_red_lantern import red_lantern, SOURCES
        assets, _ = source_assets(SOURCE)
        path = ART + '/blockstates/red_lantern.json'
        original = assets[path]
        assets[path] = json.dumps({'variants': {'hanging=true': {'model': ART + ':block/red_lantern'}}}).encode()
        with self.assertRaisesRegex(AuditError, 'unsupported source states'):
            red_lantern(SOURCE, assets, {}, {}, {}, None)
        assets[path] = original
        with patch('cookery_red_lantern.read_blobs', return_value={SOURCES[-1]: b'{}'}):
            with self.assertRaisesRegex(AuditError, 'unsupported source loot'):
                red_lantern(SOURCE, assets, {}, {}, {}, None)

    def test_table_export_rejects_unhandled_source_geometry(self):
        from cookery_tables import table_blocks
        from export_cookery_kitchen_pack import display
        from cookery_models import convert_models
        assets, _ = source_assets(SOURCE)
        definitions, _ = convert_models(assets)
        state_key = ART + '/blockstates/table_oak.json'
        states = json.loads(assets[state_key])
        first = next(iter(states['variants']))
        states['variants'][first]['y'] = 90
        assets[state_key] = json.dumps(states).encode()
        with self.assertRaisesRegex(AuditError, 'unsupported source geometry'):
            table_blocks(SOURCE, assets, {}, definitions, {}, display)

    def test_all_source_chairs_and_stools_have_seats_loot_and_crafting(self):
        from cookery_seating import WOODS, COLORS
        for family in ('chair', 'cook_stool'):
            for wood in WOODS:
                name = family + '_' + wood
                key = NAMESPACE + ':' + name
                furniture = self.config['furniture'][key]
                item = self.config['items'][key]
                self.assertEqual(key, item['behavior']['furniture'])
                self.assertEqual('four', item['behavior']['rules']['ground']['rotation'])
                variant = furniture['variants']['ground']
                self.assertEqual('0,0.5,0', variant['elements'][0]['position'])
                self.assertEqual('none', variant['elements'][0]['display_transform'])
                seat = variant['hitboxes'][0]['seats'][0]
                self.assertEqual('0,-0.3375,0 180 false' if family == 'chair' else '0,-0.4125,0 180 false', seat)
                self.assertEqual(key, furniture['loot']['pools'][0]['entries'][0]['item'])
                recipe = self.config['recipes'][NAMESPACE + ':upstream/' + ART + '/' + name]
                self.assertEqual({'id': key, 'count': 1}, recipe['result'])
                if family == 'chair':
                    self.assertEqual(NAMESPACE + ':chair', furniture['behavior'][0]['type'])
                else:
                    self.assertNotIn('behavior', furniture)
        for color in COLORS:
            model = self.config['items']['show:chair_carpet_' + color]['model']
            self.assertEqual(ART + ':block/carpet/chair/' + color, model['path'])
        self.assertEqual(11, self.manifest['seating']['chairs'])
        self.assertEqual(11, self.manifest['seating']['stools'])
        self.assertEqual('NOT_TESTED', self.manifest['seating']['runtime'])

    def test_seating_rejects_unconverted_model_branches(self):
        from cookery_seating import seating_furniture
        assets, _ = source_assets(SOURCE)
        path = ART + '/blockstates/chair_oak.json'
        states = json.loads(assets[path])
        next(iter(states['variants'].values()))['x'] = 90
        assets[path] = json.dumps(states).encode()
        definitions = {ART + ':item/chair_oak': {}}
        with self.assertRaisesRegex(AuditError, 'unhandled model variant'):
            seating_furniture(SOURCE, assets, {}, definitions, {})

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
        self.assertEqual([
            {'type': 'face_attached_horizontal_directional_block', 'blacklist': True},
            {'type': key},
        ], block['behaviors'])
        self.assertEqual({'pools': []}, block['loot'])
        self.assertEqual({'face', 'facing', 'color', 'pattern', 'wave'}, set(block['states']['properties']))
        self.assertEqual('floor', block['states']['properties']['face']['default'])
        self.assertEqual('north', block['states']['properties']['facing']['default'])
        self.assertEqual(14, block['states']['properties']['color']['default'])
        self.assertEqual(0, block['states']['properties']['pattern']['default'])
        self.assertEqual(0, block['states']['properties']['wave']['default'])
        self.assertEqual(2 * 4 * 16 * 7 * 3, len(block['states']['variants']))
        appearances = block['states']['appearances']
        display_items = set()
        for appearance in appearances.values():
            self.assertEqual({'type': 'lower_tripwire', 'id': 'cookery_kitchen_clear'}, appearance['auto_state'])
            self.assertTrue(appearance['transparent'])
            self.assertNotIn('model', appearance)
            display_items.add(appearance['entity_renderer']['item'])
        self.assertEqual(2 * 16 * 7 * 3, len(display_items))
        cases = [
            ('floor_north_color_14_pattern_0_wave_0', 0, 'floor_red_tea_still'),
            ('wall_west_color_15_pattern_6_wave_2', 270, 'wall_black_piglin_right'),
        ]
        for appearance, yaw, name in cases:
            renderer = appearances[appearance]['entity_renderer']
            self.assertEqual(yaw, renderer['yaw'])
            path = f'{NAMESPACE}:block/imported/tea_banner/{name}'
            self.assertEqual(path, self.config['items'][renderer['item']]['model']['path'])

    def test_bamboo_tray_sources_are_accounted_as_imported(self):
        self.assertEqual(612, self.manifest['recipeStatuses']['imported'])
        self.assertEqual(0, self.manifest['recipeStatuses'].get('pending', 0))
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
                  if isinstance(block.get('behavior', block.get('behaviors')), list)
                  and block.get('behavior', block.get('behaviors'))[0]['type'] == NAMESPACE + ':plate'}
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
            behaviors = block.get('behavior', block.get('behaviors'))
            if not isinstance(behaviors, list) or behaviors[0]['type'] != NAMESPACE + ':plate':
                continue
            with self.subTest(plate=key):
                maximum = block['behavior'][0]['max_servings']
                self.assertEqual(maximum, block['states']['properties']['servings']['default'])
                self.assertIn(f'facing=north,servings={maximum}', block['states']['variants'])
                self.assertIn('facing=north,servings=0', block['states']['variants'])

    def test_plate_removal_has_one_owner_for_portions_and_bowls(self):
        for key, block in self.config['blocks'].items():
            behaviors = block.get('behavior', block.get('behaviors'))
            if not isinstance(behaviors, list) or behaviors[0]['type'] != NAMESPACE + ':plate':
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

    def test_steamer_maps_slab_type_and_owns_portable_layer_drops(self):
        block = self.config['blocks'][NAMESPACE + ':steamer']
        for slab, suffix, lift in (('bottom', 'half', '0,0,0'), ('top', 'half', '0,0.5,0'), ('double', 'full', '0,0,0')):
            variant = block['states']['variants'][f'facing=north,type={slab},has_base=false,has_lid=false']
            renderer = block['states']['appearances'][variant['appearance']]['entity_renderer']
            self.assertEqual(lift, renderer['translation'])
            self.assertEqual(ART + ':block/steamer_' + suffix, self.config['items'][renderer['item']]['model']['path'])
        self.assertEqual(1, len(block['behavior']))
        self.assertTrue(block['behavior'][0]['portable_contents'])
        self.assertEqual({'pools': []}, block['loot'])
        self.assertEqual(NAMESPACE + ':steamer_item', self.config['items'][NAMESPACE + ':steamer']['behavior']['type'])

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
        self.assertEqual(25, self.manifest['furniture'])
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
