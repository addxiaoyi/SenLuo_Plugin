"""Run the native CE gameplay/configuration smoke on a disposable loopback instance.

This does not simulate players, assert restart persistence, or accept client visuals.
The probe must be enabled explicitly in CookeryAcceptanceProbe/config.yml.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import importlib.util
from itertools import chain, islice
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from zipfile import ZipFile, BadZipFile

ROOT = Path(__file__).resolve().parents[2]
# Direct CLI invocation starts with tools/acceptance on sys.path.
if str(ROOT / 'tools') not in sys.path:
    sys.path.insert(0, str(ROOT / 'tools'))
import village_publication_candidate as VILLAGE_CANDIDATE
import village_publication_launch as VILLAGE_LAUNCH
import village_publication_report as VILLAGE_REPORT
from run_enchantment_acceptance import runtime_failures
SPEC = importlib.util.spec_from_file_location("senluo_connected_runner", Path(__file__).with_name("connected") / "run.py")
CONNECTED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONNECTED)
MAX_CONSOLE_BYTES = 67_108_864
MAX_CONSOLE_LINE_BYTES = 1_048_576
WORKFLOW_STARTED = re.compile(r'正在执行资源包工作流\s+default\b|Running resource pack workflow\s+default\b', re.I)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@contextmanager
def instance_lock(instance: Path):
    """An OS-released lock also survives an interrupted runner without a stale approval step."""
    with (instance / ".series-gameplay-run.lock").open("a+b") as handle:
        if handle.seek(0, 2) == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def write_new_json(path: Path, value: dict) -> None:
    raw = (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf8')
    VILLAGE_REPORT.require(len(raw) <= VILLAGE_REPORT.MAX_REPORT_BYTES, 'Runner evidence exceeds its JSON bound')
    with VILLAGE_CANDIDATE.fs_path(path).open('xb') as target:
        target.write(raw)


class BoundedErrors(list):
    """Bound only the result summary; the complete original console remains on disk."""
    def __init__(self): super().__init__(); self.total = 0
    def append(self, value):
        self.total += 1
        if len(self) < 128: super().append(str(value)[:4096])
    def extend(self, values):
        for value in values: self.append(value)
    def describe(self, result):
        result['errorCount'] = self.total
        result['errorSummaryTruncated'] = self.total > len(self)


class VillageMode:
    """Evidence policy for the common server loop; never prepares or changes server inputs."""
    def __init__(self, candidate: dict, launch: dict, instance: Path, output: Path, result: dict):
        self.candidate, self.launch, self.instance, self.output, self.result = candidate, launch, instance, output, result
        stem = 'village-publication-smoke-' + candidate['runId']
        self.registration = VILLAGE_CANDIDATE.contained(instance, 'plugins/CookeryAcceptanceProbe/' + stem + '-registration.json')
        self.report = VILLAGE_CANDIDATE.contained(instance, 'plugins/CookeryAcceptanceProbe/' + stem + '.json')
        self.pack = VILLAGE_CANDIDATE.contained(instance, 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip')
        self.startup = CONNECTED.Startup()
        self.exact_smokes = set()
        self.workflow_offset = None
        self.pack_before_workflow = None
        self.pack_witness = None
        self.receipt_witness = None
        self.receipt_snapshots = None
        self.stop_marker = False
        self.stop_offset = None
        self.completed = False

    def preflight(self) -> None:
        # Validate both source JSON copies and every execution input inside the held instance lock,
        # immediately before Popen. Environment values rejected by this API are never printed.
        VILLAGE_LAUNCH.validate(self.launch, self.candidate, os.environ)
        VILLAGE_CANDIDATE.verify(self.candidate, before_start=True)
        VILLAGE_REPORT.require('eula=true' in VILLAGE_CANDIDATE.contained(self.instance, 'eula.txt').read_text('utf8').splitlines(),
                               'The operator must already have accepted the Minecraft EULA')

    def command(self) -> tuple[list[str], dict]:
        return list(self.launch['argv']), {
            'cwd': self.launch['cwd'], 'env': dict(self.launch['environment']), 'shell': False,
            'creationflags': subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
            'start_new_session': os.name != 'nt',
        }

    def feed(self, line: str, offset: int, *, stopping: bool = False) -> None:
        self.startup.feed(line)
        for name, expected in (('COOKERY_FOOD_SMOKE', {'checked': '103', 'failures': '0'}),
                               ('COOKERY_ENCHANTMENT_SMOKE', {'checked': '5', 'reloaded': '5', 'failures': '0'})):
            if re.search(r'\b' + name + r'\b', line):
                tokens = re.findall(r'\b(checked|reloaded|failures)=([0-9]+)\b', line)
                observed = dict(tokens)
                VILLAGE_REPORT.require(len(tokens) == len(expected) and len(tokens) == len(observed) and observed == expected,
                                       'Missing, duplicate or failed exact startup smoke: ' + name)
                self.exact_smokes.add(name)
        after_stop = stopping and self.stop_offset is not None and offset >= self.stop_offset
        if self.workflow_offset is not None and offset >= self.workflow_offset:
            started = WORKFLOW_STARTED.search(line)
            completed = CONNECTED.WORKFLOW_COMPLETED.search(line)
            workflow = self.result['workflow']
            VILLAGE_REPORT.require(not (started and completed), 'Workflow start and completion must be separate console records')
            if started and workflow.get('startLogOffset') != offset:
                VILLAGE_REPORT.require('startLogOffset' not in workflow and not after_stop, 'Unexpected repeated or late workflow start')
                workflow.update(startedAt=utc_now(), startLogOffset=offset)
            if completed and workflow.get('completeLogOffset') != offset:
                VILLAGE_REPORT.require('completeLogOffset' not in workflow and not after_stop
                                       and workflow.get('startLogOffset', offset) < offset,
                                       'Unexpected repeated, late or unpaired workflow completion')
                workflow.update(completedAt=utc_now(), completeLogOffset=offset)
                self.completed = True
        if after_stop and re.search(r'\bStopping server\b', line, re.I):
            self.stop_marker = True
        failures = runtime_failures(line)
        ce_warning = ('CraftEngine' in line and re.search(r'Failed to (?:tick|load|unload|save|serialize)\b|发现\s*[1-9]\d*\s*个问题|(?:found|detected)\s+[1-9]\d*\s+(?:issues|errors|problems)', line, re.I))
        if CONNECTED.LOG_ERROR.search(line) or failures or ce_warning:
            VILLAGE_REPORT.require(False, 'Server runtime failure: ' + line.rstrip()[:4096])

    def workflow(self, log: Path) -> None:
        self.pack_before_workflow = self.observe_pack()
        self.workflow_offset = log.stat().st_size
        self.result['workflow'] = {'command': 'ce workflow default', 'issuedAt': utc_now(),
                                   'issuedAtNs': time.time_ns(), 'logOffset': self.workflow_offset,
                                   'packBefore': self.pack_before_workflow, 'startedAt': None, 'completedAt': None}

    def observe_pack(self) -> dict:
        path = VILLAGE_CANDIDATE.contained(self.instance, 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip')
        if not path.exists():
            return {'present': False}
        def identity() -> tuple[int, int, int, int]:
            attrs = VILLAGE_CANDIDATE.fs_path(VILLAGE_CANDIDATE.unlinked_path(path)).stat()
            return attrs.st_dev, attrs.st_ino, attrs.st_size, attrs.st_mtime_ns

        before = identity()
        value = VILLAGE_CANDIDATE.fingerprint(path)
        VILLAGE_REPORT.require(identity() == before and value['bytes'] == before[2], 'Hosted ZIP changed while being observed')
        return {'present': True, **value, 'modifiedAtNs': before[3],
                'device': before[0], 'inode': before[1]}

    def observe_receipt(self) -> dict | None:
        # UUID and CREATE_NEW source receipts are authoritative; mtime alone is never proof.
        directory = self.report.parent
        stem = 'village-publication-smoke-' + self.candidate['runId']
        VILLAGE_REPORT._receipt_markers(directory, stem)
        if not self.report.exists():
            return None
        VILLAGE_REPORT.require(self.registration.exists(), 'Final village receipt lacks its registration')
        for path in (self.registration, self.report):
            VILLAGE_CANDIDATE.unlinked_path(path)
        snapshots = VILLAGE_REPORT.load_receipt_snapshots(self.registration, self.report, self.candidate['expected'])
        summary = VILLAGE_REPORT.validate(snapshots[0].value, snapshots[1].value, self.candidate['expected'])
        report = snapshots[1].value
        VILLAGE_REPORT.require(VILLAGE_REPORT._time(report['registeredAt']) >= VILLAGE_REPORT._time(self.result['processStartedAt']),
                               'Village registration predates this exact server process')
        VILLAGE_REPORT.require(VILLAGE_REPORT._time(report['completedAt']) <= VILLAGE_REPORT._time(utc_now()),
                               'Village completion is dated after its actual observation')
        observed = {snapshot.path.name: {'bytes': len(snapshot.raw), 'sha256': hashlib.sha256(snapshot.raw).hexdigest()}
                    for snapshot in snapshots}
        if self.receipt_witness is not None:
            VILLAGE_REPORT.require(observed == self.receipt_witness, 'Immutable village receipts changed while the server was running')
        self.receipt_witness = observed
        self.receipt_snapshots = snapshots
        self.result['receiptSummary'] = summary
        return {'runId': report['runId'], 'passed': True, 'status': 'PASS', 'file': self.report.name,
                **observed[self.report.name]}

    def observe_generated_pack(self) -> bool:
        if not self.completed:
            return False
        observed = self.observe_pack()
        if not observed['present'] or observed == self.pack_before_workflow:
            return False
        VILLAGE_REPORT.require(observed['modifiedAtNs'] >= self.result['workflow']['issuedAtNs'],
                               'Hosted ZIP was not written after this workflow command')
        with VILLAGE_CANDIDATE.bounded_zip(self.pack) as archive:
            entries = archive.infolist()
            VILLAGE_REPORT.require(entries and len(entries) <= 10_000 and len(entries) == len(set(archive.namelist()))
                                   and all(0 <= entry.file_size <= 16_777_216 for entry in entries)
                                   and all(entry.file_size == 0 or entry.compress_size > 0
                                           and entry.file_size <= entry.compress_size * 2_000 for entry in entries)
                                   and sum(entry.file_size for entry in entries) <= 268_435_456,
                                   'Generated hosted ZIP exceeds its bounded resource set')
            VILLAGE_REPORT.require(archive.testzip() is None and 'pack.mcmeta' in archive.namelist(), 'Generated hosted ZIP CRC or metadata is invalid')
        VILLAGE_REPORT.require(self.observe_pack() == observed, 'Hosted ZIP changed during resource validation')
        self.pack_witness = observed
        self.result['resourcePack'] = observed
        return True

    def ready(self) -> bool:
        return self.startup.ready and len(self.exact_smokes) == 2

    def copy_stable(self, path: Path, name: str, expected: dict) -> None:
        VILLAGE_CANDIDATE.unlinked_path(path)
        VILLAGE_REPORT.require(VILLAGE_CANDIDATE.fingerprint(path) == expected, 'Source evidence changed before immutable copy: ' + name)
        output = self.output / name
        count = 0
        with VILLAGE_CANDIDATE.fs_path(path).open('rb') as source, output.open('xb') as target:
            while block := source.read(65_536):
                count += len(block)
                VILLAGE_REPORT.require(count <= expected['bytes'], 'Source evidence grew while copying: ' + name)
                target.write(block)
        VILLAGE_REPORT.require(VILLAGE_CANDIDATE.fingerprint(path) == expected
                               and VILLAGE_CANDIDATE.fingerprint(output) == expected, 'Copied evidence differs from its frozen observation: ' + name)

    def finish(self) -> None:
        VILLAGE_REPORT.require(self.result.get('normalStopRequested') is True and self.result.get('normalStopWaitCompleted') is True
                               and self.stop_marker, 'Server did not acknowledge the runner normal stop and terminate normally')
        VILLAGE_REPORT.require(self.ready(), 'Server did not complete its exact food/enchantment startup checks')
        VILLAGE_REPORT.require(self.completed and self.pack_witness is not None, 'Native workflow/resource evidence is incomplete')
        VILLAGE_REPORT.require(self.observe_pack() == self.pack_witness, 'Hosted ZIP changed after workflow completion')
        VILLAGE_REPORT.require(self.receipt_witness is not None, 'Current complete cold-start receipts are missing')
        summary, registered, reported = VILLAGE_CANDIDATE.verify_receipt_snapshots(self.candidate, self.registration, self.report)
        self.save_receipts((registered, reported))
        self.copy_stable(self.pack, 'resource_pack.zip', {key: self.pack_witness[key] for key in ('bytes', 'sha256')})
        self.result['receiptSummary'] = summary
        self.result['startup'] = 'PASS'
        self.result['resourceWorkflow'] = 'PASS'
        self.result['normalStopAcknowledged'] = True

    def save_receipts(self, snapshots) -> None:
        for snapshot in snapshots:
            expected = self.receipt_witness.get(snapshot.path.name) if self.receipt_witness else None
            actual = {'bytes': len(snapshot.raw), 'sha256': hashlib.sha256(snapshot.raw).hexdigest()}
            VILLAGE_REPORT.require(actual == expected, 'Validated native receipts changed between live observation and final verification')
            with (self.output / snapshot.path.name).open('xb') as target:
                target.write(snapshot.raw)

    def retain_failed_evidence(self) -> None:
        """Keep bounded original bytes even when malformed; they never become a successful receipt."""
        if self.receipt_snapshots is not None:
            for snapshot in self.receipt_snapshots:
                target = self.output / snapshot.path.name
                if not target.exists():
                    try:
                        with target.open('xb') as stream: stream.write(snapshot.raw)
                    except OSError as error: self.result['errors'].append('Validated snapshot retention: ' + str(error))
        stem = 'village-publication-smoke-' + self.candidate['runId']
        paths = chain((self.registration, self.report), self.report.parent.glob(stem + '-*after-enable.json'),
                      self.report.parent.glob(stem + '-duplicate-callback-*.json'))
        for path in islice(paths, 32):
            if not path.exists() or (self.output / path.name).exists(): continue
            try:
                expected = VILLAGE_CANDIDATE.fingerprint(path)
                VILLAGE_REPORT.require(expected['bytes'] <= VILLAGE_REPORT.MAX_REPORT_BYTES, 'Failed receipt exceeds bounded copy budget')
                self.copy_stable(path, path.name, expected)
            except (OSError, ValueError) as error: self.result['errors'].append('Failed evidence copy: ' + str(error))
        if self.pack.exists() and not (self.output / 'resource_pack.zip').exists():
            try: self.copy_stable(self.pack, 'resource_pack.zip', VILLAGE_CANDIDATE.fingerprint(self.pack))
            except (OSError, ValueError) as error: self.result['errors'].append('Failed hosted ZIP evidence copy: ' + str(error))


def run(args) -> int:
    if getattr(args, 'village_candidate', None) is not None or getattr(args, 'village_launch', None) is not None:
        return run_village(args)
    instance, output = args.instance.resolve(), args.output.resolve()
    for path in (instance, output):
        if not path.is_relative_to(ROOT / "build/acceptance"):
            raise ValueError("Expected a disposable path under build/acceptance")
    properties = dict(line.split("=", 1) for line in (instance / "server.properties").read_text("utf8").splitlines()
                      if "=" in line and not line.startswith("#"))
    if any(properties.get(key) != value for key, value in {
        "server-ip": "127.0.0.1", "level-name": "acceptance-world", "enable-rcon": "false"
    }.items()):
        raise ValueError("Expected an isolated loopback acceptance-world with RCON disabled")
    if "eula=true" not in (instance / "eula.txt").read_text("utf8").splitlines():
        raise ValueError("The operator must already have accepted the Minecraft EULA")
    output.mkdir(parents=True, exist_ok=False)
    return run_loop(args, instance, output)


def run_village(args) -> int:
    # Validate output containment before creating evidence, even if a malformed frozen input is vetoed.
    output = VILLAGE_CANDIDATE.unlinked_path(Path(args.output_dir))
    acceptance = VILLAGE_CANDIDATE.unlinked_path(ROOT / 'build/acceptance')
    VILLAGE_REPORT.require(output.is_relative_to(acceptance) and output != acceptance and output.parent.is_dir(),
                           'Expected a new evidence directory under existing build/acceptance')
    VILLAGE_REPORT.require(not output.exists(), 'Evidence output directory must be new')
    result = {'schema': 'village-publication-run-v1', 'mode': 'village-publication', 'scope': 'COLD_START_ROLLBACK_AND_RESOURCE_WORKFLOW_ONLY',
              'instance': None, 'runId': None, 'startup': 'NOT_RUN', 'passed': False,
              'serverExit': None, 'forcedServerStop': False, 'normalStopRequested': False, 'normalStopWaitCompleted': False,
              'normalStopAcknowledged': False, 'errors': BoundedErrors(), 'probe': None, 'packGenerated': False,
              'naturalVillageIntegration': 'NOT_ACTIVATED', 'controlledFailureBoundary': 'UNPROVEN_IF_UNEXPECTED_PARTIAL_STATE'}
    output_created = False
    overlaps_instance = False
    try:
        VILLAGE_REPORT.require(args.village_candidate is not None and args.village_launch is not None,
                               'Village mode requires both the candidate and the exact launch record')
        candidate = VILLAGE_REPORT.read_json(Path(args.village_candidate))
        VILLAGE_REPORT.require(type(candidate) is dict, 'Frozen candidate must be a JSON object')
        VILLAGE_REPORT.require(type(candidate.get('instance')) is str and Path(candidate['instance']).is_absolute(),
                               'Frozen instance path must be absolute')
        instance = VILLAGE_CANDIDATE.unlinked_path(Path(candidate['instance']))
        overlaps_instance = output.is_relative_to(instance) or instance.is_relative_to(output)
        VILLAGE_REPORT.require(not overlaps_instance, 'Village instance/output must be separate disposable paths')
        VILLAGE_REPORT.require(
            instance.is_relative_to(acceptance) and instance != acceptance and instance.is_dir(),
            'Village instance must be an existing disposable path under this checkout/build/acceptance')
        result['instance'] = str(instance)
        run_id = candidate.get('runId')
        try: VILLAGE_REPORT._run_id(run_id)
        except ValueError: run_id = None
        result['runId'] = run_id
        output.mkdir(exist_ok=False)
        output_created = True
        launch = VILLAGE_REPORT.read_json(Path(args.village_launch))
        mode = VillageMode(candidate, launch, instance, output, result)
        write_new_json(output / 'frozen-candidate.json', candidate)
        write_new_json(output / 'frozen-launch.json', launch)
        result['candidateSha256'] = VILLAGE_LAUNCH.candidate_digest(candidate)
        result['launchSha256'] = hashlib.sha256(json.dumps(launch, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf8')).hexdigest()
        VILLAGE_CANDIDATE.unlinked_path(instance / '.series-gameplay-run.lock')
        with instance_lock(instance):
            return run_loop(args, instance, output, mode)
    except (OSError, ValueError, KeyError, TypeError, RecursionError, BadZipFile, subprocess.SubprocessError) as error:
        if not output_created:
            if overlaps_instance or output.exists():
                print(json.dumps({'passed': False, 'startup': 'NOT_RUN', 'output': str(output),
                                  'errors': ['Village preflight: ' + type(error).__name__ + ': ' + str(error)]}), flush=True)
                return 1
            output.mkdir(exist_ok=False)
        result['errors'].append('Village preflight: ' + type(error).__name__ + ': ' + str(error))
        result['errors'].describe(result)
        write_new_json(output / 'result.json', result)
        print(json.dumps({'passed': False, 'startup': 'NOT_RUN', 'output': str(output), 'errors': result['errors']}), flush=True)
        return 1


def run_loop(args, instance: Path, output: Path, village: VillageMode | None = None) -> int:
    log = output / "console.log"
    report_dir = instance / "plugins/CookeryAcceptanceProbe"
    probe = report_dir / "series-gameplay-smoke.json"
    pack = instance / "plugins/CraftEngine/cache/hosted/default/resource_pack.zip"
    started = time.time()
    run_id = village.candidate['runId'] if village is not None else str(uuid.uuid4())
    result = village.result if village is not None else {"scope": "native CE definitions/placement/in-process NBT smoke; no player, restart or visual acceptance",
              "instance": str(instance), "runId": run_id, "serverExit": None, "forcedServerStop": False, "errors": [],
              "probe": None, "packGenerated": False, "passed": False,
              "productionJars": {path.name: sha(path) for path in sorted((instance / "plugins").glob("Kaleidoscope*.jar"))},
              "probeJarSha256": sha(instance / "plugins/CookeryAcceptanceProbe.jar"),
              "serverJarSha256": sha(instance / "server.jar")}
    server = None
    generated = False
    workflow_completed = False
    with log.open("wb") as stdout, log.open("rb") as tail:
        try:
            command = [args.java, "-Xms512M", "-Xmx2200M", "-Dfile.encoding=UTF-8",
                                       "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                                       "-Dterminal.jline=false", "-Dterminal.ansi=false",
                                       f"-Dsenluo.series.smoke.run_id={run_id}", "-jar", "server.jar", "nogui"] if village is None else None
            options = {'cwd': instance, 'creationflags': subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
                       'start_new_session': os.name != 'nt'}
            if village is not None:
                village.preflight()
                command, options = village.command()
                result['actualProcessSpec'] = {'argv': command, **village.launch['processOptions'], 'cwd': options['cwd'],
                                              'environment': options['env']}
                result['processStartedAt'] = utc_now()
            server = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=stdout, stderr=subprocess.STDOUT, **options)
            if village is not None:
                result['startup'] = 'RUNNING'; result['serverPid'] = server.pid
            deadline = time.monotonic() + 300
            while server.poll() is None and time.monotonic() < deadline:
                if village is not None:
                    VILLAGE_REPORT.require(log.stat().st_size <= MAX_CONSOLE_BYTES, 'Console log exceeds its complete evidence bound')
                while True:
                    at = tail.tell()
                    raw = tail.readline(MAX_CONSOLE_LINE_BYTES + 1) if village is not None else tail.readline()
                    if village is not None:
                        VILLAGE_REPORT.require(len(raw) <= MAX_CONSOLE_LINE_BYTES, 'Console line exceeds its inspection bound')
                    if not raw or not raw.endswith(b"\n"):
                        tail.seek(at)
                        break
                    line = raw.decode("utf8", "replace")
                    if village is not None:
                        village.feed(line, at)
                    elif re.search(r"资源包工作流 default 执行完成|Resource pack workflow default (?:completed|finished)", line, re.I):
                        workflow_completed = True
                    ready_to_generate = village.startup.done if village is not None else "Done (" in line
                    if ready_to_generate and not generated:
                        if village is not None:
                            village.workflow(log)
                        server.stdin.write(b"ce workflow default\n")
                        server.stdin.flush()
                        generated = True
                if village is not None:
                    result['probe'] = village.observe_receipt()
                    if not result['packGenerated']:
                        result['packGenerated'] = village.observe_generated_pack()
                    if result['probe'] is not None and result['packGenerated'] and village.ready():
                        break
                    time.sleep(.1)
                    continue
                if probe.exists() and probe.stat().st_mtime >= started:
                    try:
                        candidate = json.loads(probe.read_text("utf8"))
                        if not isinstance(candidate, dict) or not isinstance(candidate.get("passed"), bool):
                            result["errors"].append("Malformed current native probe report")
                            break
                        if candidate.get("runId") == run_id:
                            result["probe"] = candidate
                    except (ValueError, OSError):
                        pass
                if workflow_completed and pack.exists() and pack.stat().st_mtime >= started and not result["packGenerated"]:
                    try:
                        with ZipFile(pack) as archive:
                            result["packGenerated"] = bool(archive.namelist()) and archive.testzip() is None
                    except (BadZipFile, OSError):
                        pass
                if result["probe"] is not None and (result["probe"].get("passed") is False or result["packGenerated"]):
                    break
                time.sleep(.1)
            else:
                result["errors"].append("Server exited early or native smoke/resource workflow timed out")
        except KeyboardInterrupt:
            result['errors'].append('Runner interrupted; the isolated candidate is failed and retained')
        except (OSError, ValueError, KeyError, TypeError, RecursionError, BadZipFile, subprocess.SubprocessError) as failure:
            result["errors"].append(f"Runner: {type(failure).__name__}: {failure}")
        finally:
            if server is not None and server.poll() is None:
                try:
                    if village is not None:
                        village.stop_offset = log.stat().st_size
                    server.stdin.write(b"stop\n")
                    server.stdin.flush()
                    if village is not None:
                        result['normalStopRequested'] = True; result['normalStopRequestedAt'] = utc_now()
                    server.wait(timeout=60)
                    if village is not None:
                        result['normalStopWaitCompleted'] = True; result['serverExitedAt'] = utc_now()
                except subprocess.TimeoutExpired:
                    result["forcedServerStop"] = True
                    try: CONNECTED.terminate_tree(server)
                    except (OSError, subprocess.SubprocessError) as failure: result['errors'].append('Cannot terminate owned server: ' + str(failure))
                except (OSError, subprocess.SubprocessError) as failure:
                    result["errors"].append(f"Shutdown: {failure}")
                    if village is not None:
                        result['forcedServerStop'] = True
                    try: CONNECTED.terminate_tree(server)
                    except (OSError, subprocess.SubprocessError) as force_failure: result['errors'].append('Cannot terminate owned server: ' + str(force_failure))
            if server is not None:
                result["serverExit"] = server.poll()
                if server.stdin is not None:
                    try: server.stdin.close()
                    except OSError as failure:
                        if village is None: raise
                        result['errors'].append('Cannot close owned server input: ' + str(failure))
                if village is not None:
                    result['serverStillRunning'] = server.poll() is None
    if village is not None:
        try:
            VILLAGE_REPORT.require(log.stat().st_size <= MAX_CONSOLE_BYTES, 'Console log exceeds evidence inspection bound')
            with log.open('rb') as lines:
                while True:
                    offset = lines.tell(); raw = lines.readline(MAX_CONSOLE_LINE_BYTES + 1)
                    if not raw: break
                    VILLAGE_REPORT.require(len(raw) <= MAX_CONSOLE_LINE_BYTES, 'Console line exceeds inspection bound')
                    try: village.feed(raw.decode('utf8', 'replace'), offset, stopping=result['normalStopRequested'])
                    except (ValueError, KeyError, TypeError) as failure: result['errors'].append('Final console: ' + str(failure))
            result['consoleInspectionComplete'] = True
            village.finish()
        except (OSError, ValueError, KeyError, TypeError, RecursionError, BadZipFile) as failure:
            result['errors'].append('Village final verification: ' + type(failure).__name__ + ': ' + str(failure))
        result['normalStopAcknowledged'] = village.stop_marker
        result['startupReady'] = village.ready()
        result['elapsedSeconds'] = round(time.time() - started, 3)
        try: result['logSha256'] = VILLAGE_CANDIDATE.fingerprint(log)['sha256']
        except (OSError, ValueError) as failure: result['errors'].append('Console fingerprint: ' + str(failure))
        result['passed'] = (result['serverExit'] == 0 and not result['forcedServerStop'] and not result['errors']
                            and result['packGenerated'] and result['startup'] == 'PASS' and result['normalStopAcknowledged'])
        if not result['passed'] and result['startup'] != 'NOT_RUN': result['startup'] = 'FAIL'
        if not result['passed']: village.retain_failed_evidence()
        result['errors'].describe(result)
        write_new_json(output / 'result.json', result)
        print(json.dumps({'passed': result['passed'], 'output': str(output), 'errors': result['errors'][:8]}, ensure_ascii=False), flush=True)
        return 0 if result['passed'] else 1
    result["errors"].extend(line for line in log.read_text("utf8", errors="replace").splitlines() if CONNECTED.LOG_ERROR.search(line))
    for name in ("food-smoke.json", "enchantment-smoke.json", "series-gameplay-smoke.json"):
        source = report_dir / name
        if source.exists() and source.stat().st_mtime >= started:
            (output / name).write_bytes(source.read_bytes())
    result["logSha256"] = sha(log)
    if result["packGenerated"]:
        result["packSha256"] = sha(pack)
    result["elapsedSeconds"] = round(time.time() - started, 3)
    result["passed"] = (result["serverExit"] == 0 and not result["forcedServerStop"] and not result["errors"]
                        and result["packGenerated"] and isinstance(result["probe"], dict) and result["probe"].get("passed") is True)
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", "utf8")
    print(json.dumps({"passed": result["passed"], "elapsedSeconds": result["elapsedSeconds"], "output": str(output),
                      "errors": result["errors"][:8]}, ensure_ascii=False), flush=True)
    return 0 if result["passed"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path, nargs='?')
    parser.add_argument("output", type=Path, nargs='?')
    parser.add_argument("--java")
    parser.add_argument('--village-candidate', type=Path)
    parser.add_argument('--village-launch', type=Path)
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    if args.village_candidate is not None or args.village_launch is not None:
        if args.village_candidate is None or args.village_launch is None or args.output_dir is None or any(value is not None for value in (args.instance, args.output, args.java)):
            parser.error('Village mode requires --village-candidate, --village-launch and --output-dir; positional paths/--java are unsupported')
        return run(args)
    if args.instance is None or args.output is None or args.java is None or args.output_dir is not None:
        parser.error('Legacy mode requires instance output --java and cannot use --output-dir')
    instance = args.instance.resolve()
    if not instance.is_relative_to(ROOT / "build/acceptance") or not instance.is_dir():
        raise ValueError("Expected an existing disposable instance under build/acceptance")
    with instance_lock(instance):
        return run(args)


if __name__ == "__main__":
    sys.exit(main())
