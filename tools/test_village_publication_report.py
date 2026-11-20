import contextlib
from copy import deepcopy
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from cookery_village import BIOMES, COMMIT, STRUCTURES
from village_publication_report import MAX_REPORT_BYTES, ReportError, SLOTS, main, read_json, validate, POOL_CLASS, RULE_CLASS, TEMPLATE_CLASS


def fixture():
    run_id = 'c2bf5619-ab88-42ce-bdd7-2b6ae704ff0c'
    artifacts = {name: {'bytes': index + 10, 'sha256': str(index + 1) * 64}
                 for index, name in enumerate(('production', 'probe', 'craftEngine', 'server'))}
    expected = {'schema': 'village-publication-expected-v1', 'runId': run_id,
                'platform': 'Paper', 'artifacts': artifacts}
    common = {'schema': 'village-publication-smoke-v1', 'runId': run_id, 'registeredAt': '2026-11-20T12:00:00Z',
              'artifacts': deepcopy(artifacts), 'minecraftVersion': '1.21.11', 'platform': 'Paper',
              'serverVersion': 'Paper 1.21.11-132', 'packIdentity': 'NOT_CERTIFIED_BY_THIS_PROBE'}
    registration = {**deepcopy(common), 'status': 'NOT_RUN', 'passed': False, 'phase': 'REGISTERED_DURING_ON_LOAD'}
    def reference(kind, key, contents):
        identities = [item['identity'] if type(item) is dict else item for item in contents]
        return {'present': True, 'type': kind, 'identity': kind + '@' + format(key, 'x'), 'size': len(contents),
                'contents': deepcopy(contents), 'contentsSha256': hashlib.sha256('\n'.join(identities).encode()).hexdigest()}
    rows = []
    for index, (name, kind) in enumerate(SLOTS.items()):
        if kind == 'INTEGER':
            before = staged = {'value': -2_147_483_648}
        elif kind == 'CACHE_ENTRY':
            before = {'present': False, 'type': 'ABSENT', 'identity': 'null'}
            staged = {'present': True, 'type': 'java.util.Optional', 'identity': 'java.util.Optional@' + format(index + 100, 'x'),
                      'templateIdentity': TEMPLATE_CLASS + '@' + format(index + 200, 'x')}
        elif name == 'processor:list':
            before = reference('java.util.ImmutableCollections$ListN', 300, [])
            staged = reference('java.util.ImmutableCollections$List12', 301, [RULE_CLASS + '@f00'])
        else:
            biome_index = BIOMES.index(name.split('/')[1])
            old_element = POOL_CLASS + 'EmptyPoolElement@' + format(400 + biome_index, 'x')
            kitchen = POOL_CLASS + 'LegacySinglePoolElement@' + format(500 + biome_index, 'x')
            if name.endswith(':raw'):
                before_contents = [{'identity': 'com.mojang.datafixers.util.Pair@' + format(600 + biome_index, 'x'),
                                    'elementIdentity': old_element, 'weight': 2}]
                after_contents = [{'identity': 'com.mojang.datafixers.util.Pair@' + format(700 + biome_index, 'x'),
                                   'elementIdentity': old_element, 'weight': 2},
                                  {'identity': 'com.mojang.datafixers.util.Pair@' + format(800 + biome_index, 'x'),
                                   'elementIdentity': kitchen, 'weight': 4}]
                before = reference('java.util.ArrayList', 900 + biome_index, before_contents)
                staged = reference('java.util.ImmutableCollections$List12', 1000 + biome_index, after_contents)
            else:
                before = reference('it.unimi.dsi.fastutil.objects.ObjectArrayList', 1100 + biome_index, [old_element] * 2)
                staged = reference('it.unimi.dsi.fastutil.objects.ObjectArrayList', 1200 + biome_index, [old_element] * 2 + [kitchen] * 4)
        rows.append({'name': name, 'kind': kind, 'before': before, 'staged': staged,
                     'publishedWitness': deepcopy(staged), 'restoredWitness': deepcopy(before), 'changed': kind != 'INTEGER'})
    cache = {'identity': 'java.util.concurrent.ConcurrentHashMap@a12', 'size': 1,
             'entries': [{'key': 'other:preserved_template', 'value': {
                 'present': True, 'type': 'java.util.Optional', 'identity': 'java.util.Optional@b12',
                 'templateIdentity': TEMPLATE_CLASS + '@c12'}}]}
    report = {**deepcopy(common), 'status': 'PASS', 'passed': True, 'completedAt': '2026-11-20T12:00:05Z',
              'phase': 'CE_INITIAL_AFTER_ENABLE', 'minecraftVersion': '1.21.11', 'platform': 'Paper',
              'naturalVillageIntegration': 'NOT_ACTIVATED', 'runtimeScope': 'NATIVE_COLD_START_PUBLICATION_ROLLBACK_ONLY',
              'controlledFailureBoundary': 'UNPROVEN_IF_UNEXPECTED_PARTIAL_STATE',
              'nativeWorldCountBefore': 0, 'nativeWorldCountAfter': 0,
              'publishedWitnessObserved': True, 'sentinelObserved': True, 'cleanRollback': True,
              'fullOriginalsRestored': True, 'scopeClosed': True, 'writerAvailableAfterClose': True,
              'writerExcludedInsideWindow': True, 'transactionStatus': 'ROLLED_BACK', 'rollbackFailures': [], 'unconfirmedSlots': [],
              'templateCacheBefore': deepcopy(cache), 'templateCacheRestored': deepcopy(cache),
              'failure': None, 'artifacts': deepcopy(artifacts), 'sourceTemplates': [
                  {'biome': biome, 'location': 'kaleidoscopecookery:village/houses/' + biome + '_kitchen',
                   'sourceCommit': COMMIT, 'sourceSha256': STRUCTURES[biome], 'targetDataVersion': 4671}
                  for biome in BIOMES], 'slots': rows, 'expectedChangedSlots': 16, 'expectedNoOpSlots': 5}
    return registration, report, expected


