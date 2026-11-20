"""Source/rules/serialization guardrails for native village loot and source templates."""
import copy
import contextlib
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZIP_DEFLATED

from audit_upstream import AuditError, read_blobs
from cookery_recipes import read_sources
from cookery_village import (BIOMES, COMMIT, GEN, JAVA, JAVA_HASHES, NbtReader, NORMAL, RES,
    RULE_HASHES, STRUCTURES, TOKEN_KEY, expected_loot, export, json_bytes, recipe_choices, structure_manifest)
from export_cookery_village import main as export_main

SOURCE = Path(__file__).resolve().parents[2] / '.references/KaleidoscopeCookery'


class VillageExportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files, cls.manifest = export(SOURCE)
        cls.sources = read_sources(SOURCE)
        cls.java = read_blobs(SOURCE, COMMIT, list(JAVA_HASHES))

    def test_all_five_original_structures_are_retained_without_runtime_ce_ids(self):
        sizes = ((9, 10, 10), (8, 10, 7), (8, 9, 11), (7, 8, 8), (9, 10, 11))
        for biome, size in zip(BIOMES, sizes):
            raw = self.files['source/structure/' + biome + '_kitchen.nbt']
            self.assertEqual(STRUCTURES[biome], hashlib.sha256(raw).hexdigest())
            row = self.manifest['structures'][biome]
            self.assertEqual(list(size), row['size']); self.assertEqual(size[0] * size[1] * size[2], row['blocks'])
            self.assertEqual({'min': [0, 0, 0], 'max': [v - 1 for v in size]}, row['bounds'])
            self.assertEqual(3465, row['dataVersion']); self.assertEqual(4, row['weight'])
            self.assertEqual('minecraft:legacy_single_pool_element', row['elementType'])
            self.assertEqual('rigid', row['projection']); self.assertEqual('NOT_IMPLEMENTED', row['runtime'])
            nbt = row['blockEntities']
            self.assertEqual(2, sum('LootTable' in block['nbt'] for block in nbt))
            self.assertEqual(1, sum(block['block']['Name'] == 'minecraft:jigsaw' for block in nbt))
            self.assertEqual(1, sum(block['block']['Name'] == 'minecraft:campfire' for block in nbt))
            self.assertEqual(1, sum(block['Name'].endswith(':stove') for block in row['customBlocks']))
        snowy = self.manifest['structures']['snowy']['customBlocks']
        self.assertEqual(9, sum(block['Name'].endswith(':chili_ristra') for block in snowy))
        self.assertTrue(any(block['Properties'].get('sheared') == 'true' for block in snowy))
        self.assertFalse(any('/structure/' in key and key.startswith('data/') for key in self.files))

    def test_native_tables_preserve_original_roll_weights_order_count_and_sequence(self):
        for hidden, name in enumerate(('village_chest', 'village_hide_chest')):
            source = expected_loot(bool(hidden)); native = json.loads(self.files['data/kaleidoscopecookery/loot_table/chest/' + name + '.json'])
            self.assertEqual(source['random_sequence'], native['random_sequence'])
            old, new = source['pools'][0], native['pools'][0]
            self.assertEqual(old['rolls'], new['rolls']); self.assertEqual(old['bonus_rolls'], new['bonus_rolls'])
            self.assertEqual([e['weight'] for e in old['entries']], [e['weight'] for e in new['entries']])
            self.assertEqual(70 if hidden else 104, sum(e['weight'] for e in new['entries']))
            for before, after in zip(old['entries'], new['entries']):
                if before['name'].endswith(':recipe_item'):
                    self.assertEqual('minecraft:loot_table', after['type'])
                    self.assertEqual('kaleidoscopecookery:chest/recipe_randomly', after['value'])
                    self.assertNotIn('functions', after)
                else:
                    self.assertEqual('minecraft:paper', after['name'])
                    self.assertEqual(before.get('functions', []), after['functions'][1:])
                    self.assertIn('v1|item|' + before['name'], after['functions'][0]['tag'])
                    self.assertIn(TOKEN_KEY, after['functions'][0]['tag'])

    def test_nested_recipe_draw_is_one_equal_native_choice_in_source_food_order(self):
        table = json.loads(self.files['data/kaleidoscopecookery/loot_table/chest/recipe_randomly.json'])
        pool = table['pools'][0]
        self.assertEqual(1, pool['rolls']); self.assertEqual(37, len(pool['entries']))
        self.assertNotIn('random_sequence', table)  # It must reuse the caller's LootContext RNG.
        for i, entry in enumerate(pool['entries']):
            self.assertEqual(1, entry['weight']); self.assertEqual('minecraft:item', entry['type'])
            self.assertIn('v1|recipe|' + str(i) + '"', entry['functions'][0]['tag'])
        self.assertEqual('kaleidoscope_cookery:dark_cuisine', self.manifest['recipes'][0]['sourceResult'])
        self.assertEqual('kaleidoscope_cookery:golden_salad', self.manifest['recipes'][-1]['sourceResult'])

    def test_blank_recipe_branch_and_repeated_first_ingredients_are_not_dropped(self):
        choices = {row['sourceResult'].split(':')[1]: row for row in self.manifest['recipes']}
        self.assertIsNone(choices['dark_cuisine']['recipe']); self.assertIsNone(choices['nether_style_sashimi']['recipe'])
        self.assertEqual(['minecraft:slime_ball'] * 6, choices['slime_ball_meal']['recipe']['ingredients'])
        self.assertEqual('stockpot', choices['dough_drop_soup']['recipe']['type'])
        self.assertEqual('minecraft:water_bucket', choices['dough_drop_soup']['recipe']['soupBase'])
        self.assertEqual('pot', choices['dongpo_pork']['recipe']['type'])
        self.assertEqual('kaleidoscopecookery:wild_rice', self.manifest['itemMappings']['kaleidoscope_cookery:wild_rice'])
        self.assertNotIn('wild_rice_seed', json.dumps(self.manifest))

    def test_ambiguous_source_first_recipe_fails_instead_of_inventing_order(self):
        sources = copy.deepcopy(self.sources)
        original = sources.recipes['kaleidoscope_cookery:pot/slime_ball_meal']
        sources.recipes['kaleidoscope_cookery:pot/another_slime_ball_meal'] = copy.deepcopy(original)
        with self.assertRaisesRegex(AuditError, 'ambiguous first recipe'):
            recipe_choices(self.java[JAVA + 'init/registry/FoodBiteRegistry.java'], sources)

    def test_source_java_rule_change_fails_closed(self):
        paths = list(JAVA_HASHES) + [RES + 'structures/village/houses/' + name + '_kitchen.nbt' for name in BIOMES]
        paths += [GEN + 'loot_tables/chest/' + name + '.json' for name in ('village_chest', 'village_hide_chest')]
        paths += [RES + 'worldgen/processor_list/crop_replace.json', JAVA + 'event/AddVillageStructuresEvent.java']
        blobs = read_blobs(SOURCE, COMMIT, paths)
        for path in RULE_HASHES:
            with self.subTest(path=path), patch('cookery_village.read_blobs', return_value={**blobs, path: blobs[path] + b'\n'}):
                with self.assertRaisesRegex(AuditError, 'unsupported source rules'):
                    export(SOURCE)

    def test_unknown_loot_function_or_count_does_not_silently_import(self):
        paths = list(self.manifest['sourceFiles'])
        blobs = read_blobs(SOURCE, COMMIT, paths)
        path = GEN + 'loot_tables/chest/village_chest.json'
        changed = json.loads(blobs[path]); changed['pools'][0]['entries'][0]['functions'][0]['count']['max'] = 99
        with patch('cookery_village.read_blobs', return_value={**blobs, path: json_bytes(changed)}):
            with self.assertRaisesRegex(AuditError, 'unsupported loot rules'):
                export(SOURCE)

    def test_structure_hash_mutation_and_malformed_nbt_are_rejected(self):
        raw = self.files['source/structure/plains_kitchen.nbt']
        with self.assertRaisesRegex(AuditError, 'unsupported structure'):
            structure_manifest(raw + b'\0', 'plains')
        for raw in (b'not gzip', gzip.compress(b'\x0a\0\0\x63'), gzip.compress(b'\x0a\0\0\x09\0\x01x\x03\xff\xff\xff\xff\0')):
            with self.subTest(raw=raw), self.assertRaises(AuditError):
                NbtReader(raw).read()

    def test_duplicate_compound_names_and_trailing_payload_fail_closed(self):
        for payload in (b'\x0a\0\0\x01\0\x01x\0\x01\0\x01x\0\0', b'\x0a\0\0\0extra',
                        b'\x0a\0\0\x09\0\x01x\x63\0\0\0\0\0'):
            with self.subTest(payload=payload), self.assertRaises(AuditError):
                NbtReader(gzip.compress(payload)).read()

    def test_inflation_and_compressed_budgets_are_bounded(self):
        with self.assertRaisesRegex(AuditError, 'compressed structure is too large'):
            NbtReader(b'x' * 1_048_577)
        with self.assertRaisesRegex(AuditError, 'inflated structure is too large'):
            NbtReader(gzip.compress(b'\0' * 8_388_609))

    def test_semantic_validator_rejects_bad_indices_positions_unknown_blocks_and_properties(self):
        raw = self.files['source/structure/plains_kitchen.nbt']
        root = NbtReader(raw).read()
        mutations = []
        changed = copy.deepcopy(root); changed['blocks'][0]['state'] = len(changed['palette']); mutations.append(changed)
        changed = copy.deepcopy(root); changed['blocks'][0]['pos'] = changed['size']; mutations.append(changed)
        changed = copy.deepcopy(root); changed['blocks'][0]['pos'] = changed['blocks'][1]['pos']; mutations.append(changed)
        changed = copy.deepcopy(root); changed['palette'][0]['Name'] = 'othermod:unexpected'; mutations.append(changed)
        changed = copy.deepcopy(root); changed['palette'][0]['Properties'] = []; mutations.append(changed)
        changed = copy.deepcopy(root)
        next(state for state in changed['palette'] if state['Name'].endswith(':stove'))['Properties']['facing'] = 'up'
        mutations.append(changed)
        for changed in mutations:
            with self.subTest(change=changed['blocks'][0]), patch.object(NbtReader, 'read', return_value=changed):
                with self.assertRaises(AuditError): structure_manifest(raw, 'plains')

    def test_every_recipe_and_tag_dependency_is_audited_from_the_fixed_commit(self):
        self.assertEqual(self.sources.files, self.manifest['recipeSourceFiles'])
        for choice in self.manifest['recipes']:
            if choice['recipe'] is not None:
                origin = choice['recipe']['source']
                self.assertEqual(origin['sha256'], self.manifest['recipeSourceFiles'][origin['path']])

    def test_cli_writes_a_deterministic_new_archive_and_refuses_overwrite_or_bad_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            def run(*args):
                with patch('sys.argv', ['export_cookery_village', '--source', str(SOURCE), *map(str, args)]), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    return export_main()
            first, second, resource = directory / 'a.zip', directory / 'b.zip', directory / 'runtime.json'
            self.assertEqual(0, run('--output', first, '--runtime-manifest', resource))
            original = first.read_bytes()
            self.assertEqual(1, run('--output', first))
            self.assertEqual(original, first.read_bytes())
            self.assertEqual(0, run('--output', second)); self.assertEqual(original, second.read_bytes())
            self.assertEqual(0, run('--output', first, '--runtime-manifest', resource, '--check'))
            first.write_bytes(b'not a zip')
            self.assertEqual(1, run('--output', first, '--check'))

    def test_runtime_resource_matches_source_and_no_natural_generation_is_claimed(self):
        resource = Path(__file__).resolve().parents[1] / 'src/main/resources/village-loot.json'
        self.assertEqual(json_bytes(self.manifest), resource.read_bytes())
        self.assertEqual('NOT_IMPLEMENTED', self.manifest['naturalVillageIntegration'])
        self.assertEqual('NOT_TESTED', self.manifest['runtime'])

    def test_packaged_template_archives_match_the_exact_five_pinned_sources(self):
        directory = Path(__file__).resolve().parents[1] / 'src/main/resources/village/source'
        self.assertEqual(sorted(biome + '_kitchen.nbt' for biome in BIOMES), sorted(p.name for p in directory.iterdir()))
        for biome in BIOMES:
            filename = biome + '_kitchen.nbt'
            raw = (directory / filename).read_bytes()
            self.assertEqual(self.files['source/structure/' + filename], raw)
            self.assertEqual(STRUCTURES[biome], hashlib.sha256(raw).hexdigest())
            self.assertEqual(self.manifest['sourceFiles'][RES + 'structures/village/houses/' + filename],
                             hashlib.sha256(raw).hexdigest())
            self.assertEqual(self.manifest['structures'][biome], structure_manifest(raw, biome))

    def test_cli_template_archives_are_reproducible_and_refuse_existing_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            def run(*args):
                with patch('sys.argv', ['export_cookery_village', '--source', str(SOURCE), *map(str, args)]), \
                        patch('export_cookery_village.export', return_value=(self.files, self.manifest)), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    return export_main()
            first, second = directory / 'a.zip', directory / 'b.zip'
            a, b = directory / 'a-sources', directory / 'b-sources'
            self.assertEqual(0, run('--output', first, '--runtime-template-dir', a))
            self.assertEqual(0, run('--output', second, '--runtime-template-dir', b))
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(sorted(p.name for p in a.iterdir()), sorted(p.name for p in b.iterdir()))
            for biome in BIOMES:
                filename = biome + '_kitchen.nbt'
                self.assertEqual((a / filename).read_bytes(), (b / filename).read_bytes())
            self.assertEqual(0, run('--output', first, '--runtime-template-dir', a, '--check'))
            self.assertEqual(1, run('--output', directory / 'not-created.zip', '--runtime-template-dir', a))
            self.assertFalse((directory / 'not-created.zip').exists())

    def test_cli_archive_check_rejects_missing_extra_and_mutated_template_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            archive, templates = directory / 'a.zip', directory / 'sources'
            def run(*args):
                with patch('sys.argv', ['export_cookery_village', '--source', str(SOURCE), '--output', str(archive),
                                       '--runtime-template-dir', str(templates), *args]), \
                        patch('export_cookery_village.export', return_value=(self.files, self.manifest)), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    return export_main()
            self.assertEqual(0, run())
            original_archive = archive.read_bytes()
            for biome in BIOMES:
                path = templates / (biome + '_kitchen.nbt')
                original = path.read_bytes()
                for mutation in (None, original[:-1], original + b'\0', bytes([original[0] ^ 1]) + original[1:]):
                    if mutation is None:
                        path.unlink()
                    else:
                        path.write_bytes(mutation)
                    self.assertEqual(1, run('--check'), (biome, mutation is None))
                    path.write_bytes(original)
            (templates / 'extra.nbt').write_bytes(b'extra')
            self.assertEqual(1, run('--check'))
            (templates / 'extra.nbt').unlink()
            self.assertEqual(0, run('--check'))
            self.assertEqual(original_archive, archive.read_bytes())

    def test_cli_check_refuses_wrong_sizes_before_reading_archive_or_manifest_payloads(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            archive, resource = directory / 'a.zip', directory / 'runtime.json'
            def run(*args):
                with patch('sys.argv', ['export_cookery_village', '--source', str(SOURCE), '--output', str(archive), *args]), \
                        patch('export_cookery_village.export', return_value=(self.files, self.manifest)), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    return export_main()
            first = sorted(self.files)[0]
            with ZipFile(archive, 'x', compression=ZIP_DEFLATED) as output:
                for name, raw in sorted(self.files.items()):
                    output.writestr(name, raw + b'\0' if name == first else raw)
            with patch.object(ZipFile, 'read', side_effect=AssertionError('wrong-size entry must not be read')):
                self.assertEqual(1, run('--check'))
            archive.unlink()
            self.assertEqual(0, run('--runtime-manifest', str(resource)))
            resource.write_bytes(json_bytes(self.manifest) + b'\0')
            with patch.object(Path, 'read_bytes', side_effect=AssertionError('wrong-size manifest must not be read')):
                self.assertEqual(1, run('--runtime-manifest', str(resource), '--check'))


if __name__ == '__main__':
    unittest.main()
