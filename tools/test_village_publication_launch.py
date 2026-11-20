from collections.abc import Mapping
from copy import deepcopy
import contextlib
import io
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch

import village_publication_launch as launch
import test_village_publication_candidate as candidate_fixture
from village_publication_report import ReportError


class VillagePublicationLaunchTest(unittest.TestCase):
    def setUp(self):
        self.fixture = candidate_fixture.VillagePublicationCandidateTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.frozen = json.loads(json.dumps(self.fixture.freeze()))
        self.environment = {name: str(self.fixture.root) for name in launch.PROFILE_PATHS}
        self.system = self.fixture.root / 'Windows'
        (self.system / 'System32').mkdir(parents=True)
        self.environment['SystemRoot'] = str(self.system)
        self.environment['WINDIR'] = str(self.system)
        self.environment['PATH'] = 'untrusted PATH with no inherited use'
        self.environment['JAVA_HOME'] = 'unselected old Java home is not used'
        self.environment['UNRELATED_API_TOKEN'] = 'secret-value-must-never-be-copied'

    def prepare(self):
        return launch.prepare(self.frozen, self.environment)

    def test_prepared_exact_command_keeps_fixed_heap_and_no_watchdog_or_argument_file_override(self):
        before = self.fixture.instance.joinpath('server.properties').read_bytes()
        with (patch('subprocess.Popen', side_effect=AssertionError('No process may be started')),
              patch('os.system', side_effect=AssertionError('No shell may be started'))):
            record = self.prepare()
            launch.validate(record, self.frozen, self.environment)
        self.assertEqual([str(self.fixture.java.resolve()), '-Xms512M', '-Xmx2200M',
                          '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                          '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'server.jar', 'nogui'], record['argv'])
        self.assertEqual(str(self.fixture.instance.resolve()), record['cwd'])
        self.assertEqual('NOT_RUN', record['startup'])
        self.assertIs(record['processOptions']['shell'], False)
        self.assertEqual('UNCHANGED_NO_OVERRIDE', record['policy']['watchdog'])
        self.assertEqual('NOT_PROVIDED_BY_THIS_RECORD', record['policy']['launchAuthorization'])
        self.assertEqual(before, self.fixture.instance.joinpath('server.properties').read_bytes())
        self.assertFalse(any(arg.startswith('@') or 'watchdog' in arg.lower() for arg in record['argv']))

    def test_unknown_secrets_and_inherited_path_never_enter_record_but_existing_profiles_keep_their_directory(self):
        record = self.prepare()
        encoded = json.dumps(record)
        self.assertNotIn('secret-value-must-never-be-copied', encoded)
        self.assertNotIn('UNRELATED_API_TOKEN', encoded)
        self.assertNotIn(self.environment['PATH'], encoded)
        self.assertNotIn(self.environment['JAVA_HOME'], encoded)
        for key in launch.PROFILE_PATHS:
            self.assertEqual(str(self.fixture.root.resolve()), record['environment'][key])
        self.assertNotEqual(record['cwd'], record['environment']['HOME'])
        self.assertEqual(str(self.fixture.java.parent.parent.resolve()), record['environment']['JAVA_HOME'])
        changed = dict(self.environment, UNRELATED_API_TOKEN='another secret')
        launch.validate(record, self.frozen, changed)

    def test_every_blocked_override_is_rejected_on_presence_even_empty_without_echoing_its_value(self):
        for name in launch.BLOCKED | {'DyLd_InSeRt_LiBrArIeS', 'JDK_UNKNOWN_OPTIONS', 'JAVA_UNKNOWN_OPTIONS',
                                     'JRE_UNKNOWN_OPTIONS', 'JVM_UNKNOWN_OPTIONS', 'LDR_PRELOAD',
                                     'LD_TRACE_LOADED_OBJECTS', 'LD_LIBRARY_PATH_64', 'JLI_LAUNCHER_DEBUG'}:
            for value in ('secret-injected-value-that-must-not-appear', ''):
                with self.subTest(name=name, empty=value == ''):
                    environment = {**self.environment, name: value}
                    with patch.object(launch.candidate_inputs, 'verify', side_effect=AssertionError('Environment veto must run first')):
                        with self.assertRaises(ValueError) as failure:
                            launch.prepare(self.frozen, environment)
                    self.assertNotIn('secret-injected-value-that-must-not-appear', str(failure.exception))

    def test_ignored_environment_values_are_never_read(self):
        selected = dict(self.environment)
        class TrappingEnvironment(Mapping):
            def __iter__(self): return iter(selected)
            def __len__(self): return len(selected)
            def __getitem__(self, key):
                if key in ('UNRELATED_API_TOKEN', 'PATH', 'JAVA_HOME', 'home', 'tmpdir'):
                    raise AssertionError('An ignored inherited secret or override value was read')
                return selected[key]
        launch.prepare(self.frozen, TrappingEnvironment())
        selected = {key: value for key, value in selected.items() if key not in ('HOME', 'TMPDIR')}
        selected.update(home='secret lowercase value must not be read', tmpdir='another secret must not be read')
        # Only PATH formatting is stubbed to let the real Windows directory fixture
        # exercise POSIX environment-name semantics without weakening production checks.
        with patch.object(launch, '_controlled_path', return_value='POSIX_NAME_SEMANTICS_FIXTURE'):
            value = launch.sanitized_environment(self.fixture.java, TrappingEnvironment(), host_platform='posix')
            self.assertNotIn('HOME', value)
            self.assertNotIn('TMPDIR', value)
            selected.pop('home')
            selected['HOME'] = str(self.fixture.root)
            value = launch.sanitized_environment(self.fixture.java, TrappingEnvironment(), host_platform='posix')
            self.assertEqual(str(self.fixture.root.resolve()), value['HOME'])
            self.assertNotIn('TMPDIR', value)

    def test_profile_and_temporary_paths_must_already_exist_be_absolute_and_remain_unlinked(self):
        for value in ('relative-temp', str(self.fixture.root / 'missing'), 'invalid\0path'):
            with self.subTest(value=value), self.assertRaises(ReportError):
                launch.prepare(self.frozen, {**self.environment, 'TEMP': value})
        linked = self.fixture.root / 'profile-link'
        try:
            linked.symlink_to(self.fixture.root, target_is_directory=True)
        except OSError:
            return  # The path-policy rejection cases above remain meaningful on hosts without symlink privilege.
        with self.assertRaises(ReportError):
            launch.prepare(self.frozen, {**self.environment, 'TEMP': str(linked)})

    def test_windows_environment_uses_only_selected_java_and_validated_system_directory_for_path(self):
        value = launch.sanitized_environment(self.fixture.java, self.environment, host_platform='nt')
        system = str(self.system.resolve())
        self.assertEqual(';'.join((str(self.fixture.java.parent.resolve()), str(self.system.resolve() / 'System32'), system)), value['PATH'])
        self.assertEqual(system, value['SystemRoot'])
        for environment in ({key: value for key, value in self.environment.items() if key != 'SystemRoot'},
                            {**self.environment, 'WINDIR': str(self.fixture.root)}):
            with self.assertRaises(ReportError):
                launch.sanitized_environment(self.fixture.java, environment, host_platform='nt')
        unusual_java = self.fixture.root / 'jdk;relative-native-search' / 'bin' / 'java.exe'
        unusual_java.parent.mkdir(parents=True)
        unusual_java.write_bytes(b'existing direct launcher')
        with self.assertRaises(ReportError):
            launch.sanitized_environment(unusual_java, self.environment, host_platform='nt')
        unusual_system = self.fixture.root / 'Windows;relative-native-search'
        (unusual_system / 'System32').mkdir(parents=True)
        with self.assertRaises(ReportError):
            launch.sanitized_environment(self.fixture.java,
                                         {**self.environment, 'SystemRoot': str(unusual_system), 'WINDIR': str(unusual_system)},
                                         host_platform='nt')

    def test_posix_environment_names_os_tool_path_dependency_and_removes_loader_overrides(self):
        if os.name != 'nt':
            value = launch.sanitized_environment(self.fixture.java, self.environment, host_platform='posix')
            self.assertEqual(str(self.fixture.java.parent.resolve()) + ':/usr/bin:/bin', value['PATH'])
            self.assertEqual('C.UTF-8', value['LANG'])
            self.assertEqual('C.UTF-8', value['LC_ALL'])
            self.assertNotIn('SystemRoot', value)
            unusual_java = self.fixture.root / 'jdk:relative-native-search' / 'bin' / 'java'
            unusual_java.parent.mkdir(parents=True)
            unusual_java.write_bytes(b'existing direct launcher')
        else:
            # The real existing Windows drive path already contains ':', and is
            # likewise invalid as one POSIX PATH entry; do not fake an absent path.
            unusual_java = self.fixture.java
        with self.assertRaises(ReportError):
            launch.sanitized_environment(unusual_java, self.environment, host_platform='posix')
        with self.assertRaises(ReportError):
            launch.sanitized_environment(self.fixture.java, self.environment, host_platform='unknown')

    def test_case_variant_environment_names_are_never_ambiguous_and_lowercase_option_variable_is_blocked(self):
        with self.assertRaises(ReportError):
            launch.prepare(self.frozen, {**self.environment, 'home': str(self.fixture.root)})
        with self.assertRaises(ValueError):
            launch.prepare(self.frozen, {**self.environment, 'java_tool_options': '@secret-options-file'})

    def test_exact_json_roundtrip_validation_rejects_any_command_or_process_policy_drift(self):
        record = json.loads(json.dumps(self.prepare()))
        for change in (
            lambda value: value['argv'].__setitem__(1, '-Xms64M'),
            lambda value: value['argv'].__setitem__(2, '-Xmx512M'),
            lambda value: value['argv'].insert(1, '@not-frozen.args'),
            lambda value: value['argv'].insert(1, '-Ddisable.watchdog=true'),
            lambda value: value.__setitem__('executable', str(self.fixture.root / 'wrapper.cmd')),
            lambda value: value.__setitem__('cwd', str(self.fixture.root)),
            lambda value: value['environment'].__setitem__('JAVA_TOOL_OPTIONS', '-Xmx64M'),
            lambda value: value['processOptions'].__setitem__('shell', 0),
            lambda value: value['processOptions'].__setitem__('shell', True),
            lambda value: value['policy'].__setitem__('watchdog', 'DISABLED'),
            lambda value: value.__setitem__('candidateSha256', '0' * 64),
            lambda value: value.__setitem__('startup', 'PASS'),
            lambda value: value.__setitem__('preparedAt', '2000-01-01T00:00:00Z'),
            lambda value: value.__setitem__('extra-field', True),
        ):
            changed = deepcopy(record); change(changed)
            with self.subTest(changed=changed.keys()), self.assertRaises(ReportError):
                launch.validate(changed, self.frozen, self.environment)
        future = deepcopy(self.frozen)
        future['frozenAt'] = '2999-01-01T00:00:00Z'
        with self.assertRaises(ReportError):
            launch.prepare(future, self.environment)

    def test_current_directory_valued_environment_and_candidate_files_cannot_drift(self):
        record = self.prepare()
        other = self.fixture.root / 'different-temp'; other.mkdir()
        with self.assertRaises(ReportError):
            launch.validate(record, self.frozen, {**self.environment, 'TEMP': str(other)})
        self.fixture.write('libraries/changed.jar', b'input drift')
        with self.assertRaises(ReportError):
            launch.validate(record, self.frozen, self.environment)

    def test_complete_candidate_not_only_artifact_hashes_is_bound_and_missing_record_fields_fail_closed(self):
        record = self.prepare(); changed = json.loads(json.dumps(self.frozen))
        changed['frozenAt'] = '2026-11-19T00:00:00Z'
        with self.assertRaises(ReportError):
            launch.validate(record, changed, self.environment)
        for key in launch.RECORD_KEYS:
            value = deepcopy(record); del value[key]
            with self.subTest(key=key), self.assertRaises(ReportError):
                launch.validate(value, self.frozen, self.environment)

    def test_writes_only_a_new_record_outside_candidate_and_cli_never_prints_secret_values(self):
        output = self.fixture.output / 'launch.json'
        candidate_file = self.fixture.output / 'candidate.json'
        before = {path: path.read_bytes() for path in self.fixture.instance.rglob('*') if path.is_file()}
        with patch.dict(os.environ, self.environment, clear=True), patch('subprocess.Popen', side_effect=AssertionError('No launch')):
            out = io.StringIO()
            with patch('sys.argv', ['launch', 'prepare', '--candidate', str(candidate_file), '--output', str(output)]), contextlib.redirect_stdout(out):
                self.assertEqual(0, launch.main())
            self.assertNotIn('secret-value-must-never-be-copied', out.getvalue())
            with patch('sys.argv', ['launch', 'check', '--candidate', str(candidate_file), '--record', str(output)]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, launch.main())
            original = output.read_bytes()
            with self.assertRaises(FileExistsError):
                launch.write_new_record(self.prepare(), output, self.frozen)
            self.assertEqual(original, output.read_bytes())
            with self.assertRaises(ReportError):
                launch.write_new_record(self.prepare(), self.fixture.instance / 'launch.json', self.frozen)
        self.assertEqual(before, {path: path.read_bytes() for path in self.fixture.instance.rglob('*') if path.is_file()})

    def test_cli_blocked_value_and_argument_file_are_never_disclosed_or_processed(self):
        candidate_file = self.fixture.output / 'candidate.json'
        output = self.fixture.output / 'must-not-exist.json'
        errors = io.StringIO()
        with patch.dict(os.environ, {**self.environment, 'JDK_JAVA_OPTIONS': '@secret-path-token=credential'}, clear=True), \
                patch('subprocess.Popen', side_effect=AssertionError('No launch')), \
                patch('sys.argv', ['launch', 'prepare', '--candidate', str(candidate_file), '--output', str(output)]), \
                contextlib.redirect_stderr(errors):
            self.assertEqual(1, launch.main())
        self.assertFalse(output.exists())
        self.assertIn('JDK_JAVA_OPTIONS', errors.getvalue())
        self.assertNotIn('secret-path-token', errors.getvalue())
        self.assertNotIn('credential', errors.getvalue())


if __name__ == '__main__':
    unittest.main()