class VillagePublicationReportTest(unittest.TestCase):
    def test_complete_receipt_accepts_only_its_narrow_scope_and_accounts_for_native_no_op_maximums(self):
        result = validate(*fixture())
        self.assertEqual('PASS', result['result'])
        self.assertEqual(16, result['changedSlots'])
        self.assertEqual(5, result['noOpSlots'])
        self.assertEqual('NOT_ACTIVATED', result['naturalVillageIntegration'])

    def test_genuinely_changed_native_size_caches_are_accounted_without_requiring_probe_mutation(self):
        registration, report, expected = fixture()
        for row in report['slots']:
            if row['kind'] == 'INTEGER':
                row['before'] = {'value': 20}
                row['restoredWitness'] = {'value': 20}
                row['changed'] = True
        report['expectedChangedSlots'], report['expectedNoOpSlots'] = 21, 0
        self.assertEqual(21, validate(registration, report, expected)['changedSlots'])

    def test_pass_and_every_required_proof_flag_are_actual_booleans(self):
        for key in ('passed', 'publishedWitnessObserved', 'sentinelObserved', 'cleanRollback',
                    'fullOriginalsRestored', 'scopeClosed', 'writerAvailableAfterClose', 'writerExcludedInsideWindow'):
            for value in (False, 1, 'true', None):
                with self.subTest(key=key, value=value):
                    registration, report, expected = fixture()
                    report[key] = value
                    with self.assertRaises(ReportError):
                        validate(registration, report, expected)

    def test_missing_or_failed_registration_or_final_receipt_never_passes(self):
        for target, key, value in (('registration', 'status', 'PASS'), ('registration', 'passed', 0),
                                   ('registration', 'runId', 'other'), ('report', 'status', 'NOT_RUN'),
                                   ('report', 'status', 'FAIL'), ('report', 'runId', 'other'),
                                   ('report', 'failure', {'transactionStatus': 'INDETERMINATE'})):
            with self.subTest(target=target, key=key):
                registration, report, expected = fixture()
                (registration if target == 'registration' else report)[key] = value
                with self.assertRaises(ReportError):
                    validate(registration, report, expected)
        for index in (0, 1):
            values = list(fixture())
            values[index] = {}
            with self.assertRaises(ReportError):
                validate(*values)

    def test_actual_artifacts_are_compared_to_independently_frozen_sizes_and_hashes(self):
        for name in ('production', 'probe', 'craftEngine', 'server'):
            for field, value in (('bytes', 999), ('bytes', True), ('sha256', 'f' * 64)):
                with self.subTest(name=name, field=field):
                    registration, report, expected = fixture()
                    report['artifacts'][name][field] = value
                    with self.assertRaises(ReportError):
                        validate(registration, report, expected)
        registration, report, expected = fixture()
        del report['artifacts']['craftEngine']
        with self.assertRaises(ReportError):
            validate(registration, report, expected)

    def test_complete_source_provenance_cannot_be_substituted_or_repeated(self):
        for key, value in (('biome', 'jungle'), ('location', 'other:house'), ('sourceCommit', '0' * 40),
                           ('sourceSha256', '0' * 64), ('targetDataVersion', 3465)):
            registration, report, expected = fixture()
            report['sourceTemplates'][0][key] = value
            with self.assertRaises(ReportError):
                validate(registration, report, expected)
        registration, report, expected = fixture()
        report['sourceTemplates'][-1] = deepcopy(report['sourceTemplates'][0])
        with self.assertRaises(ReportError):
            validate(registration, report, expected)

    def test_each_missing_duplicated_wrong_kind_or_unrestored_slot_rejects(self):
        for index in range(len(SLOTS)):
            for mutation in ('missing', 'duplicate', 'kind', 'published', 'restored', 'changed'):
                with self.subTest(index=index, mutation=mutation):
                    registration, report, expected = fixture()
                    row = report['slots'][index]
                    if mutation == 'missing':
                        report['slots'].pop(index)
                    elif mutation == 'duplicate':
                        row['name'] = report['slots'][(index + 1) % len(SLOTS)]['name']
                    elif mutation == 'kind':
                        row['kind'] = 'OTHER'
                    elif mutation in ('published', 'restored'):
                        row[mutation + 'Witness'] = {'unknown': 'external state'}
                    else:
                        row['changed'] = not row['changed']
                    with self.assertRaises(ReportError):
                        validate(registration, report, expected)

    def test_typed_witnesses_cannot_hide_boolean_integer_confusion(self):
        registration, report, expected = fixture()
        report['slots'][0]['before']['extra'] = 1
        report['slots'][0]['restoredWitness']['extra'] = True
        with self.assertRaises(ReportError):
            validate(registration, report, expected)
        registration, report, expected = fixture()
        for row in report['slots']:
            if row['kind'] == 'INTEGER':
                row['before'] = row['staged'] = row['publishedWitness'] = row['restoredWitness'] = {'value': False}
                break
        with self.assertRaises(ReportError):
            validate(registration, report, expected)

    def test_counts_worlds_wrong_phase_or_expanded_claims_cannot_be_accepted(self):
        for key, value in (('expectedChangedSlots', 21), ('expectedNoOpSlots', True), ('nativeWorldCountBefore', False),
                           ('nativeWorldCountAfter', 1), ('phase', 'POSTWORLD'), ('minecraftVersion', '1.21.10'),
                           ('platform', 'Folia'), ('naturalVillageIntegration', 'ACCEPTED'),
                           ('runtimeScope', 'NATURAL_GENERATION'), ('controlledFailureBoundary', 'GUARANTEED')):
            with self.subTest(key=key):
                registration, report, expected = fixture()
                report[key] = value
                with self.assertRaises(ReportError):
                    validate(registration, report, expected)

    def test_bounded_reader_rejects_duplicate_nonfinite_partial_and_array_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            for raw in (b'{"passed":true,"passed":false}', b'{"x":NaN}', b'{', b'[]', b'',
                        b' ' * (MAX_REPORT_BYTES + 1), b'{"x":' + b'[' * 100 + b'0' + b']' * 100 + b'}'):
                path.write_bytes(raw)
                with self.assertRaises(ReportError):
                    read_json(path)

    def test_cli_is_read_only_requires_both_receipts_and_does_not_fill_missing_runtime_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            run_id = fixture()[2]['runId']
            stem = 'village-publication-smoke-' + run_id
            paths = [Path(directory) / name for name in (stem + '-registration.json', stem + '.json', 'expected.json')]
            for path, value in zip(paths, fixture()):
                path.write_text(json.dumps(value), encoding='utf-8')
            frozen = [path.read_bytes() for path in paths]
            arguments = ['village_publication_report', '--registration', str(paths[0]), '--report', str(paths[1]),
                         '--expected', str(paths[2])]
            with patch('sys.argv', arguments), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(0, main())
                self.assertEqual(frozen, [path.read_bytes() for path in paths])
                paths[1].unlink()
                self.assertEqual(1, main())
                self.assertFalse(paths[1].exists())

    def test_contradictory_transaction_and_full_cache_records_never_pass_despite_true_summary_flags(self):
        for key, value in (('transactionStatus', 'INDETERMINATE'), ('rollbackFailures', [{'edit': 'unknown'}]),
                           ('unconfirmedSlots', ['processor:list']), ('templateCacheRestored', {'bogus': True}),
                           ('writerExcludedInsideWindow', False), ('packIdentity', 'CERTIFIED')):
            with self.subTest(key=key):
                registration, report, expected = fixture()
                report[key] = value
                with self.assertRaises(ReportError):
                    validate(registration, report, expected)

    def test_fake_witnesses_reused_checksums_and_wrong_weights_are_rejected(self):
        for mutation in ('fake', 'hash', 'weight', 'expanded', 'processor'):
            registration, report, expected = fixture()
            row = next(row for row in report['slots'] if row['name'].endswith(':raw'))
            if mutation == 'fake':
                row['before'] = row['restoredWitness'] = {'bogus': 'before'}
                row['staged'] = row['publishedWitness'] = {'bogus': 'after'}
            elif mutation == 'hash':
                row['staged']['contentsSha256'] = '0' * 64
                row['publishedWitness'] = deepcopy(row['staged'])
            elif mutation == 'weight':
                row['staged']['contents'][-1]['weight'] = 5
                row['publishedWitness'] = deepcopy(row['staged'])
            elif mutation == 'expanded':
                expanded = next(row for row in report['slots'] if row['name'].endswith(':expanded'))
                expanded['staged']['contents'][-1] = POOL_CLASS + 'EmptyPoolElement@bad'
                expanded['staged']['contentsSha256'] = hashlib.sha256('\n'.join(expanded['staged']['contents']).encode()).hexdigest()
                expanded['publishedWitness'] = deepcopy(expanded['staged'])
            else:
                row = next(row for row in report['slots'] if row['name'] == 'processor:list')
                row['staged']['contents'][0] = 'other.UnknownProcessor@bad'
                row['staged']['contentsSha256'] = hashlib.sha256('\n'.join(row['staged']['contents']).encode()).hexdigest()
                row['publishedWitness'] = deepcopy(row['staged'])
            with self.subTest(mutation=mutation), self.assertRaises(ReportError):
                validate(registration, report, expected)

    def test_registration_phase_times_and_candidate_binding_cannot_disagree_with_final(self):
        for key, value in (('phase', 'POSTWORLD'), ('registeredAt', '2026-11-20T13:00:00Z'),
                           ('platform', 'Folia'), ('minecraftVersion', '1.21.10'), ('artifacts', {})):
            registration, report, expected = fixture()
            registration[key] = value
            with self.assertRaises(ReportError):
                validate(registration, report, expected)
        registration, report, expected = fixture()
        report['completedAt'] = '2026-11-20T11:59:59Z'
        with self.assertRaises(ReportError):
            validate(registration, report, expected)

    def test_cli_rejects_supplemental_failure_marker_and_wrong_receipt_filenames(self):
        for suffix in ('-missing-after-enable.json', '-duplicate-callback-8e8b5ade-957b-4339-b854-dc576970565b.json'):
            with tempfile.TemporaryDirectory() as directory:
                registration, report, expected = fixture()
                stem = 'village-publication-smoke-' + expected['runId']
                paths = [Path(directory) / name for name in (stem + '-registration.json', stem + '.json', 'expected.json')]
                for path, value in zip(paths, (registration, report, expected)):
                    path.write_text(json.dumps(value), encoding='utf-8')
                (Path(directory) / (stem + suffix)).write_text('{}', encoding='utf-8')
                arguments = ['check', '--registration', str(paths[0]), '--report', str(paths[1]), '--expected', str(paths[2])]
                with patch('sys.argv', arguments), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(1, main())


if __name__ == '__main__':
    unittest.main()
