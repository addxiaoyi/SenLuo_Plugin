"""Run protocol acceptance against a disposable, already prepared loopback Paper/Folia instance."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from zipfile import BadZipFile, ZipFile

ROOT = Path(__file__).resolve().parents[3]
PROBE = re.compile(r'senluoprobe ([0-9]{1,8}) (arrive|supply|scarecrow|tulip|clearboard|sofa|clearsofa|inspectsofa|doll|cleardoll|inspectdoll|persistclear|persistseed|persiststock|persistinspect|gameclear|gameseed|gamestock|gameinspect|gamecombat|gamecancel|inspect) SenLuoProbe[AB]$')
CROP_ACTIONS = (
    'arrive', 'stagearena', 'stageages', 'stageplant', 'stagepick', 'stagesickle', 'stagebreak',
    'stageimmature', 'stagebone', 'stagemature', 'stagenatural', 'stagewet', 'stagedry', 'stagedense', 'stageinvalid', 'stagelight8', 'stagelight7',
    'stageboundary', 'stageboundarygrowth', 'stagelight9', 'stagesky', 'supportstone', 'stock', 'stockfortune', 'stockempty', 'armgrow', 'armfertilize',
    'arminteract', 'armsickle', 'armslot', 'armcount', 'armitem', 'armempty', 'armcrop', 'disarm',
    'observe', 'shapes', 'naturalstart', 'naturalstop', 'reloadstate', 'creative', 'survival', 'tiledropsoff', 'tiledropson',
    'checkpoint', 'verifycheckpoint', 'sealverify', 'emptycheckpoint',
) + tuple('arm' + kind + crop for kind in ('grow', 'fertilize') for crop in ('tomato', 'chili', 'lettuce', 'wheat')) + (
    'armoffcount', 'armoffitem', 'armoffempty',
)
CROP_PROBE = re.compile(r'senluocrop ([0-9]{1,8}) (' + '|'.join(CROP_ACTIONS) + r') SenLuoProbe[AB]$')
SOURCECROPS_TIMEOUTS = {'core': 1800, 'seed': 600, 'verify': 600, 'empty': 400}
CROP_NATIVE_COMMANDS = ('ce reload config', 'ce workflow default')
CORE_WINDOW_MILLIS = {'reload': 90000, 'workflow': 240000}
WORKFLOW_COMPLETED = re.compile(r'资源包工作流 default 执行完成|Resource pack workflow default (?:completed|finished)', re.IGNORECASE)


class CoreResources:
    """Authorize two finite native reload/workflow windows, not arbitrary pack drift."""
    def __init__(self, instance: Path, output: Path):
        self.pack = instance / 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip'
        self.lock = output / 'resource-pack-lock.json'
        if self.lock.exists():
            raise FileExistsError('Refusing to overwrite a core resource lock')
        self.observations: list[dict] = []
        self.reloads: list[dict] = []
        self.workflows: list[dict] = []
        self.epoch: int | None = None
        self.signature = None

    @property
    def pending_workflow(self):
        return self.workflows[-1] if self.workflows and self.workflows[-1]['completedAtMillis'] is None else None

    @property
    def pending_reload(self):
        return self.reloads[-1] if self.reloads and self.reloads[-1]['completedAtMillis'] is None else None

    def save(self):
        temporary = self.lock.with_suffix('.tmp')
        with temporary.open('x', encoding='utf8') as target:
            json.dump({'schema': 'source-crops-core-packs-v1', 'observations': self.observations,
                       'reloads': self.reloads, 'workflows': self.workflows}, target)
        temporary.replace(self.lock)

    def check_deadlines(self):
        now = time.time_ns() // 1000000
        for kind, value in (('reload', self.pending_reload), ('workflow', self.pending_workflow)):
            if value is not None and now > value['expiresAtMillis']:
                raise ValueError(f'Core native {kind} exceeded its bounded authorization window')

    def sample(self, *, force: bool = False, required: bool = False):
        self.check_deadlines()
        try:
            stat = self.pack.stat()
            signature = (stat.st_mtime_ns, stat.st_size)
            if not force and signature == self.signature:
                return
            contents = self.pack.read_bytes()
            current = self.pack.stat()
            if (current.st_mtime_ns, current.st_size) != signature:
                raise BadZipFile('Hosted pack changed while being sampled')
            checksum = hashlib.sha256(contents).hexdigest()
            with ZipFile(io.BytesIO(contents)) as archive:
                if not archive.namelist() or archive.testzip() is not None:
                    raise BadZipFile('Empty or corrupt hosted pack')
        except (OSError, BadZipFile):
            # CE can replace a ZIP while rebuilding. Never publish partial bytes.
            if self.pending_workflow is not None and not required:
                return
            raise
        if self.observations and checksum != self.observations[-1]['sha256'] and self.pending_workflow is None:
            raise ValueError('Core hosted resource bytes changed outside an authorized workflow window')
        self.signature = signature
        if not self.observations or checksum != self.observations[-1]['sha256']:
            self.observations.append({'sequence': len(self.observations) + 1, 'sha256': checksum,
                                      'bytes': len(contents), 'observedAtMillis': time.time_ns() // 1000000,
                                      'workflow': len(self.workflows)})
            self.save()

    def observe_reply(self, value: dict):
        self.check_deadlines()
        if value.get('schema') != 'source-crops-v1' or value.get('phase') != 'core' or 'epoch' not in value:
            return
        epoch = value['epoch']
        if type(epoch) is not int or epoch < 0:
            raise ValueError('Core reply contains an invalid native reload epoch')
        if self.epoch is None:
            self.epoch = epoch
        elif epoch != self.epoch:
            pending = self.pending_reload
            if pending is None or epoch != pending['beforeEpoch'] + 1:
                raise ValueError('Core native reload epoch changed outside the single authorized reload')
            pending.update(afterEpoch=epoch, completedAtMillis=time.time_ns() // 1000000)
            self.epoch = epoch
            self.save()

    def command(self, command: str):
        self.sample(force=True, required=True)
        issued = time.time_ns() // 1000000
        if command == 'ce reload config':
            if self.epoch is None or self.pending_reload is not None or self.pending_workflow is not None:
                raise ValueError('Core reload requires an observed epoch and no unfinished lifecycle operation')
            if len(self.reloads) != len(self.workflows) or len(self.reloads) >= 2:
                raise ValueError('Core requires exactly two alternating native reload/workflow rounds')
            self.reloads.append({'sequence': len(self.reloads) + 1, 'command': command, 'beforeEpoch': self.epoch,
                                 'afterEpoch': None, 'issuedAtMillis': issued, 'expiresAtMillis': issued + CORE_WINDOW_MILLIS['reload'],
                                 'completedAtMillis': None})
        elif command == 'ce workflow default':
            if self.pending_workflow is not None or self.pending_reload is not None:
                raise ValueError('Core workflow cannot overlap an unfinished lifecycle operation')
            if len(self.reloads) != len(self.workflows) + 1 or len(self.workflows) >= 2:
                raise ValueError('Core workflow requires its own completed native reload')
            self.workflows.append({'sequence': len(self.workflows) + 1, 'command': command,
                                   'beforeSha256': self.observations[-1]['sha256'],
                                   'afterSha256': None, 'issuedAtMillis': issued, 'expiresAtMillis': issued + CORE_WINDOW_MILLIS['workflow'],
                                   'completedAtMillis': None, 'completionLog': None})
        else:
            raise ValueError('Unknown core lifecycle command')
        self.save()

    def observe_log(self, line: str):
        self.check_deadlines()
        if not WORKFLOW_COMPLETED.search(line):
            return
        pending = self.pending_workflow
        if pending is None:
            raise ValueError('Unrequested native resource workflow completion during core')
        self.sample(force=True, required=True)
        pending.update(afterSha256=self.observations[-1]['sha256'], completedAtMillis=time.time_ns() // 1000000,
                       completionLog=line.rstrip('\r\n'))
        self.save()

    def verify(self):
        self.sample(force=True, required=True)
        if (len(self.reloads) != 2 or len(self.workflows) != 2
                or self.pending_reload is not None or self.pending_workflow is not None):
            raise ValueError('Core did not complete exactly two genuine reload/workflow rounds')


def validate_core_client_resources(output: Path, report: dict, resources: CoreResources):
    """Re-read every downloaded ZIP and native-reader result; report claims alone are insufficient."""
    history = report.get('packHistory')
    players = ('SenLuoProbeA', 'SenLuoProbeB')
    if (len(resources.reloads) != 2 or len(resources.workflows) != 2
            or resources.pending_reload is not None or resources.pending_workflow is not None):
        raise ValueError('Core native resource rounds are incomplete')
    if not isinstance(history, dict) or set(history) != set(players):
        raise ValueError('Core must retain native resource offer histories for both players')
    for player in players:
        offers = history[player]
        if not isinstance(offers, list) or len(offers) != 3:
            raise ValueError('Core must retain exactly the initial offer and two workflow offers per player')
        for index, offer in enumerate(offers):
            if (not isinstance(offer, dict) or type(offer.get('sequence')) is not int or type(offer.get('workflow')) is not int
                    or offer['sequence'] != index + 1 or offer['workflow'] != index):
                raise ValueError('Core offer sequence/workflow attribution differs')
            archive_name, reader_name = f'{player}-offer-{index + 1}.zip', f'{player}-offer-{index + 1}-resources.json'
            if offer.get('archive') != archive_name or offer.get('reader') != reader_name:
                raise ValueError('Core resource artifacts do not have their unique immutable names')
            data = (output / archive_name).read_bytes()
            checksum = hashlib.sha256(data).hexdigest()
            if offer.get('packSha256') != checksum or offer.get('bytes') != len(data) or offer.get('sha1') != hashlib.sha1(data).hexdigest():
                raise ValueError('Core downloaded resource bytes differ from the player receipt')
            with ZipFile(io.BytesIO(data)) as archive:
                if not archive.namelist() or archive.testzip() is not None:
                    raise ValueError('Core downloaded resource ZIP is corrupt or empty')
            observation = offer.get('observation')
            if type(observation) is not int or not 1 <= observation <= len(resources.observations):
                raise ValueError('Core downloaded resource lacks an actual hosted-pack observation')
            observed = resources.observations[observation - 1]
            if observed['sha256'] != checksum or observed['bytes'] != len(data) or observed['workflow'] > index:
                raise ValueError('Core downloaded resource does not match its authorized hosted bytes')
            expected = resources.observations[0]['sha256'] if index == 0 else resources.workflows[index - 1]['afterSha256']
            if checksum != expected:
                raise ValueError('Core workflow reoffer did not deliver its final actual hosted resource bytes')
            decoded = json.loads((output / reader_name).read_text('utf8'))
            if not isinstance(decoded, list) or len(decoded) != 1 or not isinstance(decoded[0], dict):
                raise ValueError('Core native reader did not report exactly one downloaded archive')
            native = decoded[0]
            if (native.get('packSha256') != checksum or native.get('client') != '1.21.11'
                    or type(native.get('assetsRead')) is not int or native['assetsRead'] <= 0
                    or any(offer.get(key) != value for key, value in native.items())):
                raise ValueError('Core official native reader result differs from its downloaded archive/receipt')
            downloaded, completed = offer.get('downloadedAtMillis'), offer.get('readerCompletedAtMillis')
            if type(downloaded) is not int or type(completed) is not int or downloaded > completed:
                raise ValueError('Core resource download/reader timestamps are invalid')
            if index:
                window = resources.workflows[index - 1]
                if not window['issuedAtMillis'] <= downloaded <= completed <= window['expiresAtMillis']:
                    raise ValueError('Core resource reoffer/reader escaped its bounded workflow window')


def allowed_probe(line: str, scenario: str, phase: str | None = None) -> bool:
    if scenario == 'sourcecrops':
        if line in CROP_NATIVE_COMMANDS:
            return phase == 'core'
        match = CROP_PROBE.fullmatch(line)
        if not match:
            return False
        if phase in ('verify', 'empty'):
            return match[2] in ('arrive', 'observe', 'shapes', 'reloadstate', 'verifycheckpoint', 'sealverify', 'emptycheckpoint')
        return True
    return bool(PROBE.fullmatch(line))


def session_budget(scenario: str, phase: str | None, manual: bool, timeout: float | None) -> float:
    if timeout is not None:
        return timeout
    if scenario == 'sourcecrops':
        return SOURCECROPS_TIMEOUTS[phase]
    return 1200 if manual else 400


SMOKE = re.compile(r'\b(COOKERY_(?:FOOD|ENCHANTMENT)_SMOKE)\b(.*)')
LOG_ERROR = re.compile(
    r'\[[^\]\r\n]*(?:ERROR|SEVERE|FATAL)\]|has not responded in|server has stopped responding'
    r'|^(?:\[[^\]\r\n]+\]\s*)?(?:Exception in thread |Caused by:|\s+at \S+\()'
    r'|^(?:[\w$]+\.)+[\w$]*(?:Exception|Error)(?::|$)', re.IGNORECASE)


class Startup:
    def __init__(self) -> None:
        self.done = False
        self.smokes: set[str] = set()
        self.errors: list[str] = []

    def feed(self, line: str) -> None:
        if re.search(r'\bDone \([0-9.]+s\)!', line):
            self.done = True
        match = SMOKE.search(line)
        if not match:
            return
        fields = dict(re.findall(r'\b(checked|reloaded|failures)=([0-9]+)\b', match[2]))
        valid = fields.get('failures') == '0' and int(fields.get('checked', '0')) > 0
        if match[1] == 'COOKERY_ENCHANTMENT_SMOKE':
            valid = valid and fields.get('reloaded') == fields.get('checked')
        if valid:
            self.smokes.add(match[1])
        else:
            self.errors.append('Failed or malformed startup smoke: ' + line.strip())

    @property
    def ready(self) -> bool:
        return self.done and len(self.smokes) == 2 and not self.errors


def terminate_tree(process: subprocess.Popen) -> None:
    """Stop a timed-out process together with its resource-reader descendants."""
    if process.poll() is not None:
        return
    try:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, check=False,
                           timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            os.killpg(process.pid, signal.SIGKILL)
    except (OSError, subprocess.TimeoutExpired):
        pass
    if process.poll() is None:
        process.kill()
    process.wait(timeout=10)


def passed(result: dict) -> bool:
    return (result['ready'] and result['serverExit'] == 0 and not result['errors']
            and not result['forcedServerStop']
            and result['completionReason'] in ('client-finished', 'manual-finished')
            and (result['completionReason'] == 'manual-finished' or result['clientExit'] == 0)
            and isinstance(result['client'], dict) and result['client'].get('passed') is True)


def run_session(instance: Path, out: Path, java: str, node: str, manual_client: bool = False,
                timeout: float | None = None, *, phase: str | None = None,
                state_file: Path | None = None, scenario: str = 'legacy',
                expected_dependency_hashes: dict[str, str] | None = None) -> dict:
    if phase not in (None, 'core', 'seed', 'verify', 'empty') or (phase is None) != (state_file is None):
        raise ValueError('Restart phases require an explicit shared state file')
    if scenario not in ('legacy', 'gameplay', 'sourcecrops') or (scenario != 'legacy' and phase is None):
        raise ValueError('Gameplay and sourcecrops require an explicit restart phase')
    if phase == 'core' and scenario != 'sourcecrops':
        raise ValueError('The core phase belongs only to sourcecrops')
    inbox = out / 'commands.txt'
    inbox.touch()
    log = out / 'console.log'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    process_options = {'creationflags': flags, 'start_new_session': os.name != 'nt'}
    jfr = os.path.relpath(out / 'startup.jfr', instance)
    command = [java, '-Xms512M', '-Xmx2200M', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
               '-Dstderr.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
               f'-XX:StartFlightRecording=filename={jfr},settings=profile,dumponexit=true', '-jar', 'server.jar', 'nogui']
    if scenario == 'sourcecrops':
        offset = command.index('-jar')
        command[offset:offset] = [f'-Dsenluo.sourcecrops.run_id={out.parent.name}', f'-Dsenluo.sourcecrops.phase={phase}']
    server = client = None
    startup = Startup()
    announced = False
    completion = 'runner-error'
    errors = []
    forced_stop = False
    dependency_hashes = None
    resources = CoreResources(instance, out) if scenario == 'sourcecrops' and phase == 'core' else None

    with log.open('wb') as stdout, log.open('rb') as tail, inbox.open(encoding='utf8') as commands:
        try:
            if resources is not None:
                resources.sample(required=True)
            server = subprocess.Popen(command, cwd=instance, stdin=subprocess.PIPE, stdout=stdout,
                                      stderr=subprocess.STDOUT, **process_options)
            end = time.monotonic() + session_budget(scenario, phase, manual_client, timeout)
            while True:
                if resources is not None:
                    resources.sample()
                if server.poll() is not None:
                    completion = 'server-exited'
                    break
                if time.monotonic() >= end:
                    completion = 'timeout'
                    break
                for stream, consume in ((tail, 'log'), (commands, 'cmd')):
                    while True:
                        pos = stream.tell()
                        line = stream.readline()
                        if not line or not line.endswith(b'\n' if consume == 'log' else '\n'):
                            stream.seek(pos)
                            break
                        if consume == 'log':
                            line = line.decode('utf8', 'replace')
                            match = re.search(r'CONNECTED_REPLY (\d+) (\{.*\})', line)
                            if match:
                                dest = out / f'reply-{match[1]}.json'
                                tmp = dest.with_suffix('.tmp')
                                tmp.write_text(match[2], 'utf8')
                                tmp.replace(dest)
                                if resources is not None:
                                    resources.observe_reply(json.loads(match[2]))
                            if resources is not None:
                                resources.observe_log(line)
                            startup.feed(line)
                            if startup.ready and not announced:
                                # Paper may create remapped/version/library JARs during startup. Capture
                                # the execution set only after readiness, before starting either player.
                                dependency_hashes = runtime_dependency_hashes(instance)
                                if expected_dependency_hashes is not None and dependency_hashes != expected_dependency_hashes:
                                    completion = 'startup-failed'
                                    raise ValueError('Ready-time runtime dependency JARs differ from the seed-ready baseline')
                                announced = True
                                print('Server ready', instance.name, flush=True)
                                if not manual_client:
                                    client_env = dict(os.environ, SENLUO_JAVA=java)
                                    for key in ('SENLUO_SCENARIO_PHASE', 'SENLUO_SCENARIO_STATE', 'SENLUO_SCENARIO_KIND'):
                                        client_env.pop(key, None)
                                    if phase is not None:
                                        client_env.update(SENLUO_SCENARIO_PHASE=phase,
                                                          SENLUO_SCENARIO_STATE=str(state_file.resolve()))
                                        client_env['SENLUO_SCENARIO_KIND'] = scenario
                                    client = subprocess.Popen([node, str(Path(__file__).with_name('players.cjs')), str(out)],
                                                              env=client_env, **process_options)
                        elif allowed_probe(line.strip(), scenario, phase):
                            if line.strip() in CROP_NATIVE_COMMANDS:
                                resources.command(line.strip())
                            server.stdin.write(line.encode('ascii'))
                            server.stdin.flush()
                if startup.errors:
                    completion = 'startup-failed'
                    break
                if client is not None and client.poll() is not None:
                    completion = 'client-finished'
                    break
                if manual_client and (out / 'finished').exists():
                    completion = 'manual-finished'
                    break
                time.sleep(.05)
        except KeyboardInterrupt:
            completion = 'cancelled'
        except (OSError, ValueError, BadZipFile, subprocess.SubprocessError) as error:
            errors.append(f'Acceptance runner: {type(error).__name__}: {error}')
        finally:
            if client is not None and client.poll() is None:
                try:
                    terminate_tree(client)
                except (OSError, subprocess.SubprocessError) as error:
                    errors.append(f'Cannot terminate client process tree: {error}')
            if server is not None and server.poll() is None:
                try:
                    server.stdin.write(b'stop\n')
                    server.stdin.flush()
                except (BrokenPipeError, OSError):
                    pass
                try:
                    server.wait(60)
                except subprocess.TimeoutExpired:
                    forced_stop = True
                    try:
                        terminate_tree(server)
                    except (OSError, subprocess.SubprocessError) as error:
                        errors.append(f'Cannot terminate server process tree: {error}')
                except (OSError, subprocess.SubprocessError) as error:
                    errors.append(f'Cannot wait for server shutdown: {error}')
            if server is not None and server.stdin is not None:
                server.stdin.close()
    errors.extend(startup.errors)
    errors.extend(line for line in log.read_text('utf8', errors='replace').splitlines() if LOG_ERROR.search(line))
    if dependency_hashes is not None:
        try:
            if runtime_dependency_hashes(instance) != dependency_hashes:
                errors.append('Runtime dependency JARs changed after server readiness')
        except OSError as error:
            errors.append(f'Cannot verify runtime dependency JARs after shutdown: {error}')
    if resources is not None:
        try:
            resources.verify()
        except (OSError, ValueError, BadZipFile) as error:
            errors.append(f'Core resource lifecycle: {error}')
    reports = out / 'players.json'
    client_report = None
    if reports.exists():
        try:
            client_report = json.loads(reports.read_text('utf8'))
        except (OSError, ValueError) as error:
            errors.append(f'Cannot read player report: {error}')
    if resources is not None and isinstance(client_report, dict):
        try:
            validate_core_client_resources(out, client_report, resources)
        except (OSError, ValueError, BadZipFile, KeyError, TypeError) as error:
            errors.append(f'Core downloaded resource evidence: {error}')
    expected_phase = phase or 'single'
    if not isinstance(client_report, dict) or client_report.get('phase') != expected_phase:
        label = 'restart phase' if phase is not None else 'phase'
        errors.append(f'Client report did not complete the requested {label}: {expected_phase}')
    if not isinstance(client_report, dict) or client_report.get('scenario') != scenario:
        errors.append(f'Client report did not complete the requested {scenario} scenario')
    result = {'ready': startup.ready, 'serverExit': None if server is None else server.returncode,
              'clientExit': None if client is None else client.returncode, 'completionReason': completion,
              'timedOut': completion == 'timeout', 'forcedServerStop': forced_stop,
              'errors': errors, 'client': client_report, 'runtimeDependencyHashes': dependency_hashes}
    if phase is not None:
        result['phase'] = phase
        result['scenario'] = scenario
    if scenario == 'sourcecrops' and phase == 'core':
        result['resourcePackHistory'] = resources.observations
        result['authorizedReloads'] = resources.reloads
        result['authorizedWorkflows'] = resources.workflows
    result['passed'] = bool(passed(result))
    (out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), 'utf8')
    return result


def file_sha256(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def runtime_hashes(instance: Path) -> dict[str, str]:
    """Keep the tested binaries and downloaded resources identical across all starts."""
    paths = sorted((instance / 'plugins').glob('*.jar'))
    paths += [instance / 'server.jar', instance / 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip']
    return {str(path.relative_to(instance)).replace('\\', '/'): file_sha256(path)
            for path in paths}


def runtime_dependency_hashes(instance: Path) -> dict[str, str]:
    """Hash JAR execution inputs, including ready-time Paper remaps and CE libraries, not mutable data caches."""
    return {path.relative_to(instance).as_posix(): file_sha256(path)
            for path in sorted(instance.rglob('*.jar')) if path.is_file()}


def scenario_hashes(scenario: str = 'legacy') -> dict[str, str]:
    folder = Path(__file__).resolve().parent
    if scenario == 'sourcecrops':
        return {name: file_sha256(folder / name) for name in
                ('run.py', 'players.cjs', 'source-crops.cjs', '../VanillaPack12111.java')}
    if scenario not in ('legacy', 'gameplay'):
        raise ValueError('Unknown scenario source lock')
    return {name: file_sha256(folder / name)
            for name in ('run.py', 'players.cjs', 'persistence-players.cjs', 'gameplay-persistence.cjs',
                         '../VanillaPack12111.java')}


def run_restart_suite(instance: Path, out: Path, java: str, node: str, fixture: dict, *, scenario: str = 'legacy') -> dict:
    """Reuse one world for three normal server starts, stopping on the first failure."""
    if scenario not in ('legacy', 'gameplay', 'sourcecrops'):
        raise ValueError('Unknown restart scenario')
    state_file = out / 'persistence-state.json'
    verified_file = state_file.with_name(state_file.name + '.verified.json')
    if state_file.exists():
        raise FileExistsError('A restart suite must start without a previous state file')
    if scenario in ('gameplay', 'sourcecrops') and verified_file.exists():
        raise FileExistsError('A gameplay suite must start without a previous verification snapshot')
    result = {'passed': False, 'scenario': scenario, 'phases': [], 'errors': [], 'runtimeHashes': runtime_hashes(instance),
              'scenarioHashes': scenario_hashes(scenario) if scenario == 'sourcecrops' else scenario_hashes()}
    state_hash = None
    verified_hash = None
    dependency_hashes = None
    for phase in ('seed', 'verify', 'empty'):
        try:
            if runtime_hashes(instance) != result['runtimeHashes']:
                raise ValueError('Server binaries or hosted resources changed between restart phases')
            if (scenario_hashes(scenario) if scenario == 'sourcecrops' else scenario_hashes()) != result['scenarioHashes']:
                raise ValueError('Acceptance scenario code changed between restart phases')
            if dependency_hashes is not None and runtime_dependency_hashes(instance) != dependency_hashes:
                raise ValueError('Runtime dependency JARs changed between restart phases')
            if state_hash is not None and hashlib.sha256(state_file.read_bytes()).hexdigest() != state_hash:
                raise ValueError('The seed snapshot changed between restart phases')
            if verified_hash is not None and hashlib.sha256(verified_file.read_bytes()).hexdigest() != verified_hash:
                raise ValueError('The verified inventory snapshot changed between restart phases')
            if scenario in ('gameplay', 'sourcecrops') and phase == 'verify' and verified_file.exists():
                raise ValueError('The verification snapshot already exists before recovery')
            folder = out / phase
            folder.mkdir(exist_ok=False)
            (folder / 'fixture.json').write_text(json.dumps(fixture), 'utf8')
            options = {'scenario': scenario} if scenario != 'legacy' else {}
            if dependency_hashes is not None:
                options['expected_dependency_hashes'] = dependency_hashes
            session = run_session(instance, folder, java, node, phase=phase, state_file=state_file, **options)
            result['phases'].append(session)
            if session.get('passed') is not True or session.get('phase') != phase:
                raise ValueError(f'Restart phase failed: {phase}')
            if session.get('scenario') != scenario:
                raise ValueError(f'Restart phase used a different {scenario} scenario')
            observed_dependencies = session.get('runtimeDependencyHashes')
            if not isinstance(observed_dependencies, dict) or not observed_dependencies:
                raise ValueError(f'Restart phase did not record ready-time runtime dependency JARs: {phase}')
            if dependency_hashes is None:
                dependency_hashes = observed_dependencies
                result['runtimeDependencyHashes'] = dependency_hashes
                result['runtimeDependencyBaseline'] = 'seed-ready'
            elif observed_dependencies != dependency_hashes:
                raise ValueError(f'The {phase} ready-time dependency JARs differ from the seed-ready baseline')
            if runtime_dependency_hashes(instance) != dependency_hashes:
                raise ValueError(f'Runtime dependency JARs changed during the {phase} restart phase')
            if phase == 'seed':
                state = json.loads(state_file.read_text('utf8'))
                if not isinstance(state, dict) or not state:
                    raise ValueError('The seed phase did not write a nonempty persistence snapshot')
                if scenario in ('gameplay', 'sourcecrops') and state.get('scenario') != scenario:
                    raise ValueError('The seed snapshot belongs to a different gameplay scenario')
                if scenario in ('gameplay', 'sourcecrops') and verified_file.exists():
                    raise ValueError('The seed phase wrote a premature verification snapshot')
                state_hash = hashlib.sha256(state_file.read_bytes()).hexdigest()
                result['seedStateSha256'] = state_hash
            elif hashlib.sha256(state_file.read_bytes()).hexdigest() != state_hash:
                raise ValueError(f'The {phase} phase changed the original seed snapshot')
            if scenario in ('gameplay', 'sourcecrops') and phase == 'verify':
                verified = json.loads(verified_file.read_text('utf8'))
                if (not isinstance(verified, dict) or verified.get('schemaVersion') != 1
                        or verified.get('scenario') != scenario or verified.get('phase') != phase
                        or verified.get('seedStateSha256') != state_hash):
                    raise ValueError('The verification snapshot does not match the immutable gameplay seed')
                verified_hash = hashlib.sha256(verified_file.read_bytes()).hexdigest()
                result['verifiedStateSha256'] = verified_hash
            elif verified_hash is not None and hashlib.sha256(verified_file.read_bytes()).hexdigest() != verified_hash:
                raise ValueError('The empty phase changed the verified inventory snapshot')
            if runtime_hashes(instance) != result['runtimeHashes']:
                raise ValueError('Server binaries or hosted resources changed during a restart phase')
            if (scenario_hashes(scenario) if scenario == 'sourcecrops' else scenario_hashes()) != result['scenarioHashes']:
                raise ValueError('Acceptance scenario code changed during a restart phase')
        except (OSError, ValueError) as error:
            result['errors'].append(str(error))
            break
    result['passed'] = len(result['phases']) == 3 and not result['errors']
    (out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), 'utf8')
    return result


def run_source_crops_core(instance: Path, out: Path, java: str, node: str, fixture: dict) -> dict:
    """Keep mutable resource workflows separate from the immutable restart baseline."""
    initial_runtime, sources = runtime_hashes(instance), scenario_hashes('sourcecrops')
    folder = out / 'core'
    folder.mkdir(exist_ok=False)
    (folder / 'fixture.json').write_text(json.dumps(fixture), 'utf8')
    result = run_session(instance, folder, java, node, phase='core', state_file=out / 'core-state.json', scenario='sourcecrops')
    final_runtime = runtime_hashes(instance)
    pack = 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip'
    if ({key: value for key, value in initial_runtime.items() if key != pack}
            != {key: value for key, value in final_runtime.items() if key != pack}):
        result['errors'].append('Core changed server or plugin binaries')
    if scenario_hashes('sourcecrops') != sources:
        result['errors'].append('Core acceptance scenario source changed')
    result.update(runtimeHashesBefore=initial_runtime, runtimeHashes=final_runtime, scenarioHashes=sources)
    result['passed'] = bool(result['passed'] and not result['errors'])
    (out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), 'utf8')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--java', required=True)
    parser.add_argument('--node', default='node')
    parser.add_argument('--manual-client', action='store_true', help='Run the JS scenario separately for diagnosis')
    parser.add_argument('--restart-suite', action='store_true',
                        help='Seed, restart and recover, then restart again to check for duplicate furniture or contents')
    parser.add_argument('--source-crops-core', action='store_true',
                        help='Run the separate crop core and genuine CE workflows before freezing the three-start baseline')
    parser.add_argument('--scenario', choices=('legacy', 'gameplay', 'sourcecrops'), default='legacy',
                        help='Select the verified fixture family for the restart suite')
    args = parser.parse_args()
    if args.restart_suite and args.manual_client:
        parser.error('--restart-suite requires the automatic protocol clients')
    if args.source_crops_core and (args.scenario != 'sourcecrops' or args.restart_suite or args.manual_client):
        parser.error('--source-crops-core requires --scenario sourcecrops and cannot be combined with --restart-suite/--manual-client')
    if args.scenario != 'legacy' and not (args.restart_suite or args.source_crops_core):
        parser.error('--scenario gameplay/sourcecrops requires --restart-suite')
    instance = args.instance.resolve()
    if not instance.is_relative_to(ROOT / 'build/acceptance'):
        raise ValueError('Expected a disposable copy under build/acceptance')
    props = dict(line.split('=', 1) for line in (instance / 'server.properties').read_text('utf8').splitlines() if '=' in line and not line.startswith('#'))
    if not all(props.get(k) == v for k, v in {'server-ip': '127.0.0.1', 'online-mode': 'false', 'level-name': 'acceptance-world'}.items()):
        raise ValueError('Expected the offline, loopback-only acceptance world')
    if 'eula=true' not in (instance / 'eula.txt').read_text('utf8').splitlines():
        raise ValueError('The server operator must have accepted the EULA')
    out = args.output.resolve()
    if not out.is_relative_to(ROOT / 'build/acceptance'):
        raise ValueError('Expected an output directory under build/acceptance')
    pack = instance / 'plugins/CraftEngine/cache/hosted/default/resource_pack.zip'
    with pack.open('rb') as source:
        pack_hash = hashlib.file_digest(source, 'sha256').hexdigest()
    info = {'port': int(props['server-port']), 'packSha256': pack_hash}
    out.mkdir(parents=True, exist_ok=False)
    (out / 'fixture.json').write_text(json.dumps(info), 'utf8')
    result = (run_source_crops_core(instance, out, args.java, args.node, info) if args.source_crops_core else
              run_restart_suite(instance, out, args.java, args.node, info, scenario=args.scenario) if args.restart_suite
              else run_session(instance, out, args.java, args.node, args.manual_client))
    print(f"{instance.name}: {'PASS' if result['passed'] else 'FAIL'}; {out / 'result.json'}")
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
