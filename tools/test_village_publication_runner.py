"""Exercise the village mode's complete runner contract with synthetic files and fake processes."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
from time import time_ns as real_time_ns
from types import SimpleNamespace
import subprocess
import unittest
from unittest.mock import patch
from zipfile import ZIP_STORED, ZipFile

import test_village_publication_candidate as candidate_fixture
from test_village_publication_report import fixture as receipt_fixture
import village_publication_candidate as candidate
import village_publication_launch as launch

SPEC = importlib.util.spec_from_file_location('village_mode_common_runner', Path(__file__).parent / 'acceptance/run_series_gameplay.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

DONE = '[08:00:00 INFO]: Done (1.234s)! For help, type "help"\n'
SMOKES = '[08:00:00 INFO]: COOKERY_FOOD_SMOKE checked=103 failures=0\n[08:00:00 INFO]: COOKERY_ENCHANTMENT_SMOKE checked=5 reloaded=5 failures=0\n'
WORKFLOW = '[08:00:01 INFO]: Resource pack workflow default completed\n'
STARTED = '[08:00:01 INFO]: 正在执行资源包工作流 default…\n'
PACK = b'{"pack":{"min_format":[94,1],"max_format":[94,1],"description":"SYNTHETIC_OFFLINE_RUNNER_CONTRACT_ONLY"}}'


class FakeClock:
    NS_PER_SECOND = 1_000_000_000

    def __init__(self):
        self.base_ns = real_time_ns() + self.NS_PER_SECOND
        self.elapsed_ns = 0
        self.on_sleep = lambda: None
    def time(self): return self.time_ns() / self.NS_PER_SECOND
    def time_ns(self): return self.base_ns + self.elapsed_ns
    def monotonic(self): return self.elapsed_ns / self.NS_PER_SECOND
    def utc(self): return datetime.fromtimestamp(self.time(), timezone.utc).isoformat().replace('+00:00', 'Z')
    def sleep(self, duration):
        if duration <= 0: raise AssertionError('Runner must yield')
        self.elapsed_ns += 100 * self.NS_PER_SECOND
        self.on_sleep()


class FakeInput:
    def __init__(self, process): self.process = process; self.commands = []; self.closed = False
    def write(self, command):
        self.commands.append(command)
        if command == b'ce workflow default\n': self.process.workflow()
        elif command == b'stop\n': self.process.stopping = True
        else: raise AssertionError('Unexpected command: ' + repr(command))
        return len(command)
    def flush(self): pass
    def close(self):
        self.closed = True
        if self.process.options.get('close_error'):
            raise OSError('Synthetic input close failure')


class FakeServer:
    pid = 44001
    def __init__(self, fixture, stdout, options):
        self.fixture, self.stdout, self.options = fixture, stdout, options
        self.returncode = None; self.stopping = False; self.stdin = FakeInput(self); self.waits = []
        self.receipts()
        if options.get('pre_marker'): self.emit(STARTED + WORKFLOW)
        self.emit(DONE)
        self.emit(options.get('smokes', SMOKES))
        self.emit(options.get('line', ''))
        if options.get('early_exit'): self.returncode = 0
    def emit(self, line): self.stdout.write(line.encode('utf8')); self.stdout.flush()
    def receipts(self):
        registration, report, _ = receipt_fixture()
        for value in (registration, report):
            value['runId'] = self.fixture.frozen['runId']
            value['registeredAt'] = self.fixture.clock.utc()
            value['artifacts'] = {key: {**expected, 'path': str(candidate.contained(self.fixture.instance, self.fixture.frozen['definingPaths'][key]))}
                                  for key, expected in self.fixture.frozen['expected']['artifacts'].items()}
        report['completedAt'] = self.fixture.clock.utc()
        if self.options.get('large_receipt'):
            report['syntheticPadding'] = 'x' * (RUNNER.VILLAGE_REPORT.MAX_REPORT_BYTES - len(json.dumps(report).encode('utf8')) - 32)
        if self.options.get('forged'): report['structureCodec']['states'][0]['after']['fake'] = 'changed'
        if self.options.get('old_receipt'):
            registration['registeredAt'] = report['registeredAt'] = report['completedAt'] = '2000-01-01T00:00:00Z'
        if not self.options.get('missing_receipt'):
            self.fixture.registration.write_text(json.dumps(registration), encoding='utf8')
            self.fixture.report.write_text(json.dumps(report), encoding='utf8')
        if self.options.get('marker'):
            self.fixture.report.with_name(self.fixture.report.stem + '-missing-after-enable.json').write_text('{}', encoding='utf8')
    def workflow(self):
        kind = self.options.get('zip', 'valid')
        issued_ns = self.fixture.clock.time_ns()
        # A real write follows the command. Align fake times to exact NTFS 100 ns units.
        self.fixture.clock.elapsed_ns += 10_000_000
        if kind != 'old':
            stamp = issued_ns - FakeClock.NS_PER_SECOND if kind == 'stale-write' else self.fixture.clock.time_ns()
            self.fixture.write_pack(kind, stamp - stamp % 100)
        if self.options.get('workflow_markers') is not None:
            self.emit(self.options['workflow_markers'])
            return
        if self.options.get('deferred_workflow'):
            self.fixture.clock.on_sleep = lambda: self.emit(STARTED + WORKFLOW)
        elif not self.options.get('no_workflow'): self.emit(('' if self.options.get('completion_only') else STARTED) + WORKFLOW)
    def poll(self): return self.returncode
    def wait(self, *, timeout):
        if not self.stopping: raise AssertionError('No normal stop requested')
        self.waits.append(timeout)
        if self.options.get('stop_timeout'): raise subprocess.TimeoutExpired('FAKE_ONLY', timeout)
        if self.options.get('stop_error'): raise OSError('Synthetic stop wait failure')
        if not self.options.get('no_stop_marker'): self.emit('[08:00:02 INFO]: Stopping server\n')
        if self.options.get('late_error'): self.emit('[08:00:03 ERROR]: failure during shutdown\n')
        if self.options.get('input_drift'): self.fixture.write('plugins/probe.jar', b'changed after native receipt')
        if self.options.get('late_marker'):
            self.fixture.report.with_name(self.fixture.report.stem + '-missing-after-enable.json').write_text('{}', encoding='utf8')
        self.returncode = self.options.get('exit', 0)
        return self.returncode


class VillageRunnerFixture:
    def __init__(self, test):
        self.test = test
        self.base = candidate_fixture.VillagePublicationCandidateTest(); self.base.setUp(); test.addCleanup(self.base.doCleanups)
        self.root = self.base.root
        (self.root / 'build').mkdir()
        self.acceptance = self.root / 'build/acceptance'
        self.base.acceptance.rename(self.acceptance)
        self.base.acceptance = self.acceptance
        self.base.instance = self.instance = self.acceptance / 'fresh-paper'
        self.base.output = self.acceptance / 'frozen-paper'
        self.patch = patch.object(candidate, 'ACCEPTANCE', self.acceptance); self.patch.start(); test.addCleanup(self.patch.stop)
        self.base.write('eula.txt', b'eula=true\n')
        self.frozen = self.base.freeze()
        system = self.root / 'Windows'; (system / 'System32').mkdir(parents=True)
        self.environment = {name: str(self.root) for name in launch.PROFILE_PATHS}
        self.environment.update(SystemRoot=str(system), WINDIR=str(system))
        self.record = launch.prepare(self.frozen, self.environment)
        self.launch_file = self.base.output / 'launch.json'; self.launch_file.write_text(json.dumps(self.record), encoding='utf8')
        self.output = self.acceptance / 'run-evidence'
        self.args = SimpleNamespace(village_candidate=self.base.output / 'candidate.json', village_launch=self.launch_file,
                                    output_dir=self.output, instance=None, output=None, java=None)
        stem = 'village-publication-smoke-' + self.frozen['runId']
        directory = self.instance / 'plugins/CookeryAcceptanceProbe'
        self.registration, self.report = directory / (stem + '-registration.json'), directory / (stem + '.json')
        self.pack = self.instance / 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip'
        self.clock = FakeClock(); self.server = None; self.popen_spec = None; self.force_count = 0
    def write(self, name, raw): self.base.write(name, raw)
    def write_pack(self, kind, stamp):
        self.pack.parent.mkdir(parents=True, exist_ok=True)
        if kind == 'truncated':
            self.pack.write_bytes(b'PK\x03\x04incomplete')
        else:
            with ZipFile(self.pack, 'w', compression=ZIP_STORED) as archive:
                if kind != 'empty':
                    archive.writestr('pack.mcmeta', PACK)
            if kind == 'crc':
                raw = bytearray(self.pack.read_bytes())
                raw[raw.index(PACK)] ^= 1
                self.pack.write_bytes(raw)
        os.utime(self.pack, ns=(stamp, stamp))
    def run(self, **options):
        def popen(command, **kwargs):
            if self.server is not None: raise AssertionError('One exact server only')
            self.popen_spec = (deepcopy(command), dict(kwargs))
            self.test.assertEqual(self.record['argv'], command)
            self.test.assertEqual(self.record['environment'], kwargs['env'])
            self.test.assertEqual(self.record['cwd'], kwargs['cwd'])
            self.test.assertIs(kwargs['shell'], False)
            self.server = FakeServer(self, kwargs['stdout'], options)
            return self.server
        def force(process):
            self.test.assertIs(process, self.server); self.force_count += 1; process.returncode = -1
        with (patch.object(RUNNER, 'ROOT', self.root), patch.dict(os.environ, self.environment, clear=True),
              patch.object(RUNNER.subprocess, 'Popen', side_effect=popen) as calls,
              patch.object(RUNNER.CONNECTED, 'terminate_tree', side_effect=force),
              patch.object(RUNNER.time, 'time', side_effect=self.clock.time),
              patch.object(RUNNER.time, 'time_ns', side_effect=self.clock.time_ns),
              patch.object(RUNNER.time, 'monotonic', side_effect=self.clock.monotonic),
              patch.object(RUNNER.time, 'sleep', side_effect=self.clock.sleep),
              patch.object(RUNNER, 'utc_now', side_effect=self.clock.utc), contextlib.redirect_stdout(io.StringIO())):
            status = RUNNER.run(self.args)
        self.calls = calls.call_count
        return status, json.loads((self.output / 'result.json').read_text(encoding='utf8'))


class VillagePublicationRunnerTest(unittest.TestCase):
    def fixture(self): return VillageRunnerFixture(self)
    def test_complete_contract_uses_exact_frozen_process_and_retains_original_receipts_zip_and_normal_exit(self):
        fixture = self.fixture(); status, result = fixture.run()
        self.assertEqual(0, status, result['errors']); self.assertTrue(result['passed'])
        self.assertEqual(1, fixture.calls); self.assertEqual(0, fixture.force_count)
        self.assertEqual([b'ce workflow default\n', b'stop\n'], fixture.server.stdin.commands)
        self.assertEqual([60], fixture.server.waits); self.assertTrue(fixture.server.stdin.closed)
        self.assertEqual(fixture.frozen['runId'], result['runId']); self.assertTrue(result['normalStopAcknowledged'])
        self.assertEqual('NOT_ACTIVATED', result['naturalVillageIntegration'])
        for path in (fixture.registration, fixture.report): self.assertEqual(path.read_bytes(), (fixture.output / path.name).read_bytes())
        self.assertEqual(fixture.pack.read_bytes(), (fixture.output / 'resource_pack.zip').read_bytes())
        self.assertEqual(fixture.record['argv'], result['actualProcessSpec']['argv'])
        self.assertEqual('MATCHED', result['receiptSummary']['inputInventory'])
    def test_preflight_candidate_launch_environment_and_eula_veto_never_start_a_process(self):
        for mutation in ('candidate', 'launch', 'environment', 'eula', 'malformed-launch'):
            with self.subTest(mutation=mutation):
                fixture = self.fixture()
                if mutation == 'candidate': fixture.write('plugins/probe.jar', b'drift')
                elif mutation == 'launch':
                    value = deepcopy(fixture.record); value['argv'].insert(1, '-Xmx8G'); fixture.launch_file.write_text(json.dumps(value), encoding='utf8')
                elif mutation == 'environment': fixture.environment['JAVA_TOOL_OPTIONS'] = 'NEVER_ECHO_THIS_SECRET'
                elif mutation == 'eula': fixture.write('eula.txt', b'eula=false\n')
                else: fixture.launch_file.write_text('[]', encoding='utf8')
                status, result = fixture.run()
                self.assertEqual(1, status); self.assertEqual(0, fixture.calls); self.assertFalse(result['passed'])
                self.assertEqual('NOT_RUN', result['startup']); self.assertNotIn('NEVER_ECHO_THIS_SECRET', json.dumps(result))
    def test_malformed_candidate_is_retained_as_a_controlled_preflight_result(self):
        for contents in (None, '[]', '{"runId": "invalid"}', '{"instance": "relative/path"}'):
            with self.subTest(contents=contents):
                fixture = self.fixture()
                if contents is None:
                    fixture.args.village_candidate.unlink()
                else:
                    fixture.args.village_candidate.write_text(contents, encoding='utf8')
                status, result = fixture.run()
                self.assertEqual(1, status); self.assertFalse(result['passed'])
                self.assertEqual('NOT_RUN', result['startup']); self.assertEqual(0, fixture.calls)
                self.assertTrue(result['errors']); self.assertTrue((fixture.output / 'result.json').is_file())
    def test_a_near_limit_receipt_is_preserved_separately_from_its_result_summary(self):
        fixture = self.fixture(); status, result = fixture.run(large_receipt=True)
        self.assertEqual(0, status, result['errors'])
        self.assertGreater(fixture.report.stat().st_size, RUNNER.VILLAGE_REPORT.MAX_REPORT_BYTES - 64)
        self.assertEqual(fixture.report.read_bytes(), (fixture.output / fixture.report.name).read_bytes())
        self.assertNotIn('syntheticPadding', result['probe'])
        self.assertEqual(candidate.fingerprint(fixture.report)['sha256'], result['probe']['sha256'])
    def test_missing_forged_previous_or_supplemental_receipts_never_pass(self):
        for options in ({'missing_receipt': True}, {'forged': True}, {'old_receipt': True}, {'marker': True}, {'late_marker': True}):
            with self.subTest(options=options):
                fixture = self.fixture(); status, result = fixture.run(**options)
                self.assertEqual(1, status); self.assertFalse(result['passed']); self.assertTrue(result['errors'])
    def test_workflow_must_finish_after_its_command_and_produce_a_new_valid_complete_zip(self):
        for options in ({'no_workflow': True}, {'pre_marker': True, 'no_workflow': True}, {'completion_only': True}, {'zip': 'truncated'},
                        {'zip': 'empty'}, {'zip': 'crc'}, {'zip': 'stale-write'},
                        {'workflow_markers': STARTED.rstrip() + ' ' + WORKFLOW},
                        {'workflow_markers': WORKFLOW + STARTED + WORKFLOW},
                        {'workflow_markers': STARTED + STARTED + WORKFLOW},
                        {'workflow_markers': STARTED + WORKFLOW + WORKFLOW}):
            with self.subTest(options=options):
                fixture = self.fixture(); status, result = fixture.run(**options)
                self.assertEqual(1, status); self.assertFalse(result['passed']); self.assertFalse(result['packGenerated'])
        fixture = self.fixture(); status, result = fixture.run(deferred_workflow=True)
        self.assertEqual(0, status, result['errors']); self.assertTrue(result['passed'])
        workflow = result['workflow']
        self.assertLess(workflow['startLogOffset'], workflow['completeLogOffset'])
        fixture = self.fixture()
        fixture.write_pack('valid', fixture.clock.time_ns() - FakeClock.NS_PER_SECOND)
        status, result = fixture.run(zip='old')
        self.assertEqual(1, status); self.assertFalse(result['packGenerated'])
    def test_every_abnormal_or_unacknowledged_exit_fails_and_retains_terminal_status(self):
        for options in ({'early_exit': True}, {'exit': 17}, {'stop_timeout': True}, {'stop_error': True}, {'no_stop_marker': True},
                        {'close_error': True}):
            with self.subTest(options=options):
                fixture = self.fixture(); status, result = fixture.run(**options)
                self.assertEqual(1, status); self.assertFalse(result['passed'])
                if options.get('stop_timeout') or options.get('stop_error'): self.assertTrue(result['forcedServerStop'])
                if options.get('close_error'):
                    self.assertEqual('FAIL', result['startup'])
                    self.assertEqual(0, result['serverExit'])
                    self.assertTrue(result['normalStopAcknowledged'])
                    self.assertTrue(result['consoleInspectionComplete'])
                    self.assertFalse(result['forcedServerStop'])
                    self.assertTrue(any('Synthetic input close failure' in row for row in result['errors']))
                    self.assertEqual(fixture.report.read_bytes(), (fixture.output / fixture.report.name).read_bytes())
    def test_runtime_warnings_shutdown_errors_watchdog_and_late_input_drift_cannot_be_hidden_by_clean_receipt(self):
        for options in ({'line': '[08:00:00 ERROR]: runtime failure\n'}, {'line': '[CraftEngine] Failed to load a block\n'},
                        {'line': '[08:00:00 WARN]: The server has stopped responding!\n'}, {'late_error': True}, {'input_drift': True},
                        {'smokes': SMOKES.replace('checked=103', 'checked=1')}):
            with self.subTest(options=options):
                fixture = self.fixture(); status, result = fixture.run(**options)
                self.assertEqual(1, status); self.assertFalse(result['passed']); self.assertTrue(result['errors'])
    def test_instance_and_output_links_containment_and_preexisting_output_are_rejected_before_launch(self):
        fixture = self.fixture(); fixture.args.output_dir = fixture.instance / 'inside'
        with patch.object(RUNNER, 'ROOT', fixture.root), patch.object(RUNNER.subprocess, 'Popen') as process:
            self.assertEqual(1, RUNNER.run(fixture.args))
            process.assert_not_called()
        self.assertFalse((fixture.instance / 'inside').exists())
    def test_oversized_console_line_fails_before_reading_unbounded_memory_and_preserves_the_log(self):
        fixture = self.fixture()
        with patch.object(RUNNER, 'MAX_CONSOLE_LINE_BYTES', 512):
            status, result = fixture.run(line='X' * 513 + '\n')
        self.assertEqual(1, status); self.assertFalse(result['passed']); self.assertGreater((fixture.output / 'console.log').stat().st_size, 512)
    def test_legacy_cli_remains_compatible_and_village_cli_rejects_java_or_positional_overrides(self):
        fixture = self.fixture()
        arguments = ['runner', '--village-candidate', str(fixture.args.village_candidate), '--village-launch', str(fixture.args.village_launch),
                     '--output-dir', str(fixture.output)]
        with patch('sys.argv', arguments), patch.object(RUNNER, 'run', return_value=0) as run:
            self.assertEqual(0, RUNNER.main()); self.assertEqual(fixture.args.village_candidate, run.call_args.args[0].village_candidate)
        for extra in (['--java', 'other-java'], [str(fixture.instance)], []):
            invalid = arguments + extra if extra else arguments[:-2]
            with patch('sys.argv', invalid), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit): RUNNER.main()


if __name__ == '__main__': unittest.main()
