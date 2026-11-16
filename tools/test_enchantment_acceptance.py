"""Acceptance evidence must not hide new errors or mutate existing server directories."""

from pathlib import Path
import copy
import hashlib
import json
import unittest
from tempfile import TemporaryDirectory
from unittest.mock import patch
import zipfile

from run_enchantment_acceptance import (ACCEPTANCE, COMMIT, console_text, install_kitchen, validate_rice,
                                        missing_block_error, prepare, validate_kitchen, validate_recipes, validate_pots,
                                        validate_millstone, validate_bamboo, validate_plates, validate_meals, runtime_failures)


def two_meal_fixture() -> tuple[dict, dict]:
    return {'placedMeals': {
        'kaleidoscopecookery:cold_style_sashimi': {
            'maxBites': 4, 'layout': '1x1', 'cells': 1, 'states': 20,
            'drops': ['minecraft:bowl', 'minecraft:snowball', 'minecraft:snowball']},
        'kaleidoscopecookery:cold_cut_ham_slices': {
            'maxBites': 8, 'layout': '3x3', 'cells': 9, 'states': 324, 'drops': ['minecraft:bowl']}
    }}, {'failures': [], 'checkedMeals': 2, 'checkedCells': 10, 'checkedStates': 344,
         'layouts': {'1x1': 1, '3x3': 1}}


def saved_meal(item: str, maximum: int, group: str) -> dict:
    return {'state': json.dumps({'version': 1, 'sourceCommit': COMMIT, 'itemId': item,
                                'maxBites': maximum, 'consumed': 1, 'quality': {'name': 'BASE', 'ratio': 1.0}}),
            'group': group, 'facing': 'south'}


