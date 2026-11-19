"""Run protocol acceptance against a disposable, already prepared loopback Paper/Folia instance."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROBE = re.compile(r'senluoprobe ([0-9]{1,8}) (arrive|supply|scarecrow|tulip|clearboard|sofa|clearsofa|inspectsofa|doll|cleardoll|inspectdoll|inspect) SenLuoProbe[AB]$')
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
                timeout: float | None = None) -> dict:
    inbox = out / 'commands.txt'
    inbox.touch()
    log = out / 'console.log'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    process_options = {'creationflags': flags, 'start_new_session': os.name != 'nt'}
    jfr = os.path.relpath(out / 'startup.jfr', instance)
    command = [java, '-Xms512M', '-Xmx2200M', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
               '-Dstderr.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
               f'-XX:StartFlightRecording=filename={jfr},settings=profile,dumponexit=true', '-jar', 'server.jar', 'nogui']
    server = client = None
    startup = Startup()
    announced = False
    completion = 'runner-error'
    errors = []
    forced_stop = False
    with log.open('wb') as stdout, log.open('rb') as tail, inbox.open(encoding='utf8') as commands:
        try:
            server = subprocess.Popen(command, cwd=instance, stdin=subprocess.PIPE, stdout=stdout,
                                      stderr=subprocess.STDOUT, **process_options)
            end = time.monotonic() + (timeout if timeout is not None else 1200 if manual_client else 400)
            while True:
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
                            startup.feed(line)
                            if startup.ready and not announced:
                                announced = True
                                print('Server ready', instance.name, flush=True)
                                if not manual_client:
                                    client = subprocess.Popen([node, str(Path(__file__).with_name('players.cjs')), str(out)],
                                                              env=dict(os.environ, SENLUO_JAVA=java), **process_options)
                        elif PROBE.fullmatch(line.strip()):
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
        except (OSError, ValueError, subprocess.SubprocessError) as error:
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
    reports = out / 'players.json'
    client_report = None
    if reports.exists():
        try:
            client_report = json.loads(reports.read_text('utf8'))
        except (OSError, ValueError) as error:
            errors.append(f'Cannot read player report: {error}')
    result = {'ready': startup.ready, 'serverExit': None if server is None else server.returncode,
              'clientExit': None if client is None else client.returncode, 'completionReason': completion,
              'timedOut': completion == 'timeout', 'forcedServerStop': forced_stop,
              'errors': errors, 'client': client_report}
    result['passed'] = bool(passed(result))
    (out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), 'utf8')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--java', required=True)
    parser.add_argument('--node', default='node')
    parser.add_argument('--manual-client', action='store_true', help='Run the JS scenario separately for diagnosis')
    args = parser.parse_args()
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
    result = run_session(instance, out, args.java, args.node, args.manual_client)
    print(f"{instance.name}: {'PASS' if result['passed'] else 'FAIL'}; {out / 'result.json'}")
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
