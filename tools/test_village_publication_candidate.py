from copy import deepcopy
import contextlib
import io
import json
import struct
from pathlib import Path
from datetime import datetime, timedelta, timezone
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import yaml

import village_publication_candidate as candidate
from cookery_village import COMMIT, PROCESSOR_PATH
from village_publication_report import ReportError
from test_village_publication_report import fixture as receipt_fixture


class VillagePublicationCandidateTest(unittest.TestCase):
    run_id = '74dd6260-a8ce-4059-b943-fdb7fc370bc4'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.acceptance = self.root / 'acceptance'
        self.instance = self.acceptance / 'fresh-paper'
        self.output = self.acceptance / 'frozen-paper'
        self.instance.mkdir(parents=True)
        self.patcher = patch.object(candidate, 'ACCEPTANCE', self.acceptance)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        self.defining = {'production': 'plugins/production.jar', 'probe': 'plugins/probe.jar',
                         'craftEngine': 'plugins/ce.jar', 'server': 'versions/1.21.11/paper.jar'}
        self.kitchen = 'frozen-inputs/kitchen.zip'
        self.village = 'acceptance-world/datapacks/village.zip'
        self.metadata = {'sourceCommit': COMMIT, 'naturalVillageIntegration': 'NOT_IMPLEMENTED'}
        self.write('server.jar', b'launcher')
        self.write('server.properties', b'server-ip=127.0.0.1\nenable-rcon=false\nlevel-name=acceptance-world\ninitial-enabled-packs=vanilla,file/village.zip\n')
        self.write('plugins/CraftEngine/config.yml', b'misc:\n  delay-configuration-load: false\nchunk-system:\n  generation:\n    feature: true\n')
        self.write('plugins/CookeryAcceptanceProbe/config.yml', yaml.safe_dump({
            'village-publication-smoke': {'enabled': True, 'run-id': self.run_id}}).encode())
        for name, path in self.defining.items():
            if name != 'production':
                self.write(path, (name + '-defining-jar').encode())
        production = self.instance / self.defining['production']
        with ZipFile(production, 'w') as archive:
            archive.writestr('village-loot.json', json.dumps(self.metadata))
        self.write('plugins/CraftEngine/resources/cookery_kitchen/configuration/kitchen.yml', b'actual-installed-config')
        self.write('plugins/CraftEngine/resources/cookery_kitchen/pack.yml', b'pack')
        with self.zip(self.kitchen) as archive:
            archive.writestr('manifest.json', json.dumps({'sourceCommit': COMMIT, 'recipeSources': 612}))
            archive.writestr('cookery_kitchen/configuration/kitchen.yml', b'actual-installed-config')
            archive.writestr('cookery_kitchen/pack.yml', b'pack')
        with self.zip(self.village) as archive:
            archive.writestr(PROCESSOR_PATH, json.dumps({'processors': []}))
            archive.writestr('village-manifest.json', json.dumps(self.metadata))
            archive.writestr('pack.mcmeta', '{}')
        self.java = self.root / 'jdk/bin/java.exe'
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b'java-launcher')
        (self.java.parent.parent / 'lib').mkdir()
        (self.java.parent.parent / 'lib/modules').write_bytes(b'actual-module-image')

    def write(self, relative, raw):
        path = self.instance / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def zip(self, relative):
        path = self.instance / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        return ZipFile(path, 'w')

    def freeze(self):
        return candidate.freeze(self.instance, 'Paper', self.run_id, self.defining, self.kitchen,
                                self.village, self.java, self.output)

    def test_freeze_is_only_new_manifests_and_preserves_every_candidate_input(self):
        before = candidate.inventory(self.instance, candidate.input_paths(self.instance, [self.kitchen, self.village] + list(self.defining.values())))
        with patch('subprocess.Popen', side_effect=AssertionError('No process may be started')):
            frozen = self.freeze()
            candidate.verify(frozen)
        self.assertEqual('NOT_RUN', frozen['startup'])
        self.assertEqual('NOT_ACTIVATED', frozen['naturalVillageIntegration'])
        self.assertEqual(before, frozen['files'])
        self.assertEqual(before, candidate.inventory(self.instance, list(before)))
        self.assertEqual(frozen['expected'], json.loads((self.output / 'expected.json').read_text()))
        self.assertFalse((self.instance / 'eula.txt').exists())
        self.assertFalse((self.instance / 'start.ps1').exists())
        with self.assertRaises(FileExistsError):
            self.freeze()

    def test_freeze_refuses_used_world_or_existing_probe_evidence(self):
        for relative in ('acceptance-world/level.dat', 'plugins/CookeryAcceptanceProbe/village-publication-smoke-old.json'):
            with self.subTest(relative=relative):
                self.write(relative, b'previous evidence')
                with self.assertRaises(ReportError):
                    self.freeze()
                self.assertFalse(self.output.exists())
                (self.instance / relative).unlink()

    def test_wrong_or_ambiguous_config_is_refused_before_outputs(self):
        path = self.instance / 'plugins/CraftEngine/config.yml'
        original = path.read_bytes()
        for raw in (b'misc: {delay-configuration-load: true}',
                    b'misc: {}\nmisc: {}', b'a: &loop [*loop]',
                    b'misc: {delay-configuration-load: false}\nchunk-system: {generation: {feature: false}}'):
            with self.subTest(raw=raw):
                path.write_bytes(raw)
                with self.assertRaises((ReportError, yaml.YAMLError)):
                    self.freeze()
                self.assertFalse(self.output.exists())
        path.write_bytes(original)

    def test_installed_kitchen_missing_extra_and_changed_bytes_cannot_be_frozen(self):
        path = self.instance / 'plugins/CraftEngine/resources/cookery_kitchen/pack.yml'
        path.write_bytes(b'wrong')
        with self.assertRaises(ReportError):
            self.freeze()
        path.write_bytes(b'pack')
        self.write('plugins/CraftEngine/resources/cookery_kitchen/extra.yml', b'extra')
        with self.assertRaises(ReportError):
            self.freeze()

    def test_native_pack_must_be_preinstalled_dormant_and_match_production_provenance(self):
        with ZipFile(self.instance / self.village, 'a') as archive:
            archive.writestr('data/kaleidoscopecookery/worldgen/template_pool/unsafe.json', '{}')
        with self.assertRaises(ReportError):
            self.freeze()
        with self.zip(self.village) as archive:
            archive.writestr(PROCESSOR_PATH, json.dumps({'processors': []}))
            archive.writestr('village-manifest.json', json.dumps({**self.metadata, 'extra': 'different provenance'}))
        with self.assertRaises(ReportError):
            self.freeze()

    def test_post_freeze_changed_missing_and_added_dependencies_are_detected(self):
        frozen = self.freeze()
        original = (self.instance / 'plugins/probe.jar').read_bytes()
        self.write('plugins/probe.jar', b'changed')
        with self.assertRaises(ReportError):
            candidate.verify(frozen)
        self.write('plugins/probe.jar', original)
        self.write('libraries/new.jar', b'new dependency')
        with self.assertRaises(ReportError):
            candidate.verify(frozen)
        (self.instance / 'libraries/new.jar').unlink()
        (self.instance / 'plugins/probe.jar').unlink()
        with self.assertRaises((OSError, ReportError)):
            candidate.verify(frozen)

    def test_java_runtime_and_detached_expected_identity_cannot_drift(self):
        frozen = self.freeze()
        changed = json.loads(json.dumps(frozen))
        changed['expected']['artifacts']['production']['sha256'] = '0' * 64
        with self.assertRaises(ReportError):
            candidate.verify(changed)
        self.java.write_bytes(b'different-runtime')
        with self.assertRaises(ReportError):
            candidate.verify(frozen)

    def test_serialized_roles_and_complete_runtime_inventory_cannot_be_removed(self):
        frozen = self.freeze()
        for mutation in ('role', 'runtime', 'modules', 'aliased-path'):
            value = json.loads(json.dumps(frozen))
            if mutation == 'role':
                del value['definingPaths']['production']
                value['expected']['artifacts']['production']['sha256'] = '0' * 64
            elif mutation == 'runtime':
                value['javaRuntime'] = {}
            elif mutation == 'modules':
                for path in list(value['javaRuntime']):
                    if path.endswith('modules'):
                        del value['javaRuntime'][path]
            else:
                value['definingPaths']['server'] = 'plugins//production.jar'
            with self.subTest(mutation=mutation), self.assertRaises(ReportError):
                candidate.verify(value)

    def test_undeclared_active_plugins_are_not_given_an_incomplete_input_certificate(self):
        self.write('plugins/OtherPlugin.jar', b'unknown active plugin')
        self.write('plugins/OtherPlugin/settings.json', b'untracked behavior')
        with self.assertRaises(ReportError):
            self.freeze()
        self.assertFalse(self.output.exists())

    def test_initial_mutable_ce_inputs_are_bound_before_start_and_not_mislabeled_immutable_after_run(self):
        frozen = self.freeze()
        self.write('plugins/CraftEngine/cache/custom_block_states.json', b'changed initial allocation')
        with self.assertRaises(ReportError):
            candidate.verify(frozen)
        candidate.verify(frozen, before_start=False)
        self.assertEqual('INITIAL_HASHES_ONLY_POSTRUN_CHANGES_REQUIRE_EXTERNAL_RUNTIME_EVIDENCE', frozen['mutableInputScope'])

    def test_excessive_zip_central_directory_is_rejected_before_zip_parser_allocation(self):
        path = self.instance / 'oversized-central.zip'
        path.write_bytes(struct.pack('<4s4H2LH', b'PK\x05\x06', 0, 0, 10_001, 10_001, 0, 0, 0))
        with patch.object(candidate, 'ZipFile', side_effect=AssertionError('Do not allocate parser before bounds')):
            with self.assertRaises(ReportError), candidate.bounded_zip(path):
                self.fail('Unbounded ZIP opened')

    def test_escaping_linked_and_output_inside_candidate_paths_are_refused(self):
        for path in ('../outside.jar', 'C:/outside.jar', 'plugins\\probe.jar'):
            with self.subTest(path=path), self.assertRaises(ReportError):
                candidate.contained(self.instance, path)
        self.output = self.instance / 'freeze-output'
        with self.assertRaises(ReportError):
            self.freeze()
        self.assertFalse(self.output.exists())

    def test_cli_check_is_read_only_and_never_replaces_failed_inventory(self):
        self.freeze()
        manifest = self.output / 'candidate.json'
        original = manifest.read_bytes()
        with patch('sys.argv', ['candidate', 'check', '--candidate', str(manifest)]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, candidate.main())
            self.write('plugins/probe.jar', b'wrong')
            self.assertEqual(1, candidate.main())
        self.assertEqual(original, manifest.read_bytes())

    def test_new_world_or_extra_datapack_after_freeze_invalidates_prestart_check(self):
        frozen = self.freeze()
        self.write('acceptance-world/level.dat', b'used world')
        with self.assertRaises(ReportError):
            candidate.verify(frozen)
        # Post-run inventory checks permit ordinary world output, but never new native inputs.
        candidate.verify(frozen, before_start=False)
        self.write('acceptance-world/datapacks/additional.zip', b'unfrozen datapack')
        with self.assertRaises(ReportError):
            candidate.verify(frozen, before_start=False)

    def test_processor_pack_discovery_is_not_silently_treated_as_enabled(self):
        path = self.instance / 'server.properties'
        original = path.read_bytes()
        for raw in (original.replace(b'initial-enabled-packs=vanilla,file/village.zip', b'initial-enabled-packs=vanilla'),
                    original + b'initial-disabled-packs=file/village.zip\n'):
            path.write_bytes(raw)
            with self.assertRaises(ReportError):
                self.freeze()
            self.assertFalse(self.output.exists())
        path.write_bytes(original)

    def test_actual_linked_input_is_rejected_when_supported_by_the_host(self):
        external = self.root / 'external.jar'
        external.write_bytes(b'linked bytes')
        linked = self.instance / 'linked.jar'
        try:
            linked.symlink_to(external)
        except OSError as error:
            self.skipTest('This host does not permit creating test symlinks: ' + str(error))
        with self.assertRaises(ReportError):
            candidate.contained(self.instance, 'linked.jar')
        root_link = self.acceptance / 'linked-instance'
        root_link.symlink_to(self.instance, target_is_directory=True)
        with self.assertRaises(ReportError):
            candidate.contained(root_link, 'plugins/probe.jar')

    def test_long_native_input_paths_are_hashed_and_enumerated_without_changing_logical_identity(self):
        directory = self.instance / 'long'
        directory.mkdir()
        path = directory / ('k' * 220 + '.json')
        candidate.fs_path(path).write_bytes(b'full long-path input')
        try:
            self.assertEqual(len(b'full long-path input'), candidate.fingerprint(path)['bytes'])
            self.assertEqual([path.resolve()], list(candidate.walk_files(directory.resolve())))
            self.assertEqual(path.resolve(), candidate.contained(self.instance, path.relative_to(self.instance).as_posix()))
        finally:
            candidate.fs_path(path).unlink()

    def test_receipts_are_bound_to_candidate_paths_and_inventory_without_claiming_whole_run_acceptance(self):
        frozen = self.freeze()
        registration, report, _ = receipt_fixture()
        for value in (registration, report):
            value['runId'] = self.run_id
            value['artifacts'] = {name: {**identity, 'path': str(candidate.contained(self.instance, self.defining[name]))}
                                  for name, identity in frozen['expected']['artifacts'].items()}
        registered = datetime.now(timezone.utc) + timedelta(seconds=1)
        registration['registeredAt'] = report['registeredAt'] = registered.isoformat().replace('+00:00', 'Z')
        report['completedAt'] = (registered + timedelta(seconds=1)).isoformat().replace('+00:00', 'Z')
        stem = 'village-publication-smoke-' + self.run_id
        directory = self.instance / 'plugins/CookeryAcceptanceProbe'
        registered_path, final_path = directory / (stem + '-registration.json'), directory / (stem + '.json')
        registered_path.write_text(json.dumps(registration), encoding='utf-8')
        final_path.write_text(json.dumps(report), encoding='utf-8')
        result = candidate.verify_receipt(frozen, registered_path, final_path)
        self.assertEqual('MATCHED', result['inputInventory'])
        self.assertEqual('NOT_CERTIFIED_BY_THIS_CHECK', result['wholeServerRun'])
        report['artifacts']['server']['path'] = str(self.instance / 'other-server.jar')
        registration['artifacts']['server']['path'] = report['artifacts']['server']['path']
        registered_path.write_text(json.dumps(registration), encoding='utf-8')
        final_path.write_text(json.dumps(report), encoding='utf-8')
        with self.assertRaises(ReportError):
            candidate.verify_receipt(frozen, registered_path, final_path)


if __name__ == '__main__':
    unittest.main()
