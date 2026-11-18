"""Check source coverage, recipe quantities and strict conversion boundaries."""

import copy
import json
from pathlib import Path
import unittest

import yaml

from audit_upstream import AuditError
from cookery_recipes import ART, TARGET_TAG_MEMBERS, RecipeSources, mapped, read_sources, processing_recipes, cooking_recipes, rice_bowl_recipes, validate_recipe
from cookery_stockpot import stockpot_recipes
from cookery_pot import pot_recipes
from export_cookery_kitchen_pack import build, PACK

SOURCE = Path(__file__).resolve().parents[2] / '.references' / 'KaleidoscopeCookery'


class RecipeImportTest(unittest.TestCase):
    def test_forge_mushrooms_mapping_retains_both_vanilla_members(self):
        self.assertEqual(('minecraft:brown_mushroom', 'minecraft:red_mushroom'),
                         TARGET_TAG_MEMBERS['forge:mushrooms'])
        self.assertEqual(['minecraft:brown_mushroom', 'minecraft:red_mushroom'],
                         self.sources.expand({'tag': 'forge:mushrooms'}))
        self.assertEqual(['minecraft:brown_mushroom', 'minecraft:red_mushroom'],
                         self.report['targetTagMappings']['forge:mushrooms'])
        expected = {
            ART + ':pot/fish_flavored_shredded_pork', ART + ':pot/fried_spring_roll',
            ART + ':flex_pot/fish_flavored_shredded_pork', ART + ':flex_pot/fried_spring_roll',
            ART + ':stockpot/chicken_and_mushroom_stew', ART + ':stockpot/wild_mushroom_rabbit_soup',
            ART + ':flex_stockpot/chicken_and_mushroom_stew', ART + ':flex_stockpot/wild_mushroom_rabbit_soup',
        }
        rows = [row for row in self.report['recipes'] if row['sourceId'] in expected]
        self.assertEqual(expected, {row['sourceId'] for row in rows})
        self.assertTrue(all(row['status'] == 'imported' for row in rows))

    def test_fixed_target_tags_preserve_explicit_source_members_and_nested_optional_inputs(self):
        sources = RecipeSources({}, {}, {}, {})
        for tag, members in TARGET_TAG_MEMBERS.items():
            self.assertEqual(list(members), sources.expand({'tag': tag}))
        with self.assertRaisesRegex(AuditError, 'required tag absent'):
            sources.expand({'tag': 'forge:ingots/unobtainium'})
        tags = {
            'forge:ingots/iron': {'values': ['minecraft:iron_ingot', 'kaleidoscope_cookery:test_iron']},
            'test:nested': {'values': [{'id': '#forge:gravel', 'required': False}]},
        }
        sources = RecipeSources({}, tags, {}, {})
        self.assertEqual(['minecraft:iron_ingot', 'kaleidoscopecookery:test_iron'],
                         sources.expand({'tag': 'forge:ingots/iron'}))
        omissions = []
        self.assertEqual(['minecraft:gravel'], sources.expand({'tag': 'test:nested'}, omissions=omissions))
        self.assertEqual([], omissions)
        self.assertEqual({tag: list(members) for tag, members in TARGET_TAG_MEMBERS.items()},
                         self.report['targetTagMappings'])
        self.assertEqual(self.report['targetTagMappings'], self.manifest['targetTagMappings'])

    @classmethod
    def setUpClass(cls):
        cls.sources = read_sources(SOURCE)
        cls.bundle, cls.manifest = build(SOURCE)
        cls.config = yaml.safe_load(cls.bundle[f'{PACK}/configuration/kitchen.yml'])
        cls.report = json.loads(cls.bundle[f'{PACK}/recipe-parity.json'])

    def test_every_recipe_is_accounted_for_in_both_namespaces(self):
        rows = self.report['recipes']
        self.assertEqual(612, len(rows))
        self.assertEqual(612, len({row['sourceId'] for row in rows}))
        self.assertTrue(any(row['sourceId'].startswith('minecraft:') for row in rows))
        self.assertEqual(16, len(self.report['sourceTypes']))
        for row in rows:
            self.assertEqual(self.manifest['sourceCommit'], row['sourceCommit'])
            self.assertTrue(row['reason'])
            self.assertEqual(row['status'] == 'imported', bool(row['pluginIds']))
            self.assertEqual(row['source']['sha256'], self.report['sourceFiles'][row['source']['path']])
            for key in row['pluginIds']:
                self.assertIn(key, self.config[row['section']])

    def test_knife_smithing_preserves_all_three_roles_and_custom_data_processor(self):
        row = next(r for r in self.report['recipes'] if r['type'] == 'minecraft:smithing_transform')
        self.assertEqual('imported', row['status'])
        recipe = self.config['recipes'][row['pluginIds'][0]]
        self.assertEqual('smithing_transform', recipe['type'])
        self.assertEqual(['minecraft:netherite_upgrade_smithing_template'], recipe['template_type'])
        self.assertEqual(['kaleidoscopecookery:diamond_kitchen_knife'], recipe['base'])
        self.assertEqual(['minecraft:netherite_ingot'], recipe['addition'])
        self.assertEqual({'id': 'kaleidoscopecookery:netherite_kitchen_knife', 'count': 1}, recipe['result'])
        self.assertTrue(recipe['merge_components'])
        self.assertEqual([{'type': 'kaleidoscopecookery:kitchen_knife_smithing'}], recipe['transform_processors'])
        self.assertEqual('NOT_TESTED', self.manifest['smithing'][row['pluginIds'][0]]['runtime'])

    def test_knife_smithing_rejects_missing_items_or_changed_source_semantics(self):
        from cookery_recipes import smithing_recipes
        rows = [copy.deepcopy(r) for r in self.report['recipes'] if r['type'] == 'minecraft:smithing_transform']
        available = set(self.config['items'])
        for role in ('template', 'base', 'addition', 'result'):
            changed = copy.deepcopy(rows)
            changed[0]['original'][role] = {'item': 'minecraft:stone'}
            with self.assertRaisesRegex(AuditError, 'unsupported kitchen-knife'):
                smithing_recipes(changed, available)
        with self.assertRaisesRegex(AuditError, 'uninstalled'):
            smithing_recipes(rows, available - {'kaleidoscopecookery:diamond_kitchen_knife'})
        with self.assertRaisesRegex(AuditError, 'one pinned'):
            smithing_recipes([], available)
        self.assertEqual(self.report['recipes'][next(i for i,r in enumerate(self.report['recipes']) if r['type'] == 'minecraft:smithing_transform')], rows[0])

    def test_filled_steamers_keep_four_separate_inputs_and_exact_runtime_payload(self):
        for name in ('raw_dough', 'stuffed_dough_food'):
            key = 'kaleidoscopecookery:upstream/' + ART + '/' + name + '_steamer'
            recipe = self.config['recipes'][key]
            self.assertEqual('shapeless', recipe['type'])
            self.assertEqual(['kaleidoscopecookery:steamer'] + ['kaleidoscopecookery:' + name] * 4, recipe['ingredients'])
            self.assertEqual({'id': 'kaleidoscopecookery:steamer', 'count': 1,
                'post_processors': [{'type': 'kaleidoscopecookery:filled_steamer', 'ingredient': 'kaleidoscopecookery:' + name}]}, recipe['result'])
            self.assertEqual(1200, self.manifest['filledSteamers'][key]['cookingTime'])

    def test_changed_steamer_nbt_inputs_or_missing_content_fail_closed(self):
        from cookery_recipes import filled_steamer_recipes
        rows = [copy.deepcopy(r) for r in self.report['recipes'] if isinstance(r['original']['result'], dict) and 'nbt' in r['original']['result']]
        available = set(self.config['items'])
        for old, new in [('1200', '200'), ('Slot:0b', 'Slot:3b'), ('Count:1b', 'Count:2b')]:
            changed = copy.deepcopy(rows); changed[0]['original']['result']['nbt'] = changed[0]['original']['result']['nbt'].replace(old, new)
            with self.assertRaisesRegex(AuditError, 'unsupported filled-steamer'): filled_steamer_recipes(changed, available)
        changed = copy.deepcopy(rows); changed[0]['original']['ingredients'].pop()
        with self.assertRaisesRegex(AuditError, 'unsupported filled-steamer'): filled_steamer_recipes(changed, available)
        with self.assertRaisesRegex(AuditError, 'uninstalled'): filled_steamer_recipes(rows, available - {'kaleidoscopecookery:raw_dough'})
        with self.assertRaisesRegex(AuditError, 'two pinned'): filled_steamer_recipes(rows[:1], available)

    def test_tea_expansion_keeps_fluid_count_and_time(self):
        recipes = self.config['teapot_result']
        self.assertEqual(11, len(recipes))
        butter = recipes['kaleidoscopecookery:teapot/butter_tea']
        self.assertEqual('minecraft:milk', butter['fluid'])
        self.assertEqual('kaleidoscopecookery:butter_tea_bag 1', butter['require'])
        self.assertEqual('kaleidoscopecookery:butter_tea 1', butter['result'])
        self.assertTrue(all(row['time'] == 240 for row in recipes.values()))
        flowers = [row['require'] for key, row in recipes.items() if '/flower_tea/' in key]
        self.assertEqual({f'minecraft:{name} 1' for name in ('lilac', 'rose_bush', 'peony', 'pitcher_plant', 'sunflower')}, set(flowers))

    def test_vanilla_cooking_recipes_keep_time_experience_and_result(self):
        rows = [row for row in self.report['recipes'] if row['type'] in
                ('minecraft:smelting', 'minecraft:smoking', 'minecraft:campfire_cooking')]
        self.assertEqual(20, len(rows))
        self.assertTrue(all(row['status'] == 'imported' for row in rows))
        for row in rows:
            recipe = self.config['recipes'][row['pluginIds'][0]]
            self.assertEqual(row['original']['cookingtime'], recipe['time'])
            self.assertEqual(row['original']['experience'], recipe['experience'])
            self.assertEqual(mapped(row['original']['result']), recipe['result']['id'])

    def test_cooking_serializer_keys_match_craftengine_26_9_1(self):
        # CE RecipeSerializers requires the underscore key; a hyphen makes it skip campfire recipes.
        cooking_types = {'minecraft:smelting', 'minecraft:smoking', 'minecraft:campfire_cooking'}
        rows = [row for row in self.report['recipes'] if row['type'] in cooking_types]
        self.assertEqual(7, sum(row['type'] == 'minecraft:campfire_cooking' for row in rows))
        for row in rows:
            with self.subTest(recipe=row['sourceId']):
                recipe = self.config['recipes'][row['pluginIds'][0]]
                self.assertEqual(row['type'], 'minecraft:' + recipe['type'])

    def test_appliance_crafting_keeps_all_forge_material_alternatives(self):
        by_id = {row['sourceId']: row for row in self.report['recipes']}
        for name, center in (('stove_campfire', 'minecraft:campfire'),
                             ('stove_soul_campfire', 'minecraft:soul_campfire')):
            row = by_id[ART + ':' + name]
            self.assertEqual('imported', row['status'])
            recipe = self.config['recipes'][row['pluginIds'][0]]
            self.assertEqual(['###', '#F#', '###'], recipe['pattern'])
            self.assertEqual(center, recipe['ingredients']['F'])
            self.assertEqual(['minecraft:cobblestone', 'minecraft:infested_cobblestone',
                              'minecraft:mossy_cobblestone', 'minecraft:cobbled_deepslate'],
                             recipe['ingredients']['#'])
            self.assertEqual({'id': 'kaleidoscopecookery:stove', 'count': 1}, recipe['result'])
        row = by_id[ART + ':millstone']
        recipe = self.config['recipes'][row['pluginIds'][0]]
        self.assertEqual([' F ', 'SG ', 'TTT'], recipe['pattern'])
        self.assertEqual('#minecraft:wooden_fences', recipe['ingredients']['F'])
        self.assertEqual({'id': 'kaleidoscopecookery:millstone', 'count': 1}, recipe['result'])

    def test_stockpot_keeps_exact_counts_and_distinct_matching_separate(self):
        recipes = self.config.get('stockpot_recipes', {})
        self.assertEqual(105, len(recipes))
        rice = recipes['kaleidoscopecookery:stockpot/rice_9']
        self.assertEqual('exact', rice['match'])
        self.assertEqual(9, len(rice['ingredients']))
        self.assertEqual(9, rice['result_count'])
        self.assertEqual(900, rice['time'])
        self.assertEqual('minecraft:water_bucket', rice['soup_base'])
        self.assertEqual(0xE9E3DB, rice['visuals']['finished_color'])
        flex = recipes['kaleidoscopecookery:flex_stockpot/pork_bone_soup']
        self.assertEqual('distinct', flex['match'])
        self.assertEqual([['minecraft:bone']], flex['ingredients'])
        self.assertEqual(1, flex['result_count'])
        for row in recipes.values():
            for field in ('cooking_model', 'finished_model'):
                self.assertIn(row['visuals'][field], self.config['items'])
        pending = [r for r in self.report['recipes'] if r['type'] in
                   (ART + ':stockpot', ART + ':flex_stockpot') and r['status'] == 'pending']
        self.assertEqual([], pending)

    def test_pot_import_preserves_every_alternative_and_official_batch_size(self):
        recipes = self.config.get('pot_recipes', {})
        self.assertEqual(225, len(recipes))
        rows = [r for r in self.report['recipes'] if r['type'] in (ART + ':pot', ART + ':flex_pot')]
        self.assertEqual(225, len(rows))
        for row in rows:
            if row['status'] != 'imported':
                self.assertIn('forge:mushrooms', row['reason'])
                continue
            recipe = recipes[mapped(row['sourceId'])]
            original = row['original']
            self.assertEqual([self.sources.expand(value) for value in original['ingredients']], recipe['ingredients'])
            self.assertEqual(original['result'].get('count', 1), recipe['result_count'])
            self.assertEqual(original['stir_fry_count'], recipe['stir_fry_count'])
            self.assertEqual(original['time'], recipe['source_time'])
            self.assertEqual('distinct' if row['type'].endswith(':flex_pot') else 'exact', recipe['match'])
        self.assertEqual(0, sum(row['status'] == 'pending' for row in rows))

    def test_rice_bowls_keep_custom_matching_quality_and_rice_only_remainder_contract(self):
        rows = [row for row in self.report['recipes'] if row['type'] == ART + ':rice_bowl']
        self.assertEqual(5, len(rows))
        self.assertTrue(all(row['status'] == 'imported' for row in rows))
        self.assertEqual(5, len(self.manifest.get('riceBowls', {})))
        for row in rows:
            with self.subTest(recipe=row['sourceId']):
                key = row['pluginIds'][0]
                recipe = self.config['recipes'][key]
                self.assertEqual('kaleidoscopecookery:rice_bowl', recipe['type'])
                self.assertEqual('misc', recipe['category'])
                self.assertEqual([[mapped(row['original']['ingredient']['item'])], ['kaleidoscopecookery:cooked_rice']],
                                 recipe['ingredients'])
                self.assertEqual({'id': mapped(row['original']['result']['item']), 'count': 1}, recipe['result'])
                meta = self.manifest['riceBowls'][key]
                self.assertEqual(row['sourceId'], meta['sourceId'])
                self.assertEqual(self.manifest['sourceCommit'], meta['sourceCommit'])
                self.assertEqual('minecraft:bowl', meta['recipeRemainder'])
                self.assertEqual('exactly_one_dish_slot_and_one_rice_slot_with_extras', meta['matching'])
                self.assertEqual(recipe['ingredients'][0], meta['dishes'])
                self.assertEqual(recipe['ingredients'][1], meta['rice'])
                self.assertEqual(recipe['result']['id'], meta['result'])
                self.assertEqual('farmersdelight:cooked_rice', row['optionalTagAbsences'][0]['reference'])
                self.assertFalse(row['optionalTagAbsences'][0]['required'])

    def test_optional_source_dependencies_are_reported_for_all_nine_unblocked_recipes(self):
        rows = [row for row in self.report['recipes'] if row.get('optionalTagAbsences')]
        self.assertEqual(9, len(rows))
        self.assertEqual({'statusCounts': {'imported': 612}}, {'statusCounts': self.report['statusCounts']})
        for row in rows:
            self.assertEqual('imported', row['status'])
            for absent in row['optionalTagAbsences']:
                self.assertFalse(absent['required'])
                self.assertTrue(absent['reason'])
        for name in ('raw_meatball', 'stuffed_dough_food'):
            row = next(row for row in rows if row['sourceId'] == ART + ':' + name)
            self.assertEqual('#forge:crops/cabbage', row['optionalTagAbsences'][0]['reference'])
        self.assertEqual({row['sourceId']: row['optionalTagAbsences'] for row in rows}, self.manifest['optionalTagAbsences'])

    def test_rice_bowl_conversion_rejects_missing_roles_items_and_changed_output(self):
        records = [copy.deepcopy(row) for row in self.report['recipes'] if row['type'] == ART + ':rice_bowl']
        with self.assertRaisesRegex(AuditError, 'five pinned rice-bowl'):
            rice_bowl_recipes(self.sources, records[:-1], set(self.config['items']))
        with self.assertRaisesRegex(AuditError, 'uninstalled rice-bowl'):
            rice_bowl_recipes(self.sources, copy.deepcopy(records), set())
        altered = copy.deepcopy(records)
        altered[0]['original']['result']['nbt'] = '{hidden_output:1}'
        with self.assertRaisesRegex(AuditError, 'category/result'):
            rice_bowl_recipes(self.sources, altered, set(self.config['items']))
        altered = copy.deepcopy(records)
        altered[0]['original']['ingredient'] = {'item': ART + ':cooked_rice'}
        with self.assertRaisesRegex(AuditError, 'overlapping'):
            rice_bowl_recipes(self.sources, altered, set(self.config['items']))
        tags = copy.deepcopy(self.sources.tags)
        tags['forge:cooked_rice'] = {'values': [{'id': '#test:missing', 'required': False}]}
        source = RecipeSources(self.sources.recipes, tags, self.sources.origins, self.sources.files)
        with self.assertRaisesRegex(AuditError, 'empty or overlapping'):
            rice_bowl_recipes(source, copy.deepcopy(records), set(self.config['items']))

    def test_dough_tag_and_steamer_times_are_not_guessed(self):
        recipes = {key: row for key, row in self.config['accurate_foods'].items() if row['cook'] == 'STEAMER'}
        self.assertEqual(4, len(recipes))
        self.assertTrue(all(row['cook'] == 'STEAMER' and row['cooking_time'] == 1200 and row['result_count'] == 1
                            for row in recipes.values()))
        self.assertEqual('kaleidoscopecookery:raw_dough', recipes['kaleidoscopecookery:steamer/mantou']['require'])

    def test_millstone_keeps_all_inputs_outputs_and_one_revolution(self):
        recipes = {key: row for key, row in self.config['accurate_foods'].items() if row['cook'] == 'MILLSTONE'}
        self.assertEqual(90, len(recipes))
        rows = [row for row in self.report['recipes'] if row['type'] == ART + ':millstone']
        self.assertEqual(85, len(rows))
        self.assertEqual(85, sum(row['status'] == 'imported' for row in rows))
        self.assertEqual(set(), {row['sourceId'] for row in rows if row['status'] == 'pending'})
        for row in rows:
            if row['status'] != 'imported':
                self.assertIn('required tag absent', row['reason'])
                continue
            original = row['original']
            converted = [recipes[key] for key in row['pluginIds']]
            self.assertEqual(self.sources.expand(original['ingredient']), [value['require'] for value in converted])
            for value in converted:
                self.assertEqual(mapped(original['result']['item']), value['result'])
                self.assertEqual(original['result'].get('count', 1), value['result_count'])
                self.assertEqual(1, value['rotations'])
                self.assertNotIn('cooking_time', value)

    def test_millstone_unknown_tag_and_output_structure_fail(self):
        for field, value in (('ingredient', {'tag': 'forge:unknown'}),
                             ('result', [{'item': 'minecraft:stone'}]),
                             ('result', {'item': 'minecraft:stone', 'nbt': '{}'})):
            sources = copy.deepcopy(self.sources)
            doc = sources.recipes[ART + ':millstone/bone_meal_from_bone']
            doc[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(AuditError):
                processing_recipes(sources)

    def test_cutting_keeps_byproducts_and_all_four_saved_stages(self):
        recipes = self.config['chopping_board_raws']
        self.assertEqual(16, len(recipes))
        chicken = recipes['kaleidoscopecookery:chopping_board/raw_cut_small_meats_from_chicken']
        self.assertEqual('kaleidoscopecookery:raw_cut_small_meats 3', chicken['result'])
        self.assertEqual(['minecraft:bone 1 100'], chicken['extra'])
        for row in recipes.values():
            self.assertEqual(4, row['stage'])
            for stage in range(4):
                self.assertIn(row['values'] + '/' + str(stage), self.config['items'])
            self.assertNotIn(row['values'] + '/4', self.config['items'])

    def test_cups_exclude_the_coaster_and_bars_reference_real_pngs(self):
        self.assertEqual(8, len(self.config['tea_cup']))
        for cup in self.config['tea_cup'].values():
            model = self.config['items'][cup['display_model']]['model']['path']
            namespace, path = model.split(':', 1)
            geometry = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/models/{path}.json'])
            self.assertFalse(any(e['from'] == [1, 0, 1] and e['to'] == [15, 1, 15] for e in geometry['elements']))
        for liquid in self.config['teapot_liquid'].values():
            for name, image in liquid.items():
                if not name.startswith('bar_'):
                    continue
                namespace, path = self.config['images'][image]['file'].split(':', 1)
                self.assertTrue(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/textures/{path}'].startswith(b'\x89PNG'))

    def test_soup_geometry_matches_existing_spawn_coordinates(self):
        self.assertEqual(9, len(self.config['stock_food_raw']['liquid']))
        for name in ('water', 'milk', 'lava', 'finished'):
            node = self.config['items']['show:stove_' + name]['model']
            namespace, path = node['path'].split(':', 1)
            model = json.loads(self.bundle[f'{PACK}/resourcepack/assets/{namespace}/models/{path}.json'])
            for corner in ('from', 'to'):
                x, y, z = model['elements'][0][corner]
                self.assertAlmostEqual(.38, .9 + y / 16 - .5)
                self.assertAlmostEqual(3 / 16 if corner == 'from' else 13 / 16, .3 + 3.2 / 16 + x / 16 - .5)
                self.assertEqual(x, z)

    def test_crafting_does_not_strip_nbt_or_missing_alternatives(self):
        crafting = self.config['recipes']
        for name in ('barley', 'biluochun', 'butter', 'oolong', 'sakura_fubuki', 'tieguanyin'):
            key = f'kaleidoscopecookery:upstream/{ART}/{name}_tea_bag'
            self.assertIn(key, crafting)
            self.assertEqual(['LLL', 'LCL', 'LLL'], crafting[key]['pattern'])
        nbt = [row for row in self.report['recipes'] if isinstance(row['original']['result'], dict) and 'nbt' in row['original']['result']]
        self.assertEqual(2, len(nbt))
        self.assertTrue(all(row['status'] == 'imported' and 'NBT' in row['reason'] for row in nbt))

    def test_chain_uses_its_target_version_registry_name(self):
        self.assertEqual('minecraft:iron_chain', mapped('minecraft:chain'))
        spit = self.config['recipes']['kaleidoscopecookery:upstream/kaleidoscope_cookery/shawarma_spit']
        self.assertEqual('minecraft:iron_chain', spit['ingredients']['I'])

    def test_unknown_fields_fail_even_for_pending_recipe_types(self):
        doc = copy.deepcopy(next(doc for doc in self.sources.recipes.values() if doc['type'] == ART + ':pot'))
        for bad in ({**doc, 'secret_output': {'item': 'minecraft:diamond'}}, {**doc, 'type': ART + ':new_machine'},
                    {**doc, 'ingredients': [{'item': 'minecraft:beef', 'count': 2}]},
                    {**doc, 'time': True}):
            with self.subTest(bad=bad), self.assertRaises(AuditError):
                validate_recipe(bad)

    def test_unknown_cyclic_or_empty_required_tags_abort_processing(self):
        doc = copy.deepcopy(self.sources.recipes[ART + ':steamer/mantou'])
        doc['ingredient'] = {'tag': 'test:input'}
        for tags in ({}, {'test:input': {'values': ['#test:input']}}, {'test:input': {'values': []}}):
            source = RecipeSources({ART + ':steamer/mantou': doc}, tags,
                                   {ART + ':steamer/mantou': {}}, {})
            with self.subTest(tags=tags), self.assertRaises(AuditError):
                processing_recipes(source)

    def test_optional_missing_references_are_explicit_without_dropping_present_inputs(self):
        source = RecipeSources({}, {'test:rice': {'values': [ART + ':cooked_rice',
            {'id': 'farmersdelight:cooked_rice', 'required': False},
            {'id': '#test:missing_optional', 'required': False}]}}, {}, {})
        omissions = []
        self.assertEqual(['kaleidoscopecookery:cooked_rice'], source.expand({'tag': 'test:rice'}, omissions=omissions))
        self.assertEqual([
            {'tag': 'test:rice', 'reference': 'farmersdelight:cooked_rice', 'required': False,
             'reason': 'Optional external item has no mapping in the fixed plugin target.'},
            {'tag': 'test:rice', 'reference': '#test:missing_optional', 'required': False,
             'reason': 'Optional tag is absent from the fixed source tree.'}], omissions)

    def test_present_optional_tag_does_not_hide_a_missing_required_descendant(self):
        source = RecipeSources({}, {'test:root': {'values': [{'id': '#test:present', 'required': False}]},
                                   'test:present': {'values': ['#test:required']}}, {}, {})
        with self.assertRaisesRegex(AuditError, 'required tag absent.*test:required'):
            source.expand({'tag': 'test:root'})
        for reference in ('farmersdelight:cooked_rice', '#test:missing'):
            source = RecipeSources({}, {'test:root': {'values': [{'id': reference, 'required': True}]}}, {}, {})
            with self.subTest(reference=reference), self.assertRaises(AuditError):
                source.expand({'tag': 'test:root'})

    def test_optional_tag_cycles_and_unknown_entry_shapes_are_errors(self):
        for entry in ({'id': '#test:root', 'required': False}, {'id': 'minecraft:stone', 'required': 'false'},
                      {'id': 'minecraft:stone', 'required': False, 'hidden': True}):
            source = RecipeSources({}, {'test:root': {'values': [entry]}}, {}, {})
            with self.subTest(entry=entry), self.assertRaises(AuditError):
                source.expand({'tag': 'test:root'})

    def test_processing_output_nbt_cannot_be_silently_discarded(self):
        doc = copy.deepcopy(self.sources.recipes[ART + ':steamer/mantou'])
        doc['result']['nbt'] = '{CustomModelData:17}'
        source = RecipeSources({ART + ':steamer/mantou': doc}, self.sources.tags,
                               {ART + ':steamer/mantou': {}}, {})
        with self.assertRaisesRegex(AuditError, 'NBT'):
            processing_recipes(source)

    def test_stockpot_unknown_tags_and_nbt_fail_instead_of_becoming_pending(self):
        source_id = ART + ':stockpot/pork_bone_soup'
        doc = copy.deepcopy(self.sources.recipes[source_id])
        for field, value in (('ingredients', [{'tag': 'test:new_unknown'}]),
                             ('result', {'item': ART + ':pork_bone_soup', 'nbt': '{CustomModelData:17}'}),
                             ('empty_carrier', True), ('soup_base', 'test:unknown')):
            changed = {**doc, field: value}
            source = RecipeSources({source_id: changed}, self.sources.tags, {source_id: {}}, {})
            _, rows, _ = processing_recipes(source)
            with self.subTest(field=field), self.assertRaises(AuditError):
                stockpot_recipes(source, rows)

    def test_pot_unknown_inputs_or_output_components_cannot_silently_become_pending(self):
        source_id = ART + ':pot/potato_to_baked_potato_3'
        doc = copy.deepcopy(self.sources.recipes[source_id])
        for field, value in (('ingredients', [{'tag': 'test:new_unknown'}]),
                             ('ingredients', [{'item': 'minecraft:potato'}] * 10),
                             ('result', {'item': 'minecraft:baked_potato', 'nbt': '{CustomModelData:17}'}),
                             ('carrier', {'tag': 'forge:raw_beef'}), ('stir_fry_count', 0),
                             ('new_rule', True)):
            changed = {**doc, field: value}
            source = RecipeSources({source_id: changed}, self.sources.tags, {source_id: {}}, {})
            _, rows, _ = processing_recipes(source)
            with self.subTest(field=field), self.assertRaises(AuditError):
                pot_recipes(source, rows)


if __name__ == '__main__':
    unittest.main()
