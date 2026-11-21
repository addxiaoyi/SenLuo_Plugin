"""Exercise the native-series runner offline; no Java process or live instance is used."""
from __future__ import annotations

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZIP_STORED, ZipFile


SPEC = importlib.util.spec_from_file_location(
    "series_gameplay_acceptance_runner", Path(__file__).parent / "acceptance/run_series_gameplay.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

DONE = '[08:00:00 INFO]: Done (1.234s)! For help, type "help"\n'
COMPLETED = '[08:00:01 INFO]: » CraftEngine • 资源包工作流 default 执行完成（1000毫秒）\n'
PACK_CONTENT = b'{"pack":{"pack_format":75,"description":"offline CRC regression fixture"}}'


class FakeClock:
    """Advance only on runner sleeps, making a 300-second timeout instantaneous."""
    def __init__(self):
        self.started = 1_700_000_000.0
        self.elapsed = 0.0
        self.sleeps = 0
        self.on_sleep = lambda: None

    def time(self):
        return self.started + self.elapsed

    def monotonic(self):
        return self.elapsed

    def sleep(self, seconds):
        if seconds <= 0:
            raise AssertionError("Runner must yield between polling attempts")
        self.sleeps += 1
        self.elapsed += 100
        self.on_sleep()


class FakeInput:
    def __init__(self, process):
        self.process = process
        self.commands = []
        self.closed = False

    def write(self, command):
        self.commands.append(command)
        if command == b"ce workflow default\n":
            self.process.workflow()
        elif command == b"stop\n":
            self.process.stop_requested = True
        else:
            raise AssertionError(f"Unexpected server command: {command!r}")
        return len(command)

    def flush(self):
        pass

    def close(self):
        self.closed = True


class FakeServer:
    """Produce real reports and ZIP bytes only in response to the runner's commands."""
    def __init__(self, command, stdout, fixture, *, marker=COMPLETED, report=None,
                 zip_kind="valid", exit_code=0, log_error="", deferred_marker=False,
                 report_age=0):
        self.command = command
        self.stdout = stdout
        self.fixture = fixture
        self.marker = marker
        self.zip_kind = zip_kind
        self.exit_code = exit_code
        self.log_error = log_error
        self.deferred_marker = deferred_marker
        self.returncode = None
        self.stop_requested = False
        self.wait_timeouts = []
        self.stdin = FakeInput(self)
        self.run_id = next(arg.split("=", 1)[1] for arg in command
                           if arg.startswith("-Dsenluo.series.smoke.run_id="))
        candidate = {"runId": self.run_id, "passed": True} if report is None else report(self.run_id)
        fixture.probe.write_text(json.dumps(candidate), encoding="utf8")
        fixture.current_mtime(fixture.probe, age=report_age)
        self.emit(DONE)

    def emit(self, message):
        self.stdout.write(message.encode("utf8"))
        self.stdout.flush()

    def workflow(self):
        pack = self.fixture.pack
        if self.zip_kind == "truncated":
            pack.write_bytes(b"PK\x03\x04unfinished archive")
        else:
            with ZipFile(pack, "w", compression=ZIP_STORED) as archive:
                if self.zip_kind != "empty":
                    archive.writestr("pack.mcmeta", PACK_CONTENT)
            if self.zip_kind == "bad_crc":
                content = bytearray(pack.read_bytes())
                offset = content.index(PACK_CONTENT)
                content[offset] ^= 1  # Keep a valid central directory and corrupt the member's CRC.
                pack.write_bytes(content)
        self.fixture.current_mtime(pack)
        if not self.deferred_marker:
            self.emit(self.marker)
        self.emit(self.log_error)

    def poll(self):
        return self.returncode

    def wait(self, *, timeout):
        if not self.stop_requested:
            raise AssertionError("Runner waited without requesting normal shutdown")
        self.wait_timeouts.append(timeout)
        self.emit("[08:00:02 INFO]: Stopping server\n")
        self.returncode = self.exit_code
        return self.returncode


class RunnerFixture:
    def __init__(self, root):
        self.root = root
        self.instance = root / "build/acceptance/instance"
        self.output = root / "build/acceptance/evidence"
        self.instance.mkdir(parents=True)
        self.clock = FakeClock()
        self.probe = self.instance / "plugins/CookeryAcceptanceProbe/series-gameplay-smoke.json"
        self.pack = self.instance / "plugins/CraftEngine/cache/hosted/default/resource_pack.zip"
        self.probe.parent.mkdir(parents=True)
        self.pack.parent.mkdir(parents=True)
        (self.instance / "server.properties").write_text(
            "server-ip=127.0.0.1\nlevel-name=acceptance-world\nenable-rcon=false\n", encoding="utf8")
        (self.instance / "eula.txt").write_text("eula=true\n", encoding="utf8")
        for name in ("server.jar", "plugins/CookeryAcceptanceProbe.jar", "plugins/KaleidoscopeTavernPlugin.jar"):
            (self.instance / name).write_bytes(b"offline placeholder; never executed")
        self.args = SimpleNamespace(instance=self.instance, output=self.output, java="DO_NOT_EXECUTE_JAVA")
        self.server = None

    def current_mtime(self, path, *, age=0):
        stamp = self.clock.time() - age
        os.utime(path, (stamp, stamp))

    def run(self, **scenario):
        def launch(command, **kwargs):
            if self.server is not None:
                raise AssertionError("Only one server process may be started")
            if kwargs["cwd"] != self.instance or command[0] != self.args.java:
                raise AssertionError("Runner escaped the offline fixture")
            self.server = FakeServer(command, kwargs["stdout"], self, **scenario)
            if scenario.get("deferred_marker"):
                self.clock.on_sleep = lambda: self.server.emit(self.server.marker)
            return self.server

        with patch.object(RUNNER, "ROOT", self.root), \
                patch.object(RUNNER.subprocess, "Popen", side_effect=launch) as popen, \
                patch.object(RUNNER.time, "time", side_effect=self.clock.time), \
                patch.object(RUNNER.time, "monotonic", side_effect=self.clock.monotonic), \
                patch.object(RUNNER.time, "sleep", side_effect=self.clock.sleep), \
                patch.object(RUNNER.CONNECTED, "terminate_tree") as force_stop, \
                redirect_stdout(io.StringIO()):
            status = RUNNER.run(self.args)
        if popen.call_count != 1 or force_stop.called:
            raise AssertionError("Fixture must use one normally stopped fake process")
        evidence = self.output / "result.json"
        if not evidence.is_file():
            raise AssertionError("Runner failed to persist result.json")
        return status, json.loads(evidence.read_text("utf8"))


class SeriesGameplayRunnerTests(unittest.TestCase):
    def fixture(self):
        folder = tempfile.TemporaryDirectory(prefix="senluo-series-runner-offline-")
        self.addCleanup(folder.cleanup)
        return RunnerFixture(Path(folder.name).resolve())

    def assert_failed_with_evidence(self, fixture, **scenario):
        status, result = fixture.run(**scenario)
        self.assertEqual(1, status)
        self.assertFalse(result["passed"])
        self.assertEqual([b"ce workflow default\n", b"stop\n"], fixture.server.stdin.commands)
        self.assertEqual([60], fixture.server.wait_timeouts)
        self.assertTrue(fixture.server.stdin.closed)
        self.assertTrue((fixture.output / "console.log").is_file())
        return result

    def test_pass_requires_current_run_id_completed_workflow_valid_zip_and_clean_exit(self):
        for marker in (COMPLETED, "[08:00:01 INFO]: Resource pack workflow default completed\n",
                       "[08:00:01 INFO]: Resource pack workflow default finished\n"):
            with self.subTest(marker=marker):
                fixture = self.fixture()
                status, result = fixture.run(marker=marker)
                self.assertEqual(0, status)
                self.assertTrue(result["passed"])
                self.assertEqual(result["runId"], fixture.server.run_id)
                self.assertEqual(result["runId"], result["probe"]["runId"])
                self.assertEqual(0, result["serverExit"])
                self.assertFalse(result["forcedServerStop"])
                self.assertEqual([], result["errors"])
                self.assertTrue(result["packGenerated"])
                self.assertEqual(hashlib.sha256(fixture.pack.read_bytes()).hexdigest(), result["packSha256"])
                self.assertEqual(hashlib.sha256((fixture.output / "console.log").read_bytes()).hexdigest(), result["logSha256"])
                self.assertEqual([b"ce workflow default\n", b"stop\n"], fixture.server.stdin.commands)
                self.assertEqual([60], fixture.server.wait_timeouts)

    def test_fresh_valid_zip_without_completion_marker_times_out_instead_of_passing(self):
        fixture = self.fixture()
        result = self.assert_failed_with_evidence(fixture, marker="")
        with ZipFile(fixture.pack) as archive:
            self.assertIsNone(archive.testzip())
        self.assertFalse(result["packGenerated"])
        self.assertTrue(any("timed out" in message for message in result["errors"]))

    def test_stop_waits_for_completion_even_after_the_fresh_zip_and_probe_exist(self):
        fixture = self.fixture()
        status, result = fixture.run(deferred_marker=True)
        self.assertEqual(0, status)
        self.assertTrue(result["passed"])
        self.assertGreaterEqual(fixture.clock.sleeps, 1)
        self.assertEqual([b"ce workflow default\n", b"stop\n"], fixture.server.stdin.commands)

    def test_completed_workflow_with_truncated_empty_or_bad_crc_zip_fails(self):
        for kind in ("truncated", "empty", "bad_crc"):
            with self.subTest(kind=kind):
                fixture = self.fixture()
                result = self.assert_failed_with_evidence(fixture, zip_kind=kind)
                self.assertFalse(result["packGenerated"])
                if kind == "bad_crc":
                    with ZipFile(fixture.pack) as archive:
                        self.assertEqual("pack.mcmeta", archive.testzip())

    def test_current_mtime_report_with_an_old_run_id_cannot_be_reused(self):
        fixture = self.fixture()
        result = self.assert_failed_with_evidence(
            fixture, report=lambda run_id: {"runId": "different-previous-run", "passed": True})
        self.assertIsNone(result["probe"])
        self.assertTrue(result["packGenerated"])
        self.assertTrue(any("timed out" in message for message in result["errors"]))

    def test_matching_run_id_report_with_old_mtime_is_ignored(self):
        fixture = self.fixture()
        result = self.assert_failed_with_evidence(fixture, report_age=1)
        self.assertIsNone(result["probe"])

    def test_valid_array_json_is_a_recorded_failure_instead_of_an_attribute_error(self):
        fixture = self.fixture()
        result = self.assert_failed_with_evidence(fixture, report=lambda run_id: [])
        self.assertIn("Malformed current native probe report", result["errors"])

    def test_report_passed_must_be_a_json_boolean(self):
        for value in (1, "true", None):
            with self.subTest(value=value):
                fixture = self.fixture()
                result = self.assert_failed_with_evidence(
                    fixture, report=lambda run_id: {"runId": run_id, "passed": value})
                self.assertIn("Malformed current native probe report", result["errors"])

    def test_native_probe_failure_cannot_be_hidden_by_a_successful_workflow(self):
        fixture = self.fixture()
        result = self.assert_failed_with_evidence(
            fixture, report=lambda run_id: {"runId": run_id, "passed": False, "failures": ["fixture failure"]})
        self.assertFalse(result["probe"]["passed"])
        self.assertEqual(["fixture failure"], result["probe"]["failures"])

    def test_nonzero_normal_server_exit_keeps_all_evidence_but_fails(self):
        fixture = self.fixture()
        result = self.assert_failed_with_evidence(fixture, exit_code=17)
        self.assertEqual(17, result["serverExit"])
        self.assertTrue(result["packGenerated"])
        self.assertTrue(result["probe"]["passed"])

    def test_error_and_watchdog_log_lines_fail_despite_successful_probe_and_exit(self):
        for line in ("[08:00:01 ERROR]: Offline regression error\n",
                     "[08:00:01 SEVERE]: Offline regression severe\n",
                     "[08:00:01 WARN]: The server has stopped responding!\n"):
            with self.subTest(line=line):
                fixture = self.fixture()
                result = self.assert_failed_with_evidence(fixture, log_error=line)
                self.assertIn(line.rstrip(), result["errors"])
                self.assertEqual(0, result["serverExit"])

    def test_instance_lock_is_exclusive_and_can_be_reacquired_after_release(self):
        fixture = self.fixture()
        with RUNNER.instance_lock(fixture.instance):
            with self.assertRaises(OSError):
                with RUNNER.instance_lock(fixture.instance):
                    self.fail("Concurrent instance lock unexpectedly succeeded")
        with RUNNER.instance_lock(fixture.instance):
            pass

    def test_non_loopback_instance_is_rejected_before_starting_a_process(self):
        fixture = self.fixture()
        properties = fixture.instance / "server.properties"
        properties.write_text(properties.read_text("utf8").replace("127.0.0.1", "0.0.0.0"), "utf8")
        with patch.object(RUNNER, "ROOT", fixture.root), patch.object(RUNNER.subprocess, "Popen") as launch:
            with self.assertRaisesRegex(ValueError, "isolated loopback"):
                RUNNER.run(fixture.args)
            launch.assert_not_called()

    def test_output_outside_disposable_root_is_rejected_before_starting_a_process(self):
        fixture = self.fixture()
        fixture.args.output = fixture.root / "outside-acceptance"
        with patch.object(RUNNER, "ROOT", fixture.root), patch.object(RUNNER.subprocess, "Popen") as launch:
            with self.assertRaisesRegex(ValueError, "disposable path"):
                RUNNER.run(fixture.args)
            launch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
