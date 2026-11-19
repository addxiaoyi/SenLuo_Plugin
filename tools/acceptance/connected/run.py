"""Run protocol acceptance against a disposable, already prepared loopback Paper/Folia instance."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROBE = re.compile(r'senluoprobe ([0-9]{1,8}) (arrive|supply|scarecrow|inspect) SenLuoProbe[AB]$')


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
    inbox = out / 'commands.txt'
    inbox.touch()
    log = out / 'console.log'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    jfr = os.path.relpath(out / 'startup.jfr', instance)
    command = [args.java, '-Xms512M', '-Xmx2200M', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
               '-Dstderr.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
               f'-XX:StartFlightRecording=filename={jfr},settings=profile,dumponexit=true', '-jar', 'server.jar', 'nogui']
    client = None
    ready = False
    with log.open('wb') as stdout, log.open('rb') as tail, inbox.open(encoding='utf8') as commands:
        server = subprocess.Popen(command, cwd=instance, stdin=subprocess.PIPE, stdout=stdout, stderr=subprocess.STDOUT, creationflags=flags)
        try:
            end = time.monotonic() + (1200 if args.manual_client else 400)
            while server.poll() is None and time.monotonic() < end:
                for stream, consume in ((tail, 'log'), (commands, 'cmd')):
                    while True:
                        pos = stream.tell()
                        line = stream.readline()
                        if not line or not line.endswith(b'\n' if consume == 'log' else '\n'):
                            stream.seek(pos); break
                        if consume == 'log':
                            line = line.decode('utf8', 'replace')
                            match = re.search(r'CONNECTED_REPLY (\d+) (\{.*\})', line)
                            if match:
                                dest = out / f'reply-{match[1]}.json'
                                tmp = dest.with_suffix('.tmp'); tmp.write_text(match[2], 'utf8'); tmp.replace(dest)
                            if not ready and 'COOKERY_ENCHANTMENT_SMOKE' in line:
                                ready = True
                                print('Server ready', instance.name, flush=True)
                                if not args.manual_client:
                                    env = dict(os.environ, SENLUO_JAVA=args.java)
                                    client = subprocess.Popen([args.node, str(Path(__file__).with_name('players.cjs')), str(out)],
                                                              env=env, creationflags=flags)
                        elif PROBE.fullmatch(line.strip()):
                            server.stdin.write(line.encode('ascii')); server.stdin.flush()
                if (client and client.poll() is not None) or (args.manual_client and (out / 'finished').exists()): break
                time.sleep(.05)
        finally:
            if client and client.poll() is None:
                client.kill(); client.wait()
            if server.poll() is None:
                try:
                    server.stdin.write(b'stop\n')
                    server.stdin.flush()
                except BrokenPipeError:
                    pass
                try:
                    server.wait(70)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
    console = log.read_text('utf8', errors='replace')
    errors = [s for s in console.splitlines() if ' ERROR]:' in s or '/ERROR]' in s or 'has not responded in' in s]
    reports = out / 'players.json'
    result = {'ready': ready, 'serverExit': server.returncode, 'errors': errors, 'client': json.loads(reports.read_text('utf8')) if reports.exists() else None}
    (out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), 'utf8')
    passed = (ready and server.returncode == 0 and not errors and result['client']
              and result['client'].get('passed') and (not client or client.returncode == 0))
    print(f"{instance.name}: {'PASS' if passed else 'FAIL'}; {out / 'result.json'}")
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