class AcceptanceSafetyTests(unittest.TestCase):
    def test_rice_native_quality_remainders_and_disk_evidence_are_required(self) -> None:
        manifest = {'riceBowls': {'test:rice': {'result': 'test:finished'}}}
        report = {'failures': [], 'checkedRecipes': 1, 'phase': 'CRAFTED_SAVED', 'chestMetadata': 'a' * 64,
                  'qualityCases': 25, 'extraQualityCases': 1, 'extraConsumptionCases': 3,
                  'duplicateRejected': 2, 'mismatchRejected': 1, 'repeatedCrafts': 3,
                  'overflowBowls': 2, 'storedResults': 3, 'storedBowls': 3,
                  'storedItems': {'test:finished': 3, 'minecraft:bowl': 3}}
        validate_rice(report, manifest, 1)
        for field, bad in [('qualityCases', 0), ('extraQualityCases', 0), ('extraConsumptionCases', 0),
                           ('duplicateRejected', 0), ('mismatchRejected', 0), ('repeatedCrafts', 1),
                           ('overflowBowls', 0), ('storedResults', 6), ('storedBowls', 6),
                           ('storedItems', {'test:finished': 3}), ('chestMetadata', 'absent')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_rice({**report, field: bad}, manifest, 1)
        second = {**report, 'phase': 'RESTORED', 'reloadedResults': 3, 'reloadedBowls': 3,
                  'metadataPreserved': True, 'storedItems': {}}
        validate_rice(second, manifest, 2)
        with self.assertRaises(ValueError):
            validate_rice({**second, 'metadataPreserved': False}, manifest, 2)
        third = {**second, 'phase': 'EMPTY_RELOADED', 'emptyNoDuplicate': True}
        validate_rice(third, manifest, 3)
        with self.assertRaises(ValueError):
            validate_rice({**third, 'storedItems': {'test:finished': 1}}, manifest, 3)

    def test_meal_first_bite_requires_every_snapshot_and_cancellation_check(self) -> None:
        manifest, base = two_meal_fixture()
        first = {**base, 'phase': 'PARTIAL_SAVED', 'placedMeals': 2, 'blockedPlacements': 2,
                 'cancelledConsumes': 2, 'committedBites': 2, 'reentryChecks': 2, 'badSaveRejected': 2,
                 'expiredCommitsRejected': 2,
                 'states': {
                     'kaleidoscopecookery:cold_style_sashimi': saved_meal('kaleidoscopecookery:cold_style_sashimi', 4,
                                                                        '00000000-0000-0000-0000-000000000001'),
                     'kaleidoscopecookery:cold_cut_ham_slices': saved_meal('kaleidoscopecookery:cold_cut_ham_slices', 8,
                                                                         '00000000-0000-0000-0000-000000000002')}}
        validate_meals(first, manifest, 1)
        for field, bad in [('failures', ['rejected placement']), ('phase', 'REMOVED'), ('checkedMeals', 1),
                           ('checkedCells', 2), ('checkedStates', 343), ('layouts', {'1x1': 2}),
                           ('placedMeals', 1), ('blockedPlacements', 0), ('cancelledConsumes', 0),
                           ('committedBites', 3), ('reentryChecks', 0), ('badSaveRejected', 0),
                           ('expiredCommitsRejected', 0), ('states', {})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_meals({**first, field: bad}, manifest, 1)
        id_ = 'kaleidoscopecookery:cold_style_sashimi'
        missing = copy.deepcopy(first)
        del missing['states'][id_]
        with self.assertRaises(ValueError):
            validate_meals(missing, manifest, 1)
        for field, bad in [('itemId', 'minecraft:apple'), ('maxBites', 8), ('consumed', 0),
                           ('consumed', 4), ('sourceCommit', 'not-the-pinned-source')]:
            corrupt = copy.deepcopy(first)
            state = json.loads(corrupt['states'][id_]['state'])
            state[field] = bad
            corrupt['states'][id_]['state'] = json.dumps(state)
            with self.subTest(snapshotField=field, value=bad), self.assertRaises(ValueError):
                validate_meals(corrupt, manifest, 1)
        corrupt = copy.deepcopy(first)
        corrupt['states'][id_]['group'] = ''
        with self.assertRaises(ValueError):
            validate_meals(corrupt, manifest, 1)
        corrupt['states'][id_]['state'] = '{broken-json'
        with self.assertRaises(ValueError):
            validate_meals(corrupt, manifest, 1)

    def test_meal_restart_requires_remaining_bites_and_exact_drop_multiplicity(self) -> None:
        manifest, base = two_meal_fixture()
        second = {**base, 'phase': 'REMOVED', 'reloadedMeals': 2, 'exhaustedMeals': 2,
                  'removedExhausted': 2, 'committedBites': 10, 'reentryChecks': 10, 'nonAnchorBreaks': 1,
                  'extraDrops': {'minecraft:bowl': 2, 'minecraft:snowball': 2},
                  'untouchedWholeDrops': {'kaleidoscopecookery:cold_style_sashimi': 1,
                                          'kaleidoscopecookery:cold_cut_ham_slices': 1}, 'states': {}}
        validate_meals(second, manifest, 2)
        for field, bad in [('phase', 'PARTIAL_SAVED'), ('reloadedMeals', 1), ('exhaustedMeals', 1),
                           ('removedExhausted', 0), ('committedBites', 12), ('reentryChecks', 9),
                           ('nonAnchorBreaks', 0), ('states', {'forgotten': {}}), ('extraDrops', {}),
                           ('untouchedWholeDrops', {})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_meals({**second, field: bad}, manifest, 2)
        for snowballs in (1, 3):
            with self.subTest(snowballs=snowballs), self.assertRaises(ValueError):
                validate_meals({**second, 'extraDrops': {'minecraft:bowl': 2, 'minecraft:snowball': snowballs}}, manifest, 2)
        for whole in ({'kaleidoscopecookery:cold_style_sashimi': 2},
                      {'kaleidoscopecookery:cold_style_sashimi': 1, 'kaleidoscopecookery:cold_cut_ham_slices': 2}):
            with self.subTest(whole=whole), self.assertRaises(ValueError):
                validate_meals({**second, 'untouchedWholeDrops': whole}, manifest, 2)

    def test_meal_final_restart_requires_every_cell_absent_and_no_new_output(self) -> None:
        manifest, base = two_meal_fixture()
        third = {**base, 'phase': 'REMOVAL_RELOADED', 'absentMeals': 2, 'absentCells': 10,
                 'emptyNoDuplicate': True, 'states': {}}
        validate_meals(third, manifest, 3)
        for field, bad in [('phase', 'REMOVED'), ('absentMeals', 1), ('absentCells', 2),
                           ('emptyNoDuplicate', False), ('states', {'remaining': {}})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_meals({**third, field: bad}, manifest, 3)

    def test_full_meal_coverage_requires_38_dishes_49_cells_and_four_nonanchor_breaks(self) -> None:
        catalog = json.loads((Path(__file__).resolve().parents[1] / 'src/main/resources/cookery-parity.json').read_text(encoding='utf-8'))
        meals = {}
        for entry in catalog['entries']:
            bite = entry.get('bites')
            if not bite:
                continue
            cells = {'1x1': 1, '1x2': 2, '3x3': 9}[bite['layout']]
            meals[entry['pluginId']] = {'maxBites': bite['maxBites'], 'layout': bite['layout'], 'cells': cells,
                                      'states': 4 * cells * (bite['maxBites'] + 1), 'drops': bite['drops']}
        self.assertEqual(38, len(meals))
        self.assertEqual(49, sum(meal['cells'] for meal in meals.values()))
        self.assertEqual(1024, sum(meal['states'] for meal in meals.values()))
        self.assertEqual(4, sum(meal['cells'] > 1 for meal in meals.values()))
        manifest = {'blocks': 62, 'items': 527, 'states': {'existing_kitchen': 418, 'placed_meals': 1024}, 'placedMeals': meals}
        kitchen = {'failures': [], 'checkedBlocks': 24, 'checkedStates': 418, 'checkedItems': 527,
                   'nonemptyContainers': 2, 'placedBlocks': 24, 'reloadedBlocks': 24}
        for cycle in (1, 2, 3):
            validate_kitchen(kitchen, manifest, cycle)
        for field, bad in [('checkedBlocks', 62), ('checkedStates', 1442), ('checkedItems', 342),
                           ('placedBlocks', 23)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_kitchen({**kitchen, field: bad}, manifest, 1)
        with self.assertRaises(ValueError):
            validate_kitchen({**kitchen, 'reloadedBlocks': 23}, manifest, 2)
        base = {'failures': [], 'checkedMeals': 38, 'checkedCells': 49, 'checkedStates': 1024,
                'layouts': {'1x1': 34, '1x2': 3, '3x3': 1}}
        second = {**base, 'phase': 'REMOVED', 'reloadedMeals': 38, 'exhaustedMeals': 38, 'removedExhausted': 38,
                  'committedBites': 93, 'reentryChecks': 93, 'nonAnchorBreaks': 4, 'states': {},
                  'untouchedWholeDrops': {id_: 1 for id_ in meals},
                  'extraDrops': {'minecraft:bowl': 33, 'minecraft:blaze_rod': 1, 'minecraft:bone': 2,
                                 'minecraft:flower_pot': 5, 'minecraft:snowball': 2, 'minecraft:amethyst_shard': 1,
                                 'minecraft:cactus': 1, 'minecraft:bamboo': 1, 'minecraft:chorus_fruit': 1,
                                 'minecraft:blue_ice': 1, 'minecraft:crimson_fungus': 1, 'minecraft:warped_fungus': 1,
                                 'minecraft:bone_meal': 2}}
        validate_meals(second, manifest, 2)
        for field, bad in [('nonAnchorBreaks', 3), ('committedBites', 131), ('reentryChecks', 0)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_meals({**second, field: bad}, manifest, 2)
        third = {**base, 'phase': 'REMOVAL_RELOADED', 'absentMeals': 38, 'absentCells': 49,
                 'emptyNoDuplicate': True, 'states': {}}
        validate_meals(third, manifest, 3)
        for field, bad in [('checkedMeals', 37), ('checkedCells', 48), ('checkedStates', 1020), ('absentCells', 38)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_meals({**third, field: bad}, manifest, 3)

    def test_plate_evidence_requires_exact_items_containers_and_restart(self) -> None:
        manifest = {'plates': {
            'kaleidoscopecookery:apple_platter': {'maxServings': 4, 'servings': ['minecraft:apple'], 'drops': ['minecraft:bowl']},
            'kaleidoscopecookery:berry_platter': {'maxServings': 4, 'servings': ['minecraft:sweet_berries', 'minecraft:glow_berries'],
                                                'drops': ['minecraft:bowl']}}}
        first = {'failures': [], 'checkedPlates': 2, 'phase': 'EMPTY_PLATES_SAVED', 'defaultFull': 2,
                 'emptySaved': 2, 'takenServings': 8, 'noEarlyBowl': True, 'reentryChecks': 8,
                 'takenItems': {'minecraft:apple': 4, 'minecraft:sweet_berries': 4, 'minecraft:glow_berries': 4},
                 'servings': {'apple_platter': 0, 'berry_platter': 0}}
        validate_plates(first, manifest, 1)
        for field, bad in [('defaultFull', 0), ('emptySaved', 0), ('takenServings', 12), ('takenItems', {}),
                           ('servings', {}), ('noEarlyBowl', False), ('reentryChecks', 0)]:
            with self.subTest(cycle=1, field=field), self.assertRaises(ValueError):
                validate_plates({**first, field: bad}, manifest, 1)
        second = {'failures': [], 'checkedPlates': 2, 'phase': 'PLATES_REMOVED', 'reloadedEmpty': 2,
                  'singleRefills': 1, 'berryRefillRejected': True, 'emptyBowls': 2, 'breakBowls': 2,
                  'breakDrops': {'minecraft:apple': 3, 'minecraft:sweet_berries': 3, 'minecraft:glow_berries': 3, 'minecraft:bowl': 2},
                  'removedPlates': 4, 'servings': {}, 'reentryChecks': 3}
        validate_plates(second, manifest, 2)
        for field, bad in [('reloadedEmpty', 0), ('singleRefills', 2), ('berryRefillRejected', False),
                           ('emptyBowls', 4), ('breakBowls', 4), ('breakDrops', {}), ('removedPlates', 2), ('reentryChecks', 0)]:
            with self.subTest(cycle=2, field=field), self.assertRaises(ValueError):
                validate_plates({**second, field: bad}, manifest, 2)
        third = {'failures': [], 'checkedPlates': 2, 'phase': 'REMOVAL_RELOADED', 'absentPlates': 2,
                 'servings': {}, 'emptyNoDuplicate': True}
        validate_plates(third, manifest, 3)
        with self.assertRaises(ValueError):
            validate_plates({**third, 'absentPlates': 1}, manifest, 3)
        with self.assertRaises(ValueError):
            validate_plates(first, manifest, 2)

    def test_ce_tick_and_config_warnings_cannot_pass_acceptance(self) -> None:
        lines = [
            '[19:20:47 WARN]: [CraftEngine] Failed to tick block entity(BlockEntity) at world acceptance-world',
            '[20:28:57 WARN]: [CraftEngine] Failed to unload chunk 0,0',
            '[19:19:57 WARN]: [CraftEngine] 在文件“configuration/kitchen.yml”中发现7个问题：',
            '[19:19:57 WARN]: [CraftEngine] Found 7 issues in file configuration/kitchen.yml:',
            '[19:19:57 WARN]: [CraftEngine] Found 7 issue(s) in file configuration/kitchen.yml:',
            '[19:20:15 ERROR]: [CookeryAcceptanceProbe] Kitchen recipe fixture failed',
        ]
        # These were emitted at WARN even though the live bamboo ticker and recipe loading failed.
        self.assertEqual(lines, runtime_failures('\n'.join(lines + [
            '[19:20:01 WARN]: [CraftEngine] 新版本26.9.2已发布：https://modrinth.com/plugin/craftengine/',
            '[19:20:00 WARN]: You are running the latest build for your Minecraft version (1.21.11)',
        ])))

    def test_bamboo_requires_processing_cancel_and_restart_evidence(self) -> None:
        manifest = {'recipes': {'bamboo_tray_recipes': 28}}
        report = {'failures': [], 'checkedRecipes': 28, 'phase': 'IN_PROGRESS_SAVED',
                  'environmentChecks': ['sky_drying', 'opaque_pause', 'glass_drying', 'stacked_drying',
                                        'invalid_dripstone_pause', 'water_dripstone_wetting'],
                  'states': {name: {'items': [item, 'STONE' if name == 'drying' else 'AIR', 'AIR', 'AIR'],
                                    'amounts': [amount, 3 if name == 'drying' else 0, 0, 0],
                                    'progress': [57, 0, 0, 0], 'completion': [0, 0, 0, 0]}
                             for name, item, amount in [('drying', 'WET_SPONGE', 2), ('sheltered', 'KELP', 3),
                                                        ('dripping', 'CLAY', 2), ('stacked', 'MUD', 1)]}}
        validate_bamboo(report, manifest, 1)
        for field, bad in [('items', ['SPONGE', 'STONE', 'AIR', 'AIR']), ('amounts', [1, 3, 0, 0]),
                           ('progress', [0, 0, 0, 0]), ('progress', [1800, 0, 0, 0]),
                           ('completion', [1, 0, 0, 0])]:
            with self.subTest(field=field, bad=bad), self.assertRaises(ValueError):
                validate_bamboo({**report, 'states': {**report['states'],
                                'drying': {**report['states']['drying'], field: bad}}}, manifest, 1)
        with self.assertRaises(ValueError):
            validate_bamboo({**report, 'environmentChecks': []}, manifest, 1)
        with self.assertRaises(ValueError):
            validate_bamboo(report, manifest, 2)
        report.update(phase='COMPLETED_RELOADED', reloadedTrays=4,
                      completedOutputs={'sponge': 2, 'dried_kelp': 3, 'mud': 2, 'clay': 1},
                      takenSponge=1, reverseWetting=True, canceledExtractions=2,
                      acceptedExtractions=1, emptyNoDuplicate=True, hopperCount=1)
        validate_bamboo(report, manifest, 2)
        for field, bad in (('checkedRecipes', 0), ('completedOutputs', {}), ('reloadedTrays', 0),
                           ('takenSponge', 2), ('reverseWetting', False), ('canceledExtractions', 0),
                           ('acceptedExtractions', 2), ('emptyNoDuplicate', False), ('hopperCount', 2)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_bamboo({**report, field: bad}, manifest, 2)
        report = {'failures': [], 'checkedRecipes': 28, 'phase': 'EMPTY_RELOADED',
                  'reloadedTrays': 4, 'emptyNoDuplicate': True, 'hopperCount': 1}
        validate_bamboo(report, manifest, 3)
        with self.assertRaises(ValueError):
            validate_bamboo({**report, 'hopperCount': 2}, manifest, 3)

    def test_millstone_requires_animal_progress_and_three_restart_phases(self) -> None:
        manifest = {'furniture': 1}
        report = {'failures': [], 'checkedFurniture': 1, 'phase': 'IN_PROGRESS_SAVED',
                  'rawCount': 2, 'progressDegrees': [9, 9], 'completionEvents': 0, 'spawnedOutput': 0}
        validate_millstone(report, manifest, 1)
        for bad in ([], [0, 0], [360, 360], [float('nan'), 9]):
            with self.subTest(progress=bad), self.assertRaises(ValueError):
                validate_millstone({**report, 'progressDegrees': bad}, manifest, 1)
        with self.assertRaisesRegex(ValueError, 'COMPLETED_RELOADED'):
            validate_millstone(report, manifest, 2)
        report.update(phase='COMPLETED_RELOADED', reloadedFurniture=1, emptyNoDuplicate=True,
                      completionEvents=1, spawnedOutput=10, outputCount=10, animalRestored=True)
        validate_millstone(report, manifest, 2)
        for field, bad in (('reloadedFurniture', 0), ('completionEvents', 2), ('spawnedOutput', 5),
                           ('outputCount', 0), ('animalRestored', False), ('emptyNoDuplicate', False)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_millstone({**report, field: bad}, manifest, 2)
        with self.assertRaisesRegex(ValueError, 'EMPTY_RELOADED'):
            validate_millstone(report, manifest, 3)
        report.update(phase='EMPTY_RELOADED', completionEvents=0, spawnedOutput=0)
        validate_millstone(report, manifest, 3)
        with self.assertRaises(ValueError):
            validate_millstone({**report, 'spawnedOutput': 10}, manifest, 3)

    def test_pot_probe_requires_real_restart_and_partial_serving_evidence(self) -> None:
        manifest = {'recipes': {'pot_recipes': 219}}
        report = {'failures': [], 'checkedPlans': 219, 'exactRecipes': 195, 'distinctRecipes': 24,
                  'phase': 'IN_PROGRESS_SAVED', 'stirs': [1, 1]}
        validate_pots(report, manifest, 1)
        with self.assertRaisesRegex(ValueError, 'COMPLETED_RELOADED'):
            validate_pots(report, manifest, 2)
        report.update(phase='COMPLETED_RELOADED', reloadedPots=2, servings=[2, 0], emptyNoDuplicate=True)
        validate_pots(report, manifest, 2)
        for field, bad in (('checkedPlans', 218), ('exactRecipes', 0), ('reloadedPots', 0),
                           ('servings', [3, 1]), ('emptyNoDuplicate', False)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_pots({**report, field: bad}, manifest, 2)

    def test_runtime_counts_must_match_the_new_manifest(self) -> None:
        manifest = {'blocks': 12, 'states': {'example': 180}, 'items': 252}
        report = {'failures': [], 'checkedBlocks': 12, 'checkedStates': 180, 'checkedItems': 252,
                  'nonemptyContainers': 2, 'placedBlocks': 12, 'reloadedBlocks': 0}
        validate_kitchen(report, manifest, 1)
        with self.assertRaisesRegex(ValueError, 'installed manifest'):
            validate_kitchen({**report, 'checkedItems': 165}, manifest, 1)
        with self.assertRaisesRegex(ValueError, 'installed manifest'):
            validate_kitchen(report, manifest, 2)

    def test_registration_cannot_stand_in_for_resumed_processing(self) -> None:
        manifest = {'recipes': {'teapot_result': 11, 'accurate_foods': 4, 'chopping_board_raws': 16, 'recipes': 20},
                    'teaCups': 8, 'soupBases': 9}
        report = {'failures': [], 'checkedPlans': 31, 'checkedCrafting': 20, 'teaCups': 8, 'soupBases': 9,
                  'phase': 'IN_PROGRESS_SAVED'}
        validate_recipes(report, manifest, 1)
        with self.assertRaisesRegex(ValueError, 'COMPLETED_RELOADED'):
            validate_recipes(report, manifest, 2)
        report.update(phase='COMPLETED_RELOADED', reloadedBatches=3, steamedOutputs=4, teaServings=8,
                      cuttingDrops={'kaleidoscopecookery:raw_cut_small_meats': 3, 'minecraft:bone': 1})
        validate_recipes(report, manifest, 2)
        with self.assertRaisesRegex(ValueError, 'conservation'):
            validate_recipes({**report, 'cuttingDrops': {'minecraft:bone': 2}}, manifest, 2)

    def test_resource_pack_completion_survives_console_colour_codes(self) -> None:
        with TemporaryDirectory() as folder:
            log = Path(folder) / 'server.log'
            log.write_text('[18:28:48 INFO]: \x1b[97m资源包生成完成\x1b[37m（\x1b[33m1893\x1b[37m毫秒）\x1b[0m', encoding='utf-8')
            self.assertIn('资源包生成完成（1893毫秒）', console_text(log))

    def test_stockpot_coverage_and_partial_servings_are_required(self) -> None:
        manifest = {'recipes': {'teapot_result': 11, 'accurate_foods': 4, 'chopping_board_raws': 16,
                                'stockpot_recipes': 101, 'recipes': 33}, 'teaCups': 8, 'soupBases': 9}
        report = {'failures': [], 'checkedPlans': 132, 'checkedCrafting': 33, 'teaCups': 8, 'soupBases': 9,
                  'phase': 'COMPLETED_RELOADED', 'reloadedBatches': 3, 'steamedOutputs': 4, 'teaServings': 8,
                  'cuttingDrops': {'kaleidoscopecookery:raw_cut_small_meats': 3, 'minecraft:bone': 1},
                  'exactStockpotRecipes': 75, 'distinctStockpotRecipes': 26, 'reloadedStockpots': 2,
                  'stockpotServings': [2, 0], 'stockpotLiquidOffset': -0.105, 'stockpotEmptyNoDuplicate': True}
        validate_recipes(report, manifest, 2)
        for field, bad in (('distinctStockpotRecipes', 0), ('reloadedStockpots', 0),
                           ('stockpotServings', [3, 1]), ('stockpotEmptyNoDuplicate', False),
                           ('stockpotLiquidOffset', 0)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_recipes({**report, field: bad}, manifest, 2)

    def test_known_missing_appliance_remains_an_explicit_fixture_gap(self) -> None:
        self.assertTrue(missing_block_error('[18:00 ERROR]: [KaleidoscopeCookeryPlugin] craftengine.registry.blocks.missing'))
        self.assertTrue(missing_block_error('[18:00 ERROR]: [KaleidoscopeCookeryPlugin] - kaleidoscopecookery:pot    配置：custom/pot.yml'))
        self.assertFalse(missing_block_error('[18:00 ERROR]: [CraftEngine] - kaleidoscopecookery:pot    配置：custom/pot.yml'))
        self.assertFalse(missing_block_error('[18:00 ERROR]: [KaleidoscopeCookeryPlugin] - kaleidoscopecookery:pot_new    配置：custom/pot.yml'))
        self.assertFalse(missing_block_error('[18:00 ERROR]: Registry loading errors:'))
        self.assertFalse(missing_block_error('[18:00 ERROR]: [KaleidoscopeCookeryPlugin] failed while saving inventory'))

    def test_cannot_launch_without_explicit_eula_flag(self) -> None:
        with self.assertRaisesRegex(ValueError, 'accept-eula'):
            prepare('paper', ACCEPTANCE / 'unused', False)

    def test_cannot_reuse_primary_instances_or_escape_acceptance_root(self) -> None:
        for root in (ACCEPTANCE, ACCEPTANCE.parent, Path.cwd().anchor):
            with self.subTest(root=root), self.assertRaisesRegex(ValueError, 'separate directory'):
                prepare('paper', Path(root), True)

    def test_invalid_kitchen_archive_preserves_food_fixture(self) -> None:
        for name, content, checksum in (('cookery_kitchen/../../escape', b'a', None),
                                        ('cookery_kitchen/../default_assets/injected.yml', b'a', None),
                                        ('cookery_kitchen/pack.yml', b'a', 'bad')):
            with self.subTest(name=name), TemporaryDirectory() as folder:
                root = Path(folder).resolve()
                instance = root / 'run' / 'paper'
                food = instance / 'plugins/CraftEngine/resources/cookery_foods'
                food.mkdir(parents=True)
                (food / 'sentinel').write_text('preserve', encoding='utf-8')
                manifest = {'sourceCommit': COMMIT, 'target': {'minecraft': '1.21.11', 'craftEngine': '26.9.1'},
                            'files': {name: checksum or hashlib.sha256(content).hexdigest()}}
                archive = root / 'pack.zip'
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr('manifest.json', json.dumps(manifest))
                    package.writestr(name, content)
                with patch('run_enchantment_acceptance.ACCEPTANCE', root), self.assertRaises(ValueError):
                    install_kitchen(instance, archive)
                self.assertEqual('preserve', (food / 'sentinel').read_text(encoding='utf-8'))
                self.assertFalse((instance / 'plugins/CraftEngine/fixture-backups').exists())

    def test_kitchen_install_keeps_original_foods_outside_active_resources(self) -> None:
        with TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            instance = root / 'run' / 'paper'
            food = instance / 'plugins/CraftEngine/resources/cookery_foods'
            food.mkdir(parents=True)
            (food / 'sentinel').write_text('preserve', encoding='utf-8')
            name, content = 'cookery_kitchen/pack.yml', b'namespace: kaleidoscopecookery\n'
            manifest = {'sourceCommit': COMMIT, 'target': {'minecraft': '1.21.11', 'craftEngine': '26.9.1'},
                        'files': {name: hashlib.sha256(content).hexdigest()}}
            archive = root / 'pack.zip'
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('manifest.json', json.dumps(manifest))
                package.writestr(name, content)
            with patch('run_enchantment_acceptance.ACCEPTANCE', root):
                self.assertEqual(manifest, install_kitchen(instance, archive))
            engine = instance / 'plugins/CraftEngine'
            self.assertFalse(food.exists())
            self.assertEqual('preserve', (engine / 'fixture-backups/cookery_foods/sentinel').read_text(encoding='utf-8'))
            self.assertEqual(content, (engine / 'resources' / name).read_bytes())


if __name__ == '__main__':
    unittest.main()
