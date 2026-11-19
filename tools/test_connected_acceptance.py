"""The connected runner must fail closed without starting Minecraft in unit tests."""
from __future__ import annotations

import importlib.util
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location(
    "connected_acceptance_runner", Path(__file__).parent / "acceptance/connected/run.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

STARTUP = (
    "[08:00:00 INFO]: Done (33.380s)! For help, type \"help\"\n"
    "[08:00:01 INFO]: COOKERY_FOOD_SMOKE checked=103 failures=0\n"
    "[08:00:01 INFO]: COOKERY_ENCHANTMENT_SMOKE checked=5 reloaded=5 failures=0\n"
)


class StartupTests(unittest.TestCase):
    def test_ready_requires_done_and_both_successful_smokes(self):
        startup = RUNNER.Startup()
        lines = STARTUP.splitlines()
        startup.feed(lines[2])
        self.assertFalse(startup.ready)
        startup.feed(lines[0])
        self.assertFalse(startup.ready)
        startup.feed(lines[1])
        self.assertTrue(startup.ready)

    def test_failure_cannot_be_hidden_by_a_later_success(self):
        startup = RUNNER.Startup()
        startup.feed("COOKERY_ENCHANTMENT_SMOKE checked=5 reloaded=5 failures=1")
        for line in STARTUP.splitlines():
            startup.feed(line)
        self.assertFalse(startup.ready)
        self.assertEqual(1, len(startup.errors))

    def test_malformed_empty_and_incomplete_smokes_fail(self):
        for message in (
            "COOKERY_FOOD_SMOKE checked=0 failures=0",
            "COOKERY_FOOD_SMOKE checked=103",
            "COOKERY_ENCHANTMENT_SMOKE checked=5 reloaded=4 failures=0",
            "COOKERY_ENCHANTMENT_SMOKE checked=5 reloaded=5 failures=-1",
        ):
            with self.subTest(message=message):
                startup = RUNNER.Startup()
                startup.feed(message)
                self.assertTrue(startup.errors)
                self.assertFalse(startup.ready)

    def test_recognizes_server_errors_but_not_version_warnings_or_json(self):
        for line in (
            "[08:00:01 ERROR]: Bad configuration",
            "[Server thread/ERROR]: Failure",
            "[08:00:01 SEVERE]: Cannot read save",
            "[08:00:01 WARN]: Region has not responded in 5s",
            'Exception in thread "Worker" java.lang.IllegalStateException',
            "Caused by: java.lang.IllegalArgumentException: broken",
            "java.lang.NoSuchMethodError: missing()",
            "\tat net.example.Plugin.start(Plugin.java:10)",
        ):
            self.assertIsNotNone(RUNNER.LOG_ERROR.search(line), line)
        for line in (
            "[08:00:01 WARN]: You are 6 release(s) behind the latest stable release",
            "[08:00:01 INFO]: CONNECTED_REPLY 1 {\"error\":\"expected cancellation\"}",
            "[08:00:01 INFO]: Old watchdog diagnostics are preserved",
        ):
            self.assertIsNone(RUNNER.LOG_ERROR.search(line), line)


class RunnerTests(unittest.TestCase):
    def run_fixture(self, *, startup=STARTUP, client_exit=0, client_report=None,
                    timeout=10, clock=None, forced_stop=False, start_error=None,
                    manual=False, finished=False, cleanup_error=False, cancelled=False, phase=None,
                    scenario='legacy', server_start_hook=None, client_start_hook=None,
                    expected_dependency_hashes=None, server_stdin=None):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        instance = root / "instance"
        output = root / "output"
        instance.mkdir()
        output.mkdir()
        if manual:
            report = ({'passed': True, 'phase': phase or 'single', 'scenario': scenario}
                      if client_report is None else client_report)
            (output / "players.json").write_text(json.dumps(report), encoding="utf8")
            if finished:
                (output / "finished").touch()
        server = Mock(pid=1234, returncode=None,
                      stdin=io.BytesIO() if server_stdin is None else server_stdin)
        server.poll.side_effect = lambda: server.returncode

        def wait_server(seconds):
            if forced_stop and server.returncode is None:
                raise subprocess.TimeoutExpired("server", seconds)
            server.returncode = 0
            return 0

        server.wait.side_effect = wait_server
        client = Mock(pid=1235, returncode=client_exit)
        client.poll.side_effect = lambda: client.returncode

        def popen(command, **kwargs):
            if start_error:
                raise start_error
            if len(command) > 1 and command[1] == "-Xms512M":
                if server_start_hook:
                    server_start_hook(instance)
                kwargs["stdout"].write(startup.encode())
                kwargs["stdout"].flush()
                return server
            if client_start_hook:
                client_start_hook(instance)
            report = ({'passed': True, 'phase': phase or 'single', 'scenario': scenario}
                      if client_report is None else client_report)
            (output / "players.json").write_text(json.dumps(report), encoding="utf8")
            return client

        def kill(process):
            if cleanup_error:
                raise subprocess.TimeoutExpired("client", 10)
            process.returncode = -9

        with patch.object(RUNNER.subprocess, "Popen", side_effect=popen) as launch, \
                patch.object(RUNNER, "terminate_tree", side_effect=kill) as terminate, \
                patch.object(RUNNER.time, "sleep", side_effect=KeyboardInterrupt if cancelled else None), \
                patch.object(RUNNER, "print", create=True), \
                patch.object(RUNNER.time, "monotonic", side_effect=clock or [0, 1, 2, 3, 4, 5]):
            result = RUNNER.run_session(instance, output, "java", "node", manual, timeout=timeout,
                                        phase=phase, state_file=root / 'state.json' if phase else None,
                                        scenario=scenario, expected_dependency_hashes=expected_dependency_hashes)
        self.assertEqual(result, json.loads((output / "result.json").read_text("utf8")))
        return result, launch, terminate, server, client

    def test_success_requires_client_exit_zero_and_records_process_results(self):
        result, launch, _, _, _ = self.run_fixture()
        self.assertTrue(result["passed"])
        self.assertEqual("client-finished", result["completionReason"])
        self.assertEqual(0, result["clientExit"])
        self.assertEqual(2, launch.call_count)

    def test_failed_smoke_never_starts_the_client(self):
        result, launch, _, _, _ = self.run_fixture(startup=STARTUP.replace("failures=0", "failures=1"))
        self.assertFalse(result["passed"])
        self.assertEqual("startup-failed", result["completionReason"])
        self.assertEqual(1, launch.call_count)

    def test_timeout_rejects_a_passed_report_and_kills_client_descendants(self):
        result, _, terminate, _, client = self.run_fixture(client_exit=None, timeout=2, clock=[0, 1, 3])
        self.assertFalse(result["passed"])
        self.assertTrue(result["timedOut"])
        self.assertTrue(result["client"]["passed"])
        terminate.assert_called_once_with(client)

    def test_nonzero_client_exit_cannot_be_hidden_by_a_passed_report(self):
        result, _, _, _, _ = self.run_fixture(client_exit=1)
        self.assertFalse(result["passed"])
        self.assertEqual(1, result["clientExit"])

    def test_truthy_nonboolean_pass_field_is_rejected(self):
        result, _, _, _, _ = self.run_fixture(client_report={"passed": "false"})
        self.assertFalse(result["passed"])

    def test_forced_server_shutdown_cannot_pass(self):
        result, _, terminate, server, _ = self.run_fixture(forced_stop=True)
        self.assertFalse(result["passed"])
        self.assertTrue(result["forcedServerStop"])
        terminate.assert_called_once_with(server)

    def test_spawn_failure_still_writes_a_failed_result(self):
        result, launch, _, _, _ = self.run_fixture(start_error=FileNotFoundError("java"))
        self.assertFalse(result["passed"])
        self.assertIsNone(result["serverExit"])
        self.assertEqual(1, launch.call_count)
        self.assertIn("FileNotFoundError", result["errors"][0])

    def test_logged_exception_fails_even_with_successful_smokes_and_client(self):
        result, _, _, _, _ = self.run_fixture(startup=STARTUP + "Caused by: java.lang.IllegalStateException\n")
        self.assertFalse(result["passed"])
        self.assertTrue(result["errors"])

    def test_manual_mode_requires_the_explicit_finished_marker(self):
        result, launch, _, _, _ = self.run_fixture(manual=True, finished=True)
        self.assertTrue(result["passed"])
        self.assertEqual("manual-finished", result["completionReason"])
        self.assertEqual(1, launch.call_count)
        result, _, _, _, _ = self.run_fixture(manual=True, timeout=2, clock=[0, 1, 3])
        self.assertFalse(result["passed"])
        self.assertTrue(result["timedOut"])

    def test_client_cleanup_failure_does_not_skip_stopping_the_server(self):
        result, _, _, server, _ = self.run_fixture(
            client_exit=None, timeout=2, clock=[0, 1, 3], cleanup_error=True)
        self.assertFalse(result["passed"])
        self.assertEqual(0, server.returncode)
        self.assertTrue(any("client process tree" in error for error in result["errors"]))

    def test_user_interrupt_stops_processes_and_records_cancellation(self):
        result, _, terminate, _, client = self.run_fixture(client_exit=None, cancelled=True)
        self.assertFalse(result["passed"])
        self.assertEqual("cancelled", result["completionReason"])
        terminate.assert_called_once_with(client)

    def test_restart_phase_is_explicit_and_checked_in_the_client_report(self):
        result, launch, _, _, _ = self.run_fixture(phase='verify', client_report={
            'passed': True, 'phase': 'verify', 'scenario': 'legacy'})
        self.assertTrue(result['passed'])
        self.assertEqual('verify', result['phase'])
        environment = launch.call_args.kwargs['env']
        self.assertEqual('verify', environment['SENLUO_SCENARIO_PHASE'])
        self.assertTrue(Path(environment['SENLUO_SCENARIO_STATE']).is_absolute())
        result, _, _, _, _ = self.run_fixture(phase='empty', client_report={'passed': True, 'phase': 'seed'})
        self.assertFalse(result['passed'])
        self.assertIn('requested restart phase', result['errors'][0])

    def test_default_scenario_does_not_inherit_restart_environment(self):
        with patch.dict(os.environ, SENLUO_SCENARIO_PHASE='verify', SENLUO_SCENARIO_STATE='old.json',
                        SENLUO_SCENARIO_KIND='gameplay'):
            result, launch, _, _, _ = self.run_fixture()
        self.assertTrue(result['passed'])
        self.assertNotIn('SENLUO_SCENARIO_PHASE', launch.call_args.kwargs['env'])
        self.assertNotIn('SENLUO_SCENARIO_STATE', launch.call_args.kwargs['env'])
        self.assertNotIn('SENLUO_SCENARIO_KIND', launch.call_args.kwargs['env'])

    def test_gameplay_phase_requires_matching_report_and_forwards_explicit_kind(self):
        report = {'passed': True, 'phase': 'verify', 'scenario': 'gameplay'}
        result, launch, _, _, _ = self.run_fixture(phase='verify', scenario='gameplay', client_report=report)
        self.assertTrue(result['passed'])
        self.assertEqual('gameplay', result['scenario'])
        self.assertEqual('gameplay', launch.call_args.kwargs['env']['SENLUO_SCENARIO_KIND'])
        for wrong_kind in (None, 'legacy', True):
            with self.subTest(wrong_kind=wrong_kind):
                result, _, _, _, _ = self.run_fixture(phase='verify', scenario='gameplay',
                    client_report={**report, 'scenario': wrong_kind})
                self.assertFalse(result['passed'])
                self.assertIn('requested gameplay scenario', result['errors'][0])

    def test_gameplay_cannot_run_without_a_restart_phase_or_as_an_unknown_kind(self):
        for kind in ('gameplay', 'unknown'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):
                    RUNNER.run_session(Path(folder), Path(folder), 'java', 'node', scenario=kind)

    def test_single_phase_rejects_restart_or_missing_report_identity(self):
        for identity in (
            {'phase': 'verify', 'scenario': 'legacy'},
            {'phase': 'single', 'scenario': 'gameplay'},
            {'phase': 'verify', 'scenario': 'gameplay'},
            {'phase': 'single'}, {'scenario': 'legacy'}, {},
        ):
            with self.subTest(identity=identity):
                result, _, _, _, _ = self.run_fixture(client_report={'passed': True, **identity})
                self.assertFalse(result['passed'])
                self.assertTrue(any('Client report did not complete' in error for error in result['errors']))

    def test_manual_diagnosis_requires_the_same_single_phase_and_scenario_identity(self):
        for identity in (
            {'phase': 'verify', 'scenario': 'gameplay'},
            {'phase': 'single', 'scenario': 'gameplay'},
            {'phase': 'empty', 'scenario': 'legacy'}, {},
        ):
            with self.subTest(identity=identity):
                result, launch, _, _, _ = self.run_fixture(manual=True, finished=True,
                    client_report={'passed': True, **identity})
                self.assertFalse(result['passed'])
                self.assertEqual('manual-finished', result['completionReason'])
                self.assertEqual(1, launch.call_count)

    def test_legacy_phase_cannot_accept_a_gameplay_success_report(self):
        result, _, _, _, _ = self.run_fixture(phase='verify', scenario='legacy', client_report={
            'passed': True, 'phase': 'verify', 'scenario': 'gameplay'})
        self.assertFalse(result['passed'])
        self.assertIn('requested legacy scenario', result['errors'][0])

    def test_probe_allowlist_covers_only_the_named_gameplay_commands(self):
        for action in ('gameclear', 'gameseed', 'gamestock', 'gameinspect', 'gamecombat', 'gamecancel'):
            self.assertIsNotNone(RUNNER.PROBE.fullmatch(f'senluoprobe 123 {action} SenLuoProbeA'))
        for action in ('gameinject', 'op', 'gameseed extra', 'gameinspect\nstop'):
            self.assertIsNone(RUNNER.PROBE.fullmatch(f'senluoprobe 123 {action} SenLuoProbeA'))

    def test_combat_observation_reaches_server_without_forwarding_unapproved_commands(self):
        sink = Mock(wraps=io.BytesIO())
        allowed = ['senluoprobe 42 gamecombat SenLuoProbeA',
                   'senluoprobe 43 gamecombat SenLuoProbeB']
        rejected = ['senluoprobe 44 gamecombat OtherPlayer',
                    'senluoprobe 45 gamecombat SenLuoProbeA extra',
                    'senluoprobe 46 gamecombat;stop SenLuoProbeA',
                    'op SenLuoProbeA', 'stop']

        def commands(instance):
            (instance.parent / 'output/commands.txt').write_text(
                '\n'.join(allowed + rejected) + '\n', encoding='utf8')

        result, _, _, _, _ = self.run_fixture(phase='verify', scenario='gameplay',
            client_start_hook=commands, server_stdin=sink)
        self.assertTrue(result['passed'])
        self.assertEqual([((line + '\n').encode('ascii'),) for line in allowed] + [(b'stop\n',)],
                         [call.args for call in sink.write.call_args_list])

    def test_startup_generated_remapped_jars_are_captured_at_ready_before_the_client(self):
        def arrange(instance):
            jar = instance / 'plugins/.paper-remapped/Probe.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'normal startup remap')
        result, _, _, _, _ = self.run_fixture(server_start_hook=arrange)
        self.assertTrue(result['passed'])
        self.assertEqual({'plugins/.paper-remapped/Probe.jar': hashlib.sha256(b'normal startup remap').hexdigest()},
                         result['runtimeDependencyHashes'])

    def test_ready_to_shutdown_jar_mutation_addition_or_removal_cannot_pass(self):
        def arrange(instance):
            jar = instance / 'plugins/.paper-remapped/Probe.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'normal startup remap')
        for action in ('change', 'add', 'remove'):
            def mutate(instance):
                jar = instance / 'plugins/.paper-remapped/Probe.jar'
                if action == 'change':
                    jar.write_bytes(b'different execution code')
                elif action == 'add':
                    (jar.parent / 'LateLibrary.jar').write_bytes(b'late code')
                else:
                    jar.unlink()
            with self.subTest(action=action):
                result, _, _, _, _ = self.run_fixture(server_start_hook=arrange, client_start_hook=mutate)
                self.assertFalse(result['passed'])
                self.assertIn('Runtime dependency JARs changed after server readiness', result['errors'])

    def test_normal_nonjar_cache_updates_after_ready_do_not_invalidate_execution_hashes(self):
        def cache_update(instance):
            cache = instance / 'plugins/CraftEngine/cache/runtime.json'
            cache.parent.mkdir(parents=True)
            cache.write_text('{"normal":"cache update"}', 'utf8')
        result, _, _, _, _ = self.run_fixture(client_start_hook=cache_update)
        self.assertTrue(result['passed'])
        self.assertEqual({}, result['runtimeDependencyHashes'])

    def test_ready_dependency_mismatch_stops_before_launching_the_protocol_client(self):
        def different_remap(instance):
            jar = instance / 'plugins/.paper-remapped/Probe.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'changed at startup')
        result, launch, _, _, _ = self.run_fixture(phase='verify', server_start_hook=different_remap,
            expected_dependency_hashes={'plugins/.paper-remapped/Probe.jar': hashlib.sha256(b'seed baseline').hexdigest()})
        self.assertFalse(result['passed'])
        self.assertEqual('startup-failed', result['completionReason'])
        self.assertEqual(1, launch.call_count, 'Do not launch protocol actions against changed execution bytes')
        self.assertIsNone(result['clientExit'])
        self.assertTrue(any('differ from the seed-ready baseline' in error for error in result['errors']))


class RestartSuiteTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.instance = self.root / 'instance'
        self.out = self.root / 'output'
        self.out.mkdir()
        pack = self.instance / 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip'
        pack.parent.mkdir(parents=True)
        pack.write_bytes(b'hosted-pack')
        (self.instance / 'server.jar').write_bytes(b'fixed-server')
        self.plugin = self.instance / 'plugins/TestPlugin.jar'
        self.plugin.write_bytes(b'fixed-plugin')

    def run_suite(self, callback=None, scenario='legacy'):
        def session(instance, out, java, node, *, phase, state_file, **options):
            self.assertEqual(self.instance, instance)
            self.assertEqual(self.out / 'persistence-state.json', state_file)
            self.assertEqual(phase, out.name)
            self.assertEqual({'port': 25591}, json.loads((out / 'fixture.json').read_text('utf8')))
            expected_options = {'scenario': 'gameplay'} if scenario == 'gameplay' else {}
            if phase != 'seed':
                expected_options['expected_dependency_hashes'] = RUNNER.runtime_dependency_hashes(instance)
            self.assertEqual(expected_options, options)
            if callback:
                callback(phase, state_file)
            elif phase == 'seed':
                state_file.write_text(json.dumps({'uuid': 'original-furniture', 'scenario': scenario}), 'utf8')
            elif phase == 'verify' and scenario == 'gameplay':
                state_file.with_name(state_file.name + '.verified.json').write_text(json.dumps({
                    'schemaVersion': 1, 'phase': 'verify', 'scenario': scenario,
                    'seedStateSha256': hashlib.sha256(state_file.read_bytes()).hexdigest(),
                    'players': {'fixture': 'original inventory'}}), 'utf8')
            return {'passed': True, 'phase': phase, 'scenario': scenario,
                    'runtimeDependencyHashes': RUNNER.runtime_dependency_hashes(instance)}
        with patch.object(RUNNER, 'run_session', side_effect=session) as launch:
            result = RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {'port': 25591},
                                              scenario=scenario)
        self.assertEqual(result, json.loads((self.out / 'result.json').read_text('utf8')))
        return result, launch

    def test_three_normal_starts_reuse_world_and_preserve_seed_and_binaries(self):
        result, launch = self.run_suite()
        self.assertTrue(result['passed'])
        self.assertEqual(['seed', 'verify', 'empty'], [phase['phase'] for phase in result['phases']])
        self.assertEqual(3, launch.call_count)
        self.assertEqual(64, len(result['seedStateSha256']))
        self.assertEqual(3, len(result['runtimeHashes']))
        self.assertEqual('seed-ready', result['runtimeDependencyBaseline'])
        self.assertEqual(RUNNER.runtime_dependency_hashes(self.instance), result['runtimeDependencyHashes'])

    def test_gameplay_runs_three_starts_with_explicit_matching_scenario(self):
        result, launch = self.run_suite(scenario='gameplay')
        self.assertTrue(result['passed'])
        self.assertEqual('gameplay', result['scenario'])
        self.assertEqual(3, launch.call_count)

    def test_gameplay_rejects_a_checkpoint_from_the_legacy_family(self):
        def legacy_seed(phase, state_file):
            state_file.write_text('{"uuid":"original-furniture","scenario":"legacy"}', 'utf8')
        result, launch = self.run_suite(legacy_seed, scenario='gameplay')
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('seed snapshot belongs', result['errors'][0])

    def test_gameplay_rejects_a_successful_phase_from_a_different_family(self):
        with patch.object(RUNNER, 'run_session', return_value={
                'passed': True, 'phase': 'seed', 'scenario': 'legacy'}) as launch:
            result = RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {}, scenario='gameplay')
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('different gameplay scenario', result['errors'][0])

    def test_gameplay_recovery_checkpoint_must_bind_the_original_seed(self):
        def wrong_seed_binding(phase, state_file):
            if phase == 'seed':
                state_file.write_text('{"scenario":"gameplay","uuid":"original"}', 'utf8')
            elif phase == 'verify':
                state_file.with_name(state_file.name + '.verified.json').write_text(json.dumps({
                    'schemaVersion': 1, 'phase': 'verify', 'scenario': 'gameplay',
                    'seedStateSha256': '0' * 64}), 'utf8')
        result, launch = self.run_suite(wrong_seed_binding, scenario='gameplay')
        self.assertFalse(result['passed'])
        self.assertEqual(2, launch.call_count)
        self.assertIn('immutable gameplay seed', result['errors'][0])

    def test_seed_cannot_precreate_a_future_recovery_checkpoint(self):
        def premature_verification(phase, state_file):
            state_file.write_text('{"scenario":"gameplay","uuid":"original"}', 'utf8')
            state_file.with_name(state_file.name + '.verified.json').write_text(json.dumps({
                'schemaVersion': 1, 'phase': 'verify', 'scenario': 'gameplay',
                'seedStateSha256': hashlib.sha256(state_file.read_bytes()).hexdigest()}), 'utf8')
        result, launch = self.run_suite(premature_verification, scenario='gameplay')
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('premature verification', result['errors'][0])

    def test_empty_phase_cannot_rewrite_its_expected_recovered_inventory(self):
        def rewrite_inventory(phase, state_file):
            verified = state_file.with_name(state_file.name + '.verified.json')
            if phase == 'seed':
                state_file.write_text('{"scenario":"gameplay","uuid":"original"}', 'utf8')
            elif phase == 'verify':
                verified.write_text(json.dumps({'schemaVersion': 1, 'phase': 'verify',
                    'scenario': 'gameplay', 'players': {'A': ['unique-item']},
                    'seedStateSha256': hashlib.sha256(state_file.read_bytes()).hexdigest()}), 'utf8')
            else:
                verified.write_text('{"players":{"A":[]}}', 'utf8')
        result, launch = self.run_suite(rewrite_inventory, scenario='gameplay')
        self.assertFalse(result['passed'])
        self.assertEqual(3, launch.call_count)
        self.assertIn('changed the verified inventory', result['errors'][0])

    def test_missing_or_invalid_seed_snapshot_stops_before_restart(self):
        for seed in (None, '{}', '[]', 'broken'):
            with self.subTest(seed=seed), tempfile.TemporaryDirectory() as folder:
                self.out = Path(folder)
                def write(phase, state_file):
                    if seed is not None:
                        state_file.write_text(seed, 'utf8')
                result, launch = self.run_suite(write)
                self.assertFalse(result['passed'])
                self.assertEqual(1, launch.call_count)
                self.assertTrue(result['errors'])

    def test_rewritten_seed_snapshot_cannot_hide_restoration_failure(self):
        def mutate(phase, state_file):
            state_file.write_text('{"state":"' + phase + '"}', 'utf8')
        result, launch = self.run_suite(mutate)
        self.assertFalse(result['passed'])
        self.assertEqual(2, launch.call_count)
        self.assertIn('original seed', result['errors'][0])

    def test_changed_runtime_stops_the_suite(self):
        def change_plugin(phase, state_file):
            state_file.write_text('{"uuid":"original-furniture"}', 'utf8')
            self.plugin.write_bytes(b'other-plugin')
        result, launch = self.run_suite(change_plugin)
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('binaries', result['errors'][0])

    def test_changed_scenario_code_stops_the_suite(self):
        with patch.object(RUNNER, 'scenario_hashes', side_effect=[{'script': 'original'},
                          {'script': 'original'}, {'script': 'changed'}]):
            result, launch = self.run_suite()
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('scenario code changed', result['errors'][0])

    def test_first_start_may_generate_remapped_and_version_jars_before_seed_ready(self):
        def normal_startup(phase, state_file):
            if phase == 'seed':
                state_file.write_text('{"uuid":"original"}', 'utf8')
                for name in ('plugins/.paper-remapped/Probe.jar', 'versions/1.21.11/paper.jar',
                             'libraries/group/dependency.jar', 'plugins/CraftEngine/libs/extra.jar'):
                    jar = self.instance / name
                    jar.parent.mkdir(parents=True, exist_ok=True)
                    jar.write_bytes(name.encode())
        result, launch = self.run_suite(normal_startup)
        self.assertTrue(result['passed'])
        self.assertEqual(3, launch.call_count)
        self.assertEqual(6, len(result['runtimeDependencyHashes']))
        self.assertEqual('seed-ready', result['runtimeDependencyBaseline'])

    def test_later_ready_time_jar_set_must_match_the_seed_ready_baseline(self):
        def late_jar(phase, state_file):
            if phase == 'seed':
                state_file.write_text('{"uuid":"original"}', 'utf8')
            elif phase == 'verify':
                jar = self.instance / 'plugins/.paper-remapped/Other.jar'
                jar.parent.mkdir(parents=True)
                jar.write_bytes(b'newly introduced execution dependency')
        result, launch = self.run_suite(late_jar)
        self.assertFalse(result['passed'])
        self.assertEqual(2, launch.call_count)
        self.assertIn('differ from the seed-ready baseline', result['errors'][0])

    def test_post_session_dependency_check_rejects_a_false_success_after_ready_jar_changes(self):
        def session(instance, out, java, node, *, phase, state_file):
            ready = RUNNER.runtime_dependency_hashes(instance)
            state_file.write_text('{"uuid":"original"}', 'utf8')
            jar = instance / 'libraries/changed.jar'
            jar.parent.mkdir()
            jar.write_bytes(b'after the ready snapshot')
            return {'passed': True, 'phase': phase, 'scenario': 'legacy', 'runtimeDependencyHashes': ready}
        with patch.object(RUNNER, 'run_session', side_effect=session) as launch:
            result = RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {})
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('Runtime dependency JARs changed during the seed', result['errors'][0])

    def test_between_phase_dependency_change_is_rejected_before_starting_the_next_server(self):
        original = RUNNER.runtime_dependency_hashes
        calls = 0
        def dependencies(instance):
            nonlocal calls
            calls += 1
            if calls == 3:
                jar = instance / 'libraries/between-starts.jar'
                jar.parent.mkdir()
                jar.write_bytes(b'different execution set')
            return original(instance)
        with patch.object(RUNNER, 'runtime_dependency_hashes', side_effect=dependencies):
            result, launch = self.run_suite()
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('Runtime dependency JARs changed between restart phases', result['errors'][0])

    def test_missing_ready_dependency_manifest_cannot_be_hidden_by_a_passing_client(self):
        with patch.object(RUNNER, 'run_session', return_value={
                'passed': True, 'phase': 'seed', 'scenario': 'legacy'}) as launch:
            result = RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {})
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('did not record ready-time runtime dependency JARs', result['errors'][0])

    def test_failed_phase_stops_without_starting_later_phases(self):
        with patch.object(RUNNER, 'run_session', return_value={'passed': False, 'phase': 'seed'}) as launch:
            result = RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {})
        self.assertFalse(result['passed'])
        self.assertEqual(1, launch.call_count)
        self.assertIn('seed', result['errors'][0])

    def test_previous_seed_file_is_rejected_without_starting_a_server(self):
        (self.out / 'persistence-state.json').write_text('{"old":true}', 'utf8')
        with patch.object(RUNNER, 'run_session') as launch, self.assertRaises(FileExistsError):
            RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {})
        launch.assert_not_called()


