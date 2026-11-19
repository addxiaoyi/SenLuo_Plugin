"""The connected runner must fail closed without starting Minecraft in unit tests."""
from __future__ import annotations

import importlib.util
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
                    manual=False, finished=False, cleanup_error=False, cancelled=False, phase=None):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        instance = root / "instance"
        output = root / "output"
        instance.mkdir()
        output.mkdir()
        if manual:
            (output / "players.json").write_text(json.dumps({"passed": True}), encoding="utf8")
            if finished:
                (output / "finished").touch()
        server = Mock(pid=1234, returncode=None, stdin=io.BytesIO())
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
                kwargs["stdout"].write(startup.encode())
                kwargs["stdout"].flush()
                return server
            report = {"passed": True} if client_report is None else client_report
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
                                        phase=phase, state_file=root / 'state.json' if phase else None)
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
        result, launch, _, _, _ = self.run_fixture(phase='verify', client_report={'passed': True, 'phase': 'verify'})
        self.assertTrue(result['passed'])
        self.assertEqual('verify', result['phase'])
        environment = launch.call_args.kwargs['env']
        self.assertEqual('verify', environment['SENLUO_SCENARIO_PHASE'])
        self.assertTrue(Path(environment['SENLUO_SCENARIO_STATE']).is_absolute())
        result, _, _, _, _ = self.run_fixture(phase='empty', client_report={'passed': True, 'phase': 'seed'})
        self.assertFalse(result['passed'])
        self.assertIn('requested restart phase', result['errors'][0])

    def test_default_scenario_does_not_inherit_restart_environment(self):
        with patch.dict(os.environ, SENLUO_SCENARIO_PHASE='verify', SENLUO_SCENARIO_STATE='old.json'):
            result, launch, _, _, _ = self.run_fixture()
        self.assertTrue(result['passed'])
        self.assertNotIn('SENLUO_SCENARIO_PHASE', launch.call_args.kwargs['env'])
        self.assertNotIn('SENLUO_SCENARIO_STATE', launch.call_args.kwargs['env'])


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

    def run_suite(self, callback=None):
        def session(instance, out, java, node, *, phase, state_file):
            self.assertEqual(self.instance, instance)
            self.assertEqual(self.out / 'persistence-state.json', state_file)
            self.assertEqual(phase, out.name)
            self.assertEqual({'port': 25591}, json.loads((out / 'fixture.json').read_text('utf8')))
            if callback:
                callback(phase, state_file)
            elif phase == 'seed':
                state_file.write_text('{"uuid":"original-furniture"}', 'utf8')
            return {'passed': True, 'phase': phase}
        with patch.object(RUNNER, 'run_session', side_effect=session) as launch:
            result = RUNNER.run_restart_suite(self.instance, self.out, 'java', 'node', {'port': 25591})
        self.assertEqual(result, json.loads((self.out / 'result.json').read_text('utf8')))
        return result, launch

    def test_three_normal_starts_reuse_world_and_preserve_seed_and_binaries(self):
        result, launch = self.run_suite()
        self.assertTrue(result['passed'])
        self.assertEqual(['seed', 'verify', 'empty'], [phase['phase'] for phase in result['phases']])
        self.assertEqual(3, launch.call_count)
        self.assertEqual(64, len(result['seedStateSha256']))
        self.assertEqual(3, len(result['runtimeHashes']))

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
