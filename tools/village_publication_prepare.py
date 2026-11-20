"""Assemble a fresh village rollback fixture and freeze it after manual EULA acceptance.

Run seed-manifest on a known local runtime, prepare with three explicit plugin
JARs and the two export ZIPs, then run handoff after placing an operator-accepted
eula.txt in the new instance. None of these commands downloads or launches anything.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import uuid
from zipfile import BadZipFile

import yaml

import village_publication_candidate as candidate
import village_publication_launch as launch
from village_publication_report import MAX_REPORT_BYTES, decode_json, require

SEED_SCHEMA = 'village-publication-seed-v1'
ASSEMBLY_SCHEMA = 'village-publication-assembly-v1'
NATIVE = {
    'Paper': ('versions/1.21.11/paper-1.21.11.jar', '3a73f0cc4020a5eb45629a67629b14263331c68de45552f8505df8060fa8ed43'),
    'Folia': ('versions/1.21.11/folia-1.21.11.jar', '6bfd94ffd8649cf416d247e849214667ac91759699a4c3536eef2307277c4e33'),
}
LAUNCHER_SHA256 = {
    'Paper': '5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba',
    'Folia': 'f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39',
}
# The pinned launchers use this local Paperclip input. Do not arrange a download.
MOJANG_SHA256 = 'f83b8e093865806f931c7e34aae41b177d4c076335263dd124c75d6d65dd1726'
CE_SHA256 = '021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f'
CE_CONFIG = 'plugins/CraftEngine/config.yml'
PROBE_CONFIG = 'plugins/CookeryAcceptanceProbe/config.yml'
KITCHEN = 'frozen-inputs/kitchen.zip'
VILLAGE = 'acceptance-world/datapacks/village.zip'
PLUGINS = {'production': 'plugins/KaleidoscopeCookeryPlugin.jar', 'craftEngine': 'plugins/craftengine-26.9.1.jar',
           'probe': 'plugins/CookeryAcceptanceProbe.jar'}
PLUGIN_NAMES = {'production': 'KaleidoscopeCookeryPlugin', 'craftEngine': 'CraftEngine', 'probe': 'CookeryAcceptanceProbe'}
SEED_FILES = ('bukkit.yml', 'spigot.yml', 'config/paper-global.yml', 'config/paper-world-defaults.yml',
              'plugins/CraftEngine/commands.yml', CE_CONFIG)
RESOURCES = 'plugins/CraftEngine/resources/'
SEED_TREES = ('plugins/CraftEngine/libs', 'plugins/CraftEngine/translations', *(RESOURCES + name for name in
              ('default_assets', 'default_feature_populator', 'default_templates', 'internal', 'legacy_armor')))
MEMBER_PART = re.compile(r'[A-Za-z0-9_.+-]+')
SHA256 = re.compile(r'[0-9a-f]{64}')
RESERVED = {'con', 'prn', 'aux', 'nul', *(f'{kind}{number}' for kind in ('com', 'lpt') for number in range(1, 10))}


def new_output(path: Path) -> Path:
    path = candidate.unlinked_path(path)
    root = candidate.unlinked_path(candidate.ACCEPTANCE)
    require(path != root and path.is_relative_to(root), 'Output must be separate under this checkout/build/acceptance')
    require(not candidate.fs_path(path).exists(), 'Output already exists: ' + str(path))
    return path


def separate(first: Path, second: Path) -> None:
    require(not first.is_relative_to(second) and not second.is_relative_to(first), 'Input and output paths must be separate')


def read_record(path: Path) -> dict:
    with candidate.fs_path(candidate.unlinked_path(path)).open('rb') as stream:
        return decode_json(stream.read(MAX_REPORT_BYTES + 1))


def write_record(path: Path, value: dict) -> None:
    raw = (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
    require(len(raw) <= MAX_REPORT_BYTES, 'Preparation record exceeds its byte bound')
    target = candidate.fs_path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    identity = None
    try:
        with target.open('xb') as stream:
            identity = os.fstat(stream.fileno())
            stream.write(raw)
    except BaseException:
        if identity is not None:
            try:
                current = target.lstat()
                if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                    target.unlink()
            except FileNotFoundError:
                pass
        raise


def safe_member(name: str) -> tuple[str, ...]:
    parts = name.split('/')
    require(all(MEMBER_PART.fullmatch(part) and part not in ('.', '..') and not part.endswith('.')
                and part.split('.', 1)[0].casefold() not in RESERVED for part in parts),
            'Unsupported archive or launcher path: ' + name)
    return tuple(parts)


def bounded_member(archive, name: str, limit: int) -> bytes:
    entries = [entry for entry in archive.infolist() if entry.filename == name]
    require(len(entries) == 1 and 0 <= entries[0].file_size <= limit, 'Missing or oversized ZIP member: ' + name)
    with archive.open(entries[0]) as stream:
        raw = stream.read(limit + 1)
    require(len(raw) == entries[0].file_size, 'ZIP member length changed: ' + name)
    return raw


def launcher_inventory(seed: Path, platform: str) -> dict[str, str]:
    required_native, native_hash = NATIVE[platform]
    launcher = candidate.contained(seed, 'server.jar')
    result = {}
    folded = set()
    with candidate.bounded_zip(launcher) as archive:
        for folder in ('versions', 'libraries'):
            rows = bounded_member(archive, f'META-INF/{folder}.list', 262_144).decode('utf-8').splitlines()
            require(0 < len(rows) <= 2_000, 'Unexpected launcher dependency manifest')
            for row in rows:
                fields = row.split('\t')
                require(len(fields) == 3 and SHA256.fullmatch(fields[0]) and fields[1], 'Malformed launcher dependency row')
                relative = '/'.join((folder, *safe_member(fields[2])))
                require(relative.endswith('.jar') and relative.casefold() not in folded, 'Duplicate or invalid launcher dependency')
                result[relative] = fields[0]
                folded.add(relative.casefold())
    require({path: digest for path, digest in result.items() if path.startswith('versions/')} == {required_native: native_hash},
            'Server launcher does not declare the pinned native build for ' + platform)
    return result


def seed_manifest(seed: Path, platform: str) -> dict:
    seed = candidate.unlinked_path(seed)
    require(platform in NATIVE and candidate.fs_path(seed).is_dir(), 'Supply a local runtime seed and a supported platform')
    launcher = candidate.fingerprint(candidate.contained(seed, 'server.jar'))
    require(launcher['sha256'] == LAUNCHER_SHA256[platform], 'Server launcher is not the pinned build for ' + platform)
    dependencies = launcher_inventory(seed, platform)
    files = ['server.jar', candidate.PAPERCLIP_CLIENT, *dependencies]
    for name in SEED_FILES:
        if candidate.fs_path(candidate.contained(seed, name)).exists():
            files.append(name)
    require(CE_CONFIG in files, 'A prepared local CraftEngine config is required')
    for name in SEED_TREES:
        tree = candidate.contained(seed, name)
        if candidate.fs_path(tree).exists():
            require(candidate.fs_path(tree).is_dir(), 'Expected a seed directory: ' + name)
            for path in candidate.walk_files(tree):
                files.append(path.relative_to(seed).as_posix())
                require(len(files) <= candidate.MAX_FILES, 'Seed file inventory exceeds its bound')
    inventory = candidate.inventory(seed, files)
    require(inventory['server.jar'] == launcher and inventory[candidate.PAPERCLIP_CLIENT]['sha256'] == MOJANG_SHA256
            and all(inventory[path]['sha256'] == expected for path, expected in dependencies.items()),
            'Local launcher, Paperclip input or runtime dependency differs from the pinned build')
    config_path = candidate.contained(seed, CE_CONFIG)
    config = candidate.configuration(config_path, engine=True)
    require(candidate.fingerprint(config_path) == inventory[CE_CONFIG], 'CraftEngine seed config changed during inspection')
    generation = config.get('chunk-system', {}).get('generation', {})
    storage, pack = config.get('storage', {}), config.get('resource-pack', {})
    require(config.get('misc', {}).get('delay-configuration-load') is False and generation.get('feature') is True,
            'Seed must already enable non-delayed CraftEngine configuration and FEATURE generation')
    require(storage.get('type') == 'sqlite' and storage.get('url') == 'jdbc:sqlite:./plugins/CraftEngine/data.db'
            and pack.get('self-host', {}).get('ip') == '127.0.0.1'
            and pack.get('packs', {}).get('default', {}).get('type') == 'self',
            'Seed requires local CE SQLite and loopback self-hosted default resource pack')
    return {'schema': SEED_SCHEMA, 'platform': platform, 'seed': str(seed), 'files': inventory}


def copy_checked(source: Path, target: Path, expected: dict) -> None:
    source = candidate.unlinked_path(source)
    require(candidate.fingerprint(source) == expected, 'Source changed before copy: ' + str(source))
    candidate.fs_path(target.parent).mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    size = 0
    with candidate.fs_path(source).open('rb') as src, candidate.fs_path(target).open('xb') as dst:
        while block := src.read(65_536):
            size += len(block)
            require(size <= expected['bytes'], 'Source grew during copy: ' + str(source))
            dst.write(block)
            digest.update(block)
    require({'bytes': size, 'sha256': digest.hexdigest()} == expected, 'Source changed during copy: ' + str(source))


def check_plugins(sources: dict[str, Path]) -> dict[str, dict]:
    require(set(sources) == set(PLUGINS) and len(set(sources.values())) == 3, 'Supply three distinct plugin JARs')
    result = {}
    for role, path in sources.items():
        require(path.suffix.lower() == '.jar', 'Expected a plugin JAR: ' + role)
        value = candidate.fingerprint(path)
        if role == 'craftEngine':
            require(value['sha256'] == CE_SHA256, 'CraftEngine JAR is not the pinned 26.9.1 build')
        with candidate.bounded_zip(path) as archive:
            descriptor = 'paper-plugin.yml' if role == 'craftEngine' else 'plugin.yml'
            name_lines = [line for line in bounded_member(archive, descriptor, 262_144).decode('utf-8').splitlines()
                          if line.startswith('name:')]
            require(name_lines == ['name: ' + PLUGIN_NAMES[role]], 'Unexpected plugin descriptor name: ' + role)
            mf = bounded_member(archive, 'META-INF/MANIFEST.MF', 262_144).decode('utf-8').splitlines()
            require([line for line in mf if line.lower().startswith('paperweight-mappings-namespace:')]
                    == ['paperweight-mappings-namespace: mojang'], 'Declared defining plugin JAR must use Mojang mappings: ' + role)
        result[role] = value
    return result


def install_kitchen(instance: Path) -> None:
    with candidate.bounded_zip(candidate.contained(instance, KITCHEN)) as archive:
        entries = archive.infolist()
        require(len({entry.filename for entry in entries}) == len(entries)
                and all(0 <= entry.file_size <= 16_777_216 for entry in entries)
                and sum(entry.file_size for entry in entries) <= 134_217_728, 'Invalid kitchen ZIP member set or size')
        selected = {}
        dirs = set()
        spelling = {}
        for entry in entries:
            parts = safe_member(entry.filename.removesuffix('/') if entry.is_dir() else entry.filename)
            kind = stat.S_IFMT(entry.external_attr >> 16)
            require(kind in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG), 'Linked or special kitchen ZIP entry')
            if parts[0] != 'cookery_kitchen':
                continue
            require(entry.is_dir() or len(parts) > 1, 'Kitchen ZIP root must be a directory')
            for index in range(1, len(parts) + 1):
                name = '/'.join(parts[:index])
                alias = name.casefold()
                require(spelling.setdefault(alias, name) == name, 'Case-colliding kitchen ZIP paths')
                if index < len(parts) or entry.is_dir():
                    dirs.add(alias)
            if not entry.is_dir():
                selected[entry.filename.casefold()] = entry
        require(selected and selected.keys().isdisjoint(dirs), 'Missing or conflicting kitchen ZIP paths')
        for entry in selected.values():
            target = candidate.contained(instance, RESOURCES + entry.filename)
            candidate.fs_path(target.parent).mkdir(parents=True, exist_ok=True)
            size = 0
            with archive.open(entry) as src, candidate.fs_path(target).open('xb') as dst:
                while block := src.read(65_536):
                    size += len(block)
                    require(size <= entry.file_size, 'Kitchen entry grew beyond its declared bound')
                    dst.write(block)
            require(size == entry.file_size, 'Kitchen entry length differs')


def assembled_inventory(instance: Path, *, eula: bool) -> dict:
    files = [path.relative_to(instance).as_posix() for path in candidate.walk_files(instance)]
    require(('eula.txt' in files) == eula, 'EULA must be supplied independently after preparation')
    return candidate.inventory(instance, [name for name in files if name != 'eula.txt'])


def prepare(seed_record: dict, instance: Path, receipt: Path, sources: dict[str, Path], kitchen: Path, village: Path,
            run_id: str, port: int) -> dict:
    require(type(port) is int and 1 <= port <= 65_535 and str(uuid.UUID(run_id)) == run_id,
            'Choose a valid loopback port and a canonical new run UUID')
    instance, receipt = new_output(instance), new_output(receipt)
    separate(instance, receipt)
    require(seed_record.get('schema') == SEED_SCHEMA, 'Expected a pinned local seed manifest')
    seed, platform = candidate.unlinked_path(Path(seed_record['seed'])), seed_record['platform']
    separate(instance, seed)
    separate(receipt, seed)
    require(seed_manifest(seed, platform) == seed_record, 'Local seed inventory drifted')
    sources = {role: candidate.unlinked_path(path) for role, path in sources.items()}
    kitchen, village = candidate.unlinked_path(kitchen), candidate.unlinked_path(village)
    for path in (*sources.values(), kitchen, village):
        separate(instance, path)
        separate(receipt, path)
    plugin_inputs = check_plugins(sources)
    packs = {name: (path, candidate.fingerprint(path)) for name, path in ((KITCHEN, kitchen), (VILLAGE, village))}
    target = candidate.fs_path(instance)
    target.parent.mkdir(parents=True, exist_ok=True)
    # Keep the logical path without the Windows extended prefix while creating
    # the staging directory; candidate.inspect_fresh compares logical roots.
    staging = candidate.plain_path(Path(tempfile.mkdtemp(
        prefix=f'.{instance.name}.prepare-', dir=target.parent)).resolve())
    target_identity = candidate.fs_path(staging).lstat()
    published = False
    try:
        for name, value in seed_record['files'].items():
            copy_checked(candidate.contained(seed, name), candidate.contained(staging, name), value)
        for role, name in PLUGINS.items():
            copy_checked(sources[role], candidate.contained(staging, name), plugin_inputs[role])
        for name, (path, value) in packs.items():
            copy_checked(path, candidate.contained(staging, name), value)
        install_kitchen(staging)
        properties = {**candidate.FIXTURE_PROPERTIES, 'server-port': str(port), 'online-mode': 'true',
                      'enable-query': 'false', 'management-server-enabled': 'false', 'max-players': '2',
                      'view-distance': '4', 'simulation-distance': '4', 'spawn-protection': '0',
                      'initial-enabled-packs': 'vanilla,file/village.zip', 'initial-disabled-packs': ''}
        with candidate.fs_path(candidate.contained(staging, 'server.properties')).open('x', encoding='utf-8') as stream:
            stream.writelines(f'{key}={value}\n' for key, value in properties.items())
        probe = candidate.contained(staging, PROBE_CONFIG)
        candidate.fs_path(probe.parent).mkdir(parents=True, exist_ok=True)
        with candidate.fs_path(probe).open('x', encoding='utf-8') as stream:
            stream.write(f'village-publication-smoke:\n  enabled: true\n  run-id: "{run_id}"\n')
        defining = {**PLUGINS, 'server': NATIVE[platform][0]}
        properties = candidate.inspect_fresh(staging, run_id)
        candidate.check_initial_pack_selection(properties, VILLAGE)
        candidate.check_packs(staging, KITCHEN, VILLAGE)
        candidate.check_production_provenance(staging, defining['production'], VILLAGE)
        record = {'schema': ASSEMBLY_SCHEMA, 'instance': str(instance), 'platform': platform, 'runId': run_id,
                  'files': assembled_inventory(staging, eula=False), 'startup': 'NOT_RUN'}

        require(not target.exists(), 'Output already exists: ' + str(instance))
        published = True
        os.rename(candidate.fs_path(staging), target)
        write_record(receipt, record)
        return record
    except BaseException:
        if published:
            try:
                current = target.lstat()
                if (current.st_dev, current.st_ino) == (target_identity.st_dev, target_identity.st_ino):
                    shutil.rmtree(target)
            except FileNotFoundError:
                pass
        staged = candidate.fs_path(staging)
        try:
            current = staged.lstat()
            if (current.st_dev, current.st_ino) == (target_identity.st_dev, target_identity.st_ino):
                shutil.rmtree(staged)
        except FileNotFoundError:
            pass
        raise


def handoff(assembly: dict, java: Path, output: Path, inherited=None) -> dict:
    output = new_output(output)
    require(set(assembly) == {'schema', 'instance', 'platform', 'runId', 'files', 'startup'}
            and assembly['schema'] == ASSEMBLY_SCHEMA and assembly['startup'] == 'NOT_RUN'
            and assembly['platform'] in NATIVE, 'Expected an offline village assembly receipt')
    instance = candidate.unlinked_path(Path(assembly['instance']))
    require(instance != candidate.ACCEPTANCE.resolve() and instance.is_relative_to(candidate.ACCEPTANCE.resolve()),
            'Assembly instance left build/acceptance')
    separate(output, instance)
    require(assembled_inventory(instance, eula=True) == assembly['files'], 'Assembled instance inputs drifted before handoff')
    eula = candidate.contained(instance, 'eula.txt')
    def read_eula():
        with candidate.fs_path(eula).open('rb') as stream:
            return stream.read(65_537)

    raw_eula = read_eula()
    require(len(raw_eula) <= 65_536 and [line for line in raw_eula.decode('utf-8').splitlines()
            if line.strip() and not line.lstrip().startswith(('#', '!'))] == ['eula=true'],
            'The operator must already have accepted the Minecraft EULA in this instance')
    platform = assembly['platform']
    defining = {**PLUGINS, 'server': NATIVE[platform][0]}
    frozen = candidate.freeze(instance, platform, assembly['runId'], defining, KITCHEN, VILLAGE, java, output)
    require(assembled_inventory(instance, eula=True) == assembly['files'] and read_eula() == raw_eula,
            'Assembled instance or EULA changed during handoff')
    record = launch.prepare(frozen, inherited)
    launch.write_new_record(record, output / 'launch.json', frozen)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    pin = commands.add_parser('seed-manifest', help='Fingerprint known local runtime inputs without modifying the seed')
    pin.add_argument('--seed', type=Path, required=True)
    pin.add_argument('--platform', choices=NATIVE, required=True)
    pin.add_argument('--output', type=Path, required=True)
    assemble = commands.add_parser('prepare', help='Create a fresh three-plugin rollback fixture without an EULA file')
    for name in ('seed-manifest', 'instance', 'receipt', 'cookery-jar', 'craft-engine-jar', 'probe-jar', 'kitchen-pack', 'village-pack'):
        assemble.add_argument('--' + name, type=Path, required=True)
    assemble.add_argument('--run-id', required=True)
    assemble.add_argument('--port', type=int, required=True)
    finish = commands.add_parser('handoff', help='Freeze the candidate and launch record after manual EULA acceptance')
    for name in ('receipt', 'java', 'output'):
        finish.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'seed-manifest':
            output, seed = new_output(args.output), candidate.unlinked_path(args.seed)
            separate(output, seed)
            result = seed_manifest(seed, args.platform)
            write_record(output, result)
        elif args.command == 'prepare':
            result = prepare(read_record(args.seed_manifest), args.instance, args.receipt,
                             {'production': args.cookery_jar, 'craftEngine': args.craft_engine_jar, 'probe': args.probe_jar},
                             args.kitchen_pack, args.village_pack, args.run_id, args.port)
        else:
            result = handoff(read_record(args.receipt), args.java, args.output)
        print(json.dumps({'platform': result['platform'], 'runId': result.get('runId'), 'startup': 'NOT_RUN'}))
        return 0
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError, BadZipFile, yaml.YAMLError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