class ScenarioSourceLockTests(unittest.TestCase):
    def test_recursive_dependency_hashes_stream_all_jars_and_ignore_nonjar_cache_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            names = ('server.jar', 'versions/1.21.11/paper.jar', 'plugins/.paper-remapped/Probe.jar',
                     'plugins/.paper-remapped/remap-classpath/runtime.jar', 'libraries/dependency.jar',
                     'plugins/CraftEngine/libs/extra.jar')
            for name in names:
                jar = root / name
                jar.parent.mkdir(parents=True, exist_ok=True)
                jar.write_bytes(name.encode())
            (root / 'plugins/CraftEngine/cache.json').write_text('{"mutable":true}', 'utf8')
            with patch.object(Path, 'read_bytes', side_effect=AssertionError('Hash JARs with streaming file_digest')):
                hashes = RUNNER.runtime_dependency_hashes(root)
            self.assertEqual({name: hashlib.sha256(name.encode()).hexdigest() for name in names}, hashes)

    def test_directly_executed_resource_reader_has_an_explicit_relative_source_lock(self):
        hashes = RUNNER.scenario_hashes()
        name = '../VanillaPack12111.java'
        self.assertIn(name, hashes)
        reader = Path(RUNNER.__file__).parent / name
        self.assertEqual(hashlib.sha256(reader.read_bytes()).hexdigest(), hashes[name])

    def test_changing_only_the_resource_reader_changes_the_source_lock(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            scripts = root / 'connected'
            scripts.mkdir()
            for name in ('run.py', 'players.cjs', 'persistence-players.cjs', 'gameplay-persistence.cjs'):
                (scripts / name).write_text('unchanged-' + name, 'utf8')
            reader = root / 'VanillaPack12111.java'
            reader.write_text('original reader', 'utf8')
            with patch.object(RUNNER, '__file__', str(scripts / 'run.py')):
                original = RUNNER.scenario_hashes()
                reader.write_text('different reader', 'utf8')
                changed = RUNNER.scenario_hashes()
            self.assertNotEqual(original['../VanillaPack12111.java'], changed['../VanillaPack12111.java'])
            self.assertEqual({name: value for name, value in original.items() if name != '../VanillaPack12111.java'},
                             {name: value for name, value in changed.items() if name != '../VanillaPack12111.java'})


class ProcessTreeTests(unittest.TestCase):
    def test_completed_clients_are_not_terminated(self):
        process = Mock()
        process.poll.return_value = 0
        RUNNER.terminate_tree(process)
        process.kill.assert_not_called()
        process.wait.assert_not_called()

    def test_timeout_terminates_the_platform_process_tree(self):
        process = Mock(pid=4567)
        process.poll.return_value = None
        with patch.object(RUNNER.subprocess, "run") as taskkill, patch.object(RUNNER.os, "killpg", create=True) as killpg:
            RUNNER.terminate_tree(process)
        if os.name == "nt":
            self.assertEqual(["taskkill", "/PID", "4567", "/T", "/F"], taskkill.call_args.args[0])
            killpg.assert_not_called()
        else:
            killpg.assert_called_once_with(4567, RUNNER.signal.SIGKILL)
            taskkill.assert_not_called()
        process.wait.assert_called_once_with(timeout=10)


if __name__ == "__main__":
    unittest.main()
