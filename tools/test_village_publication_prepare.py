import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from cookery_village import COMMIT, PROCESSOR_PATH
import village_publication_candidate as candidate
import village_publication_launch as launch
import village_publication_prepare as prepare
from village_publication_report import ReportError


def digest(data):
    return hashlib.sha256(data).hexdigest()


class VillagePublicationPrepareTest(unittest.TestCase):
    run_id = '74dd6260-a8ce-4059-b943-fdb7fc370bc4'
    metadata = json.dumps({'sourceCommit': COMMIT, 'naturalVillageIntegration': 'NOT_IMPLEMENTED'})

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.acceptance = self.root / 'acceptance'
        self.acceptance.mkdir()
        self.seed = self.root / 'seed'
        self.instance = self.acceptance / 'new'
        self.receipt = self.acceptance / 'assembly.json'
        self.output = self.acceptance / 'frozen'
        self.native = prepare.NATIVE['Paper'][0]
        self.write(self.seed / self.native, b'pinned-native')
        self.write(self.seed / 'libraries/org/example/library.jar', b'pinned-library')
        self.write(self.seed / candidate.PAPERCLIP_CLIENT, b'pinned-mojang')
        self.archive(self.seed / 'server.jar', {
            'META-INF/versions.list': f'{digest(b"pinned-native")}\t1.21.11\t{self.native.removeprefix("versions/")}\n',
            'META-INF/libraries.list': f'{digest(b"pinned-library")}\torg:example:1\torg/example/library.jar\n',
        })
        self.write(self.seed / prepare.CE_CONFIG, b'misc:\n  delay-configuration-load: false\n'
                   b'chunk-system:\n  generation:\n    feature: true\n'
                   b'storage:\n  type: sqlite\n  url: jdbc:sqlite:./plugins/CraftEngine/data.db\n'
                   b'resource-pack:\n  self-host:\n    ip: 127.0.0.1\n  packs:\n    default:\n      type: self\n')
        self.write(self.seed / (prepare.RESOURCES + 'default_assets/pack.yml'), b'default')
        self.sources = {}
        for role, name in prepare.PLUGIN_NAMES.items():
            archive = self.root / (role + '.jar')
            self.sources[role] = archive
            members = {'paper-plugin.yml' if role == 'craftEngine' else 'plugin.yml': f'name: {name}\n',
                       'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n'}
            if role == 'production':
                members['village-loot.json'] = self.metadata
            self.archive(archive, members)
        self.kitchen = self.root / 'kitchen.zip'
        self.kitchen_members = {'manifest.json': json.dumps({'sourceCommit': COMMIT, 'recipeSources': 612}),
                                'cookery_kitchen/pack.yml': 'kitchen',
                                'cookery_kitchen/configuration/kitchen.yml': 'actual-installed-config',
                                'tool-output.json': '{}'}
        self.archive(self.kitchen, self.kitchen_members)
        self.village = self.root / 'village.zip'
        self.archive(self.village, {PROCESSOR_PATH: '{"processors":[]}', 'village-manifest.json': self.metadata, 'pack.mcmeta': '{}'})
        self.java = self.root / 'jdk/bin/java.exe'
        self.write(self.java, b'never-started')
        self.write(self.java.parent.parent / 'lib/modules', b'java-image')
        sysroot = self.root / 'windows'
        (sysroot / 'System32').mkdir(parents=True)
        self.env = {'SYSTEMROOT': str(sysroot)}
        for module, key, value in (
            (candidate, 'ACCEPTANCE', self.acceptance),
            (prepare, 'NATIVE', {'Paper': (self.native, digest(b'pinned-native'))}),
            (prepare, 'LAUNCHER_SHA256', {'Paper': candidate.fingerprint(self.seed / 'server.jar')['sha256']}),
            (prepare, 'CE_SHA256', candidate.fingerprint(self.sources['craftEngine'])['sha256']),
            (prepare, 'MOJANG_SHA256', digest(b'pinned-mojang')),
            ('subprocess', 'Popen', None),
        ):
            mocked = patch(module + '.' + key, side_effect=AssertionError('No process launch')) if isinstance(module, str) else patch.object(module, key, value)
            mocked.start()
            self.addCleanup(mocked.stop)

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    @staticmethod
    def archive(path, members):
        path.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(path, 'w') as archive:
            for name, value in members.items():
                archive.writestr(name, value)

    def assemble(self, seed=None):
        return prepare.prepare(seed or prepare.seed_manifest(self.seed, 'Paper'), self.instance, self.receipt, self.sources,
                               self.kitchen, self.village, self.run_id, 25613)

    def test_fresh_three_plugin_fixture_then_verified_handoff(self):
        for name in ('eula.txt', 'acceptance-world/level.dat', 'plugins/legacy.jar', 'plugins/CraftEngine/data.db',
                     prepare.RESOURCES + 'deco/pack.yml', 'logs/latest.log', 'server.properties'):
            self.write(self.seed / name, b'do not copy')
        result = self.assemble()
        self.assertEqual(prepare.read_record(self.receipt), result)
        self.assertEqual({'vanilla', 'file/village.zip'}, set(candidate.inspect_fresh(self.instance, self.run_id)['initial-enabled-packs'].split(',')))
        self.assertEqual('25613', candidate.inspect_fresh(self.instance, self.run_id)['server-port'])
        self.assertEqual(set(prepare.PLUGINS.values()), {path.relative_to(self.instance).as_posix() for path in (self.instance / 'plugins').glob('*.jar')})
        self.assertFalse((self.instance / 'eula.txt').exists())
        self.assertEqual(self.village.read_bytes(), (self.instance / prepare.VILLAGE).read_bytes())
        self.assertEqual(b'actual-installed-config', (self.instance / (prepare.RESOURCES + 'cookery_kitchen/configuration/kitchen.yml')).read_bytes())
        for name in ('logs/latest.log', 'acceptance-world/level.dat', 'plugins/CraftEngine/data.db',
                     prepare.RESOURCES + 'deco/pack.yml', prepare.RESOURCES + 'tool-output.json'):
            self.assertFalse((self.instance / name).exists(), name)
        raw = b'# operator-provided\neula=true\n'
        self.write(self.instance / 'eula.txt', raw)
        record = prepare.handoff(result, self.java, self.output, self.env)
        frozen = prepare.read_record(self.output / 'candidate.json')
        candidate.verify(frozen)
        launch.validate(record, frozen, self.env)
        self.assertEqual('NOT_RUN', record['startup'])
        self.assertEqual(digest(raw), frozen['files']['eula.txt']['sha256'])
        self.assertEqual(raw, (self.instance / 'eula.txt').read_bytes())

    def test_seed_config_and_pinned_runtime_dependency_drift_are_rejected(self):
        record = prepare.seed_manifest(self.seed, 'Paper')
        changed = self.seed / prepare.CE_CONFIG
        changed.write_bytes(changed.read_bytes() + b'\n# changed\n')
        with self.assertRaisesRegex(ReportError, 'seed inventory drifted'):
            self.assemble(record)
        self.write(self.seed / 'libraries/org/example/library.jar', b'changed')
        with self.assertRaisesRegex(ReportError, 'runtime dependency differs'):
            prepare.seed_manifest(self.seed, 'Paper')
        self.assertFalse(self.instance.exists())

    def test_plugin_role_and_runtime_mappings_must_match(self):
        self.archive(self.sources['probe'], {'plugin.yml': 'name: CookeryAcceptanceProbe\n',
                                              'META-INF/MANIFEST.MF': 'paperweight-mappings-namespace: spigot\n'})
        with self.assertRaisesRegex(ReportError, 'must use Mojang mappings'):
            self.assemble()
        self.assertFalse(self.instance.exists())

    def test_pack_traversal_and_case_aliases_do_not_produce_receipts(self):
        for name in ('cookery_kitchen/../escape', 'cookery_kitchen/Pack.yml', 'cookery_kitchen/configuration/CON.yml'):
            with self.subTest(name=name):
                self.instance = self.acceptance / ('bad-' + str(len(list(self.acceptance.iterdir()))))
                self.archive(self.kitchen, {**self.kitchen_members, name: 'invalid'})
                with self.assertRaises(ReportError):
                    self.assemble()
                self.assertFalse(self.receipt.exists())
                self.assertFalse((self.instance / (prepare.RESOURCES + 'escape')).exists())

    def test_failed_inspection_cleans_staging_and_final_paths_then_allows_retry(self):
        before_seed = prepare.seed_manifest(self.seed, 'Paper')
        before_inputs = {name: candidate.fingerprint(path) for name, path in {
            **self.sources, 'kitchen': self.kitchen, 'village': self.village}.items()}
        with patch.object(candidate, 'inspect_fresh', side_effect=ReportError('injected inspection failure')):
            with self.assertRaisesRegex(ReportError, 'injected inspection failure'):
                self.assemble(before_seed)

        self.assertFalse(self.instance.exists())
        self.assertFalse(self.receipt.exists())
        self.assertFalse(any(path.name.startswith(f'.{self.instance.name}.prepare-')
                            for path in self.acceptance.iterdir()))
        self.assertEqual(before_seed, prepare.seed_manifest(self.seed, 'Paper'))
        self.assertEqual(before_inputs, {name: candidate.fingerprint(path) for name, path in {
            **self.sources, 'kitchen': self.kitchen, 'village': self.village}.items()})

        result = self.assemble(before_seed)
        self.assertEqual(str(self.instance), result['instance'])
        self.assertEqual(prepare.read_record(self.receipt), result)

    def test_failed_receipt_write_removes_published_instance_and_allows_retry(self):
        with patch.object(prepare, 'write_record', side_effect=OSError('injected receipt failure')):
            with self.assertRaisesRegex(OSError, 'injected receipt failure'):
                self.assemble()

        self.assertFalse(self.instance.exists())
        self.assertFalse(self.receipt.exists())
        self.assertFalse(any(path.name.startswith(f'.{self.instance.name}.prepare-')
                            for path in self.acceptance.iterdir()))

        result = self.assemble()
        self.assertEqual(str(self.instance), result['instance'])
        self.assertEqual(prepare.read_record(self.receipt), result)

    def test_manual_eula_and_pristine_inputs_are_required_at_handoff(self):
        result = self.assemble()
        with self.assertRaisesRegex(ReportError, 'supplied independently'):
            prepare.handoff(result, self.java, self.output, self.env)
        for raw in (b'eula=false\n', b'eula=true\neula=false\n'):
            self.write(self.instance / 'eula.txt', raw)
            with self.assertRaisesRegex(ReportError, 'operator must already have accepted'):
                prepare.handoff(result, self.java, self.output, self.env)
        self.write(self.instance / 'eula.txt', b'eula=true\n')
        self.write(self.instance / 'unexpected.file', b'drift')
        with self.assertRaisesRegex(ReportError, 'instance inputs drifted'):
            prepare.handoff(result, self.java, self.output, self.env)
        self.assertFalse(self.output.exists())

    def test_prepare_will_not_reuse_an_instance(self):
        self.instance.mkdir()
        self.write(self.instance / 'keep.txt', b'keep')
        with self.assertRaisesRegex(ReportError, 'already exists'):
            self.assemble()
        self.assertEqual(b'keep', (self.instance / 'keep.txt').read_bytes())
        self.assertFalse(self.receipt.exists())


if __name__ == '__main__':
    unittest.main()
