"""Freeze/check isolated village-smoke inputs without launching or changing a server."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import sys
import uuid
from zipfile import ZipFile, BadZipFile

import yaml

from cookery_village import COMMIT, PROCESSOR_PATH
from village_publication_report import (MAX_REPORT_BYTES, ReceiptSnapshot, check_current_receipt_snapshots, decode_json,
                                        load_receipt_snapshots, read_json, require, validate, _manifest, _time)

ROOT = Path(__file__).resolve().parents[1]
ACCEPTANCE = ROOT / 'build/acceptance'
MAX_FILE_BYTES = 536_870_912
MAX_TOTAL_BYTES = 2_147_483_648
MAX_FILES = 25_000
INPUT_TREES = ('libraries', 'versions', 'plugins/.paper-remapped', 'plugins/CraftEngine/libs',
               'plugins/CraftEngine/resources', 'plugins/CraftEngine/translations',
               'plugins/KaleidoscopeCookeryPlugin', 'config')
BASE_FILES = ('server.jar', 'server.properties', 'plugins/CraftEngine/config.yml',
              'plugins/CookeryAcceptanceProbe/config.yml')
# Paperclip keeps the vanilla client/runtime JAR at this stable path.  Include
# only this known dependency when present; the rest of cache/ is mutable state.
PAPERCLIP_CLIENT = 'cache/mojang_1.21.11.jar'
FIXTURE_PROPERTIES = {'server-ip': '127.0.0.1', 'enable-rcon': 'false', 'level-name': 'acceptance-world'}
PACK_PROPERTIES = {'initial-enabled-packs', 'initial-disabled-packs'}


def unlinked_path(path: Path) -> Path:
    absolute = plain_path(path).absolute()
    require(all(not fs_path(item).is_symlink() and not fs_path(item).is_junction() for item in (absolute, *absolute.parents)),
            'Linked input roots or ancestors are unsupported')
    return plain_path(fs_path(absolute).resolve())


def plain_path(path: Path) -> Path:
    value = str(path)
    if os.name == 'nt' and value.startswith('\\\\?\\UNC\\'):
        return Path('\\\\' + value[8:])
    if os.name == 'nt' and value.startswith('\\\\?\\'):
        return Path(value[4:])
    return path


def fs_path(path: Path) -> Path:
    if os.name != 'nt':
        return path
    value = str(path.absolute())
    if value.startswith('\\\\?\\'):
        return Path(value)
    if value.startswith('\\\\'):
        return Path('\\\\?\\UNC\\' + value[2:])
    return Path('\\\\?\\' + value)


def contained(instance: Path, relative: str) -> Path:
    instance = unlinked_path(instance)
    path = PurePosixPath(relative)
    require(not path.is_absolute() and bool(path.parts) and all(part not in ('..', '.') for part in path.parts)
            and '\\' not in relative and ':' not in relative, 'Input path must be a contained relative path')
    current = instance
    for part in path.parts:
        current /= part
        require(not fs_path(current).is_symlink() and not fs_path(current).is_junction(), 'Linked candidate inputs are unsupported')
    require(unlinked_path(current).is_relative_to(instance), 'Input escapes the isolated candidate')
    return current


def fingerprint(path: Path) -> dict:
    before = fs_path(path).stat()
    require(stat.S_ISREG(before.st_mode) and 0 <= before.st_size <= MAX_FILE_BYTES, 'Input file is missing or exceeds its bound: ' + str(path))
    digest = hashlib.sha256()
    total = 0
    with fs_path(path).open('rb') as stream:
        while block := stream.read(65_536):
            total += len(block)
            require(total <= MAX_FILE_BYTES, 'Input grew beyond its hash bound')
            digest.update(block)
    after = fs_path(path).stat()
    require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
            (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) and total == before.st_size,
            'Input changed during freezing: ' + str(path))
    return {'bytes': total, 'sha256': digest.hexdigest()}


def walk_files(directory: Path):
    pending = [directory]
    nodes = 0
    while pending:
        current = pending.pop()
        unlinked_path(current)
        for native_path in fs_path(current).iterdir():
            path = plain_path(native_path)
            nodes += 1
            require(nodes <= MAX_FILES * 2, 'Input tree traversal exceeds its node bound')
            unlinked_path(path)
            if fs_path(path).is_dir():
                pending.append(path)
            else:
                require(fs_path(path).is_file(), 'Unsupported non-regular input')
                yield path


@contextmanager
def bounded_zip(path: Path):
    before = fs_path(path).stat()
    require(stat.S_ISREG(before.st_mode) and 22 <= before.st_size <= MAX_FILE_BYTES, 'ZIP file exceeds its file bound')
    with fs_path(path).open('rb') as stream:
        stream.seek(max(0, before.st_size - 65_557))
        tail = stream.read(65_557)
    offset = tail.rfind(b'PK\x05\x06')
    require(offset >= 0 and len(tail) - offset >= 22, 'Missing bounded ZIP end record')
    _, disk, central_disk, on_disk, entries, size, start, comment = struct.unpack('<4s4H2LH', tail[offset:offset + 22])
    require(disk == central_disk == 0 and on_disk == entries and entries <= 10_000
            and size <= 2_097_152 and start + size <= before.st_size - 22
            and len(tail) - offset == 22 + comment, 'Unsupported ZIP64/multidisk or excessive central directory')
    with ZipFile(fs_path(path)) as archive:
        require(len(archive.infolist()) <= 10_000, 'ZIP contains too many actual entries')
        yield archive


def inventory(instance: Path, paths: list[str]) -> dict:
    result = {}
    total = 0
    for relative in sorted(set(paths)):
        path = contained(instance, relative)
        value = fingerprint(path)
        total += value['bytes']
        require(total <= MAX_TOTAL_BYTES and len(result) < MAX_FILES, 'Candidate inventory exceeds its bound')
        result[relative] = value
    return result


def input_paths(instance: Path, extra: list[str]) -> list[str]:
    instance = instance.resolve()
    paths = list(BASE_FILES) + list(extra)
    paths.extend(name for name in ('eula.txt', 'bukkit.yml', 'spigot.yml') if (instance / name).exists())
    if contained(instance, PAPERCLIP_CLIENT).exists():
        paths.append(PAPERCLIP_CLIENT)
    plugins = instance / 'plugins'
    require(plugins.is_dir(), 'Missing candidate plugins directory')
    paths.extend(path.relative_to(instance).as_posix() for path in plugins.glob('*.jar'))
    command_config = contained(instance, 'plugins/CraftEngine/commands.yml')
    if command_config.exists():
        paths.append(command_config.relative_to(instance).as_posix())
    for name in (*INPUT_TREES, 'acceptance-world/datapacks'):
        directory = contained(instance, name)
        if directory.exists():
            require(directory.is_dir(), 'Expected candidate input directory: ' + name)
            for path in walk_files(directory):
                paths.append(path.relative_to(instance).as_posix())
                require(len(paths) <= MAX_FILES * 2, 'Too many candidate input paths')
    return sorted(set(paths))


def java_inventory(executable: Path) -> dict:
    executable = unlinked_path(executable)
    require(executable.is_file(), 'Supply the actual Java executable')
    root = executable.parent.parent
    require((root / 'lib/modules').is_file(), 'Missing actual Java module image')
    paths = [executable]
    if (root / 'release').is_file():
        paths.append(root / 'release')
    for directory in ('bin', 'lib', 'conf'):
        base = root / directory
        if base.exists():
            for path in walk_files(base):
                paths.append(path)
                require(len(paths) <= MAX_FILES, 'Java runtime file inventory exceeds its bound')
    result = {}
    total = 0
    for path in sorted(set(paths)):
        value = fingerprint(path)
        total += value['bytes']
        require(total <= MAX_TOTAL_BYTES, 'Java runtime byte inventory exceeds its bound')
        result[str(path)] = value
    return result


def plugin_sources(instance: Path, defining: dict, sources: dict | None) -> dict:
    sources = dict(sources or {key: defining[key] for key in ('production', 'probe', 'craftEngine')})
    require(set(sources) == {'production', 'probe', 'craftEngine'}, 'Declare exactly three plugin source roles')
    files = []
    for relative in sources.values():
        require(type(relative) is str and relative.endswith('.jar'), 'Invalid source plugin path')
        path = contained(instance, relative)
        require(path.parent == instance / 'plugins', 'Source plugin JARs must be directly in plugins/')
        files.append(path)
    require(len(set(files)) == 3 and set(files) == set((instance / 'plugins').glob('*.jar')),
            'Rollback fixture accepts exactly the three declared source plugins; unexpected plugins are not certified')
    return sources


def mutable_inputs(instance: Path) -> dict:
    paths = ['plugins/CraftEngine/data.db']
    paths.extend('plugins/CraftEngine/cache/' + name for name in ('custom_block_states.json', 'visual_block_states.json'))
    return {relative: {'present': path.exists(), **(fingerprint(path) if path.exists() else {})}
            for relative in paths for path in (contained(instance, relative),)}


class StrictYaml(yaml.SafeLoader):
    pass


def _mapping(loader, node, deep=False):
    result = {}
    for key, value in node.value:
        name = loader.construct_object(key, deep=deep)
        require(type(name) is str and name not in result, 'Duplicate or non-string YAML key')
        result[name] = loader.construct_object(value, deep=deep)
    return result


StrictYaml.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _mapping)


def configuration(path: Path) -> dict:
    require(path.stat().st_size <= 1_048_576, 'Configuration exceeds its bound')
    with path.open('rb') as stream:
        raw = stream.read(1_048_577)
    require(len(raw) <= 1_048_576, 'Configuration grew beyond its read bound')
    text = raw.decode('utf-8')
    tokens = 0
    for token in yaml.scan(text):
        tokens += 1
        require(tokens <= 100_000 and not isinstance(token, (yaml.AliasToken, yaml.AnchorToken)),
                'Configuration aliases or excessive tokens are unsupported')
    value = yaml.load(text, Loader=StrictYaml)
    require(type(value) is dict, 'Configuration must be a YAML mapping')
    return value


def inspect_fresh(instance: Path, run_id: str) -> dict:
    require(instance.is_dir() and instance.is_relative_to(ACCEPTANCE.resolve()) and instance != ACCEPTANCE.resolve(),
            'Use a fresh isolated instance under this checkout/build/acceptance')
    require(str(uuid.UUID(run_id)) == run_id, 'Run UUID must be canonical')
    with fs_path(contained(instance, 'server.properties')).open('rb') as stream:
        raw = stream.read(1_048_577)
    require(len(raw) <= 1_048_576, 'server.properties exceeds its read bound')
    properties = {}
    # Accept only unambiguous keys; Java also interprets key escapes and continuations.
    for line in raw.decode('utf-8').replace('\r\n', '\n').replace('\r', '\n').split('\n'):
        stripped = line.lstrip(' \t\f')
        if not stripped or stripped.startswith(('#', '!')):
            continue
        key, separator, value = line.partition('=')
        require(separator and re.fullmatch(r'[A-Za-z0-9_.-]+', key), 'Unsupported server.properties key or separator')
        require((len(value) - len(value.rstrip('\\'))) % 2 == 0, 'Unsupported server.properties continuation')
        require(key not in properties, 'Duplicate server property')
        require((key not in FIXTURE_PROPERTIES and key not in PACK_PROPERTIES) or '\\' not in value,
                'Escaped fixture or datapack properties are unsupported')
        properties[key] = value
    for key, value in FIXTURE_PROPERTIES.items():
        require(properties.get(key) == value, 'Candidate is not the isolated loopback fixture: ' + key)
    world = contained(instance, 'acceptance-world')
    if world.exists():
        require(world.is_dir() and all(path.name == 'datapacks' for path in world.iterdir()),
                'A fresh candidate may contain only preinstalled datapacks before its first world')
    for suffix in ('_nether', '_the_end'):
        require(not fs_path(contained(instance, 'acceptance-world' + suffix)).exists(),
                'A fresh candidate cannot reuse a Nether or End world directory')
    reports = contained(instance, 'plugins/CookeryAcceptanceProbe')
    require(not any(reports.glob('village-publication-smoke-*.json')), 'Candidate already contains a village smoke receipt')
    probe = configuration(reports / 'config.yml').get('village-publication-smoke')
    require(type(probe) is dict and probe.get('enabled') is True and probe.get('run-id') == run_id,
            'Probe opt-in and run UUID must be prepared explicitly')
    engine = configuration(contained(instance, 'plugins/CraftEngine/config.yml'))
    require(type(engine.get('misc')) is dict and engine['misc'].get('delay-configuration-load') is False,
            'Cold-start smoke requires explicit non-delayed CE configuration')
    require(type(engine.get('chunk-system')) is dict and type(engine['chunk-system'].get('generation')) is dict
            and engine['chunk-system']['generation'].get('feature') is True,
            'Cold-start smoke requires CE FEATURE generation enabled')
    return properties


def check_packs(instance: Path, kitchen: str, village: str) -> None:
    kitchen_path, village_path = contained(instance, kitchen), contained(instance, village)
    require(village_path.parent == instance / 'acceptance-world/datapacks' and village_path.suffix == '.zip',
            'Village pack must be preinstalled in the initial world datapacks directory')
    with bounded_zip(kitchen_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and len(names) <= 10_000, 'Invalid kitchen ZIP member set')
        require(all(0 <= entry.file_size <= 16_777_216 for entry in archive.infolist())
                and sum(entry.file_size for entry in archive.infolist()) <= 134_217_728,
                'Kitchen ZIP exceeds inspection bounds')
        require(archive.testzip() is None, 'Kitchen pack CRC failed')
        require(archive.getinfo('manifest.json').file_size <= 1_048_576, 'Kitchen manifest exceeds its read bound')
        manifest = decode_json(archive.read('manifest.json'))
        require(manifest.get('sourceCommit') == COMMIT and manifest.get('recipeSources') == 612,
                'Kitchen pack is not the complete pinned source export')
        selected = [entry for entry in archive.infolist() if entry.filename.startswith('cookery_kitchen/') and not entry.is_dir()]
        require(selected, 'Missing installed kitchen pack files')
        expected = set()
        total = 0
        for entry in selected:
            total += entry.file_size
            require(entry.file_size <= 16_777_216 and total <= 134_217_728, 'Kitchen ZIP exceeds inspection bounds')
            target = contained(instance, 'plugins/CraftEngine/resources/' + entry.filename)
            require(fs_path(target).is_file() and fs_path(target).stat().st_size == entry.file_size, 'Installed kitchen file is missing or changed')
            require(fingerprint(target)['sha256'] == hashlib.sha256(archive.read(entry)).hexdigest(),
                    'Installed kitchen bytes differ from frozen ZIP: ' + entry.filename)
            expected.add(target.relative_to(instance).as_posix())
        actual = {path.relative_to(instance).as_posix() for path in walk_files(contained(instance, 'plugins/CraftEngine/resources/cookery_kitchen'))}
        require(actual == expected, 'Installed kitchen pack has extra/missing files')
    with bounded_zip(village_path) as archive:
        require(len(archive.infolist()) <= 1_000
                and all(0 <= entry.file_size <= 8_388_608 for entry in archive.infolist())
                and sum(entry.file_size for entry in archive.infolist()) <= 67_108_864,
                'Village ZIP exceeds inspection bounds')
        require(archive.testzip() is None and len(archive.namelist()) == len(set(archive.namelist())), 'Invalid village ZIP')
        entry = archive.getinfo(PROCESSOR_PATH)
        require(entry.file_size <= 1_024 and decode_json(archive.read(entry)) == {'processors': []},
                'Stable native processor must remain an empty dormant placeholder')
        require(archive.getinfo('village-manifest.json').file_size <= 1_048_576, 'Village manifest exceeds its read bound')
        metadata = decode_json(archive.read('village-manifest.json'))
        require(metadata.get('sourceCommit') == COMMIT and metadata.get('naturalVillageIntegration') == 'NOT_IMPLEMENTED',
                'Village pack is not the pinned dormant export')
        require(not any('/template_pool/' in name or name.startswith('data/') and '/structure/' in name for name in archive.namelist()),
                'Rollback smoke must not preload natural kitchens')


def check_initial_pack_selection(properties: dict, village: str) -> None:
    required = {'vanilla', 'file/' + Path(village).name}
    enabled = {value.strip() for value in properties.get('initial-enabled-packs', '').split(',')}
    disabled = {value.strip() for value in properties.get('initial-disabled-packs', '').split(',')}
    require(required.issubset(enabled) and required.isdisjoint(disabled),
            'Explicitly enable vanilla and the chosen village datapack for the initial native registry load')


def check_production_provenance(instance: Path, production_path: str, village: str) -> None:
    with bounded_zip(contained(instance, production_path)) as production, bounded_zip(contained(instance, village)) as datapack:
        require(production.namelist().count('village-loot.json') == 1, 'Expected one production village manifest')
        require(production.getinfo('village-loot.json').file_size <= 1_048_576
                and datapack.getinfo('village-manifest.json').file_size <= 1_048_576,
                'Village manifest exceeds its bound')
        require(production.read('village-loot.json') == datapack.read('village-manifest.json'),
                'Installed village data differs from the defining production JAR provenance')


def freeze(instance: Path, platform: str, run_id: str, defining: dict[str, str], kitchen: str, village: str,
           java: Path, output: Path, source_plugins: dict | None = None) -> dict:
    instance = unlinked_path(instance)
    properties = inspect_fresh(instance, run_id)
    require(platform in ('Paper', 'Folia') and set(defining) == {'production', 'probe', 'craftEngine', 'server'}, 'Freeze all four defining JARs')
    require(all(path.endswith('.jar') for path in defining.values()), 'Defining inputs must be JAR files')
    require(len({contained(instance, path) for path in defining.values()}) == 4, 'Four defining roles must refer to distinct files')
    sources = plugin_sources(instance, defining, source_plugins)
    check_initial_pack_selection(properties, village)
    check_packs(instance, kitchen, village)
    check_production_provenance(instance, defining['production'], village)
    paths = input_paths(instance, list(defining.values()) + [kitchen, village])
    before = inventory(instance, paths)
    java = unlinked_path(java)
    runtime = java_inventory(java)
    mutable = mutable_inputs(instance)
    expected = {'schema': 'village-publication-expected-v1', 'runId': run_id, 'platform': platform,
                'artifacts': {name: before[path] for name, path in defining.items()}}
    _manifest(expected)
    candidate = {'schema': 'village-publication-candidate-v1', 'runId': run_id, 'platform': platform,
                 'frozenAt': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'), 'instance': str(instance),
                 'definingPaths': defining, 'kitchenPack': kitchen, 'villagePack': village,
                 'files': before, 'javaExecutable': str(java), 'javaRuntime': runtime, 'expected': expected,
                 'sourcePlugins': sources, 'initialMutableInputs': mutable,
                 'mutableInputScope': 'INITIAL_HASHES_ONLY_POSTRUN_CHANGES_REQUIRE_EXTERNAL_RUNTIME_EVIDENCE',
                 'launchCommandAndEnvironment': 'NOT_CAPTURED_NO_SERVER_LAUNCHED',
                 'startup': 'NOT_RUN', 'naturalVillageIntegration': 'NOT_ACTIVATED',
                 'scope': 'FROZEN_INPUTS_ONLY_NOT_RUNTIME_ACCEPTANCE'}
    require(inventory(instance, input_paths(instance, list(defining.values()) + [kitchen, village])) == before,
            'Candidate inputs changed while being frozen')
    require(mutable_inputs(instance) == mutable, 'Initial CE mutable inputs changed while being frozen')
    output = output.resolve()
    require(not output.is_relative_to(instance) and output.is_relative_to(ACCEPTANCE.resolve())
            and output != ACCEPTANCE.resolve(), 'Store a new freeze directory separately under build/acceptance')
    require(all(len(json.dumps(value).encode('utf-8')) <= MAX_REPORT_BYTES for value in (candidate, expected)),
            'Frozen input manifest exceeds its read bound')
    output.mkdir(parents=True, exist_ok=False)
    for name, value in (('candidate.json', candidate), ('expected.json', expected)):
        with (output / name).open('x', encoding='utf-8') as stream:
            json.dump(value, stream, indent=2)
            stream.write('\n')
    return candidate


def verify(candidate: dict, before_start: bool = True) -> None:
    require(candidate.get('schema') == 'village-publication-candidate-v1'
            and candidate.get('startup') == 'NOT_RUN' and candidate.get('naturalVillageIntegration') == 'NOT_ACTIVATED', 'Unknown frozen candidate')
    _manifest(candidate['expected'])
    require(type(candidate.get('definingPaths')) is dict
            and set(candidate['definingPaths']) == {'production', 'probe', 'craftEngine', 'server'}
            and len(set(candidate['definingPaths'].values())) == 4, 'Detached or aliased defining artifact roles')
    instance = unlinked_path(Path(candidate['instance']))
    require(instance.is_relative_to(ACCEPTANCE.resolve()) and instance != ACCEPTANCE.resolve(), 'Candidate path left the workspace')
    require(len({contained(instance, path) for path in candidate['definingPaths'].values()}) == 4,
            'Defining roles resolve to aliased files')
    plugin_sources(instance, candidate['definingPaths'], candidate['sourcePlugins'])
    extra = list(candidate['definingPaths'].values()) + [candidate['kitchenPack'], candidate['villagePack']]
    if before_start:
        check_initial_pack_selection(inspect_fresh(instance, candidate['runId']), candidate['villagePack'])
        require(mutable_inputs(instance) == candidate['initialMutableInputs'], 'Initial CE mutable inputs drifted before startup')
    check_packs(instance, candidate['kitchenPack'], candidate['villagePack'])
    check_production_provenance(instance, candidate['definingPaths']['production'], candidate['villagePack'])
    require(inventory(instance, input_paths(instance, extra)) == candidate['files'], 'Frozen candidate files drifted')
    require(type(candidate.get('javaExecutable')) is str and java_inventory(Path(candidate['javaExecutable'])) == candidate['javaRuntime'],
            'Frozen Java runtime drifted or was detached from its complete file inventory')
    require(candidate['runId'] == candidate['expected']['runId'] and candidate['platform'] == candidate['expected']['platform'], 'Candidate identity drifted')
    for name, relative in candidate['definingPaths'].items():
        require(candidate['files'][relative] == candidate['expected']['artifacts'][name], 'Expected artifact is detached from frozen input')


def verify_receipt_snapshots(candidate: dict, registration: Path, report: Path) -> tuple[dict, ReceiptSnapshot, ReceiptSnapshot]:
    """Authenticate one receipt pair and return the exact observed bytes for evidence preservation."""
    verify(candidate, before_start=False)
    instance = Path(candidate['instance']).resolve()
    expected_directory = instance / 'plugins/CookeryAcceptanceProbe'
    require(registration.resolve().parent == expected_directory and report.resolve().parent == expected_directory,
            'Receipts must belong to the frozen candidate instance')
    registration_snapshot, report_snapshot = load_receipt_snapshots(registration, report, candidate['expected'])
    require(registration_snapshot.path.parent == expected_directory and report_snapshot.path.parent == expected_directory,
            'Loaded receipts do not belong to the frozen candidate instance')
    observed = report_snapshot.value
    result = validate(registration_snapshot.value, observed, candidate['expected'])
    require(_time(candidate['frozenAt']) <= _time(observed['registeredAt']), 'Candidate was frozen after probe registration')
    for name, relative in candidate['definingPaths'].items():
        actual = observed['artifacts'][name].get('path')
        require(type(actual) is str and Path(actual).is_absolute()
                and unlinked_path(Path(actual)) == contained(instance, relative),
                'Actual defining JAR path differs from frozen candidate: ' + name)
    verify(candidate, before_start=False)
    check_current_receipt_snapshots(registration_snapshot, report_snapshot)
    return ({**result, 'inputInventory': 'MATCHED',
             'initialMutableInputs': 'FROZEN_ONLY_REQUIRES_PRESTART_AND_RUNTIME_EVIDENCE',
             'wholeServerRun': 'NOT_CERTIFIED_BY_THIS_CHECK'}, registration_snapshot, report_snapshot)


def verify_receipt(candidate: dict, registration: Path, report: Path) -> dict:
    result, _, _ = verify_receipt_snapshots(candidate, registration, report)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    prepare = sub.add_parser('freeze')
    prepare.add_argument('--instance', type=Path, required=True)
    prepare.add_argument('--platform', choices=('Paper', 'Folia'), required=True)
    prepare.add_argument('--run-id', required=True)
    for name in ('production', 'probe', 'craft-engine', 'server'):
        prepare.add_argument('--defining-' + name, required=True)
    for name in ('production', 'probe', 'craft-engine'):
        prepare.add_argument('--source-' + name)
    prepare.add_argument('--kitchen-pack', required=True)
    prepare.add_argument('--village-pack', required=True)
    prepare.add_argument('--java', type=Path, required=True)
    prepare.add_argument('--output', type=Path, required=True)
    check = sub.add_parser('check')
    check.add_argument('--candidate', type=Path, required=True)
    receipt = sub.add_parser('check-receipt')
    receipt.add_argument('--candidate', type=Path, required=True)
    receipt.add_argument('--registration', type=Path, required=True)
    receipt.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == 'freeze':
            value = freeze(args.instance, args.platform, args.run_id,
                           {'production': args.defining_production, 'probe': args.defining_probe,
                            'craftEngine': args.defining_craft_engine, 'server': args.defining_server},
                           args.kitchen_pack, args.village_pack, args.java, args.output,
                           {'production': args.source_production or args.defining_production,
                            'probe': args.source_probe or args.defining_probe,
                            'craftEngine': args.source_craft_engine or args.defining_craft_engine})
            print(json.dumps({'runId': value['runId'], 'inputs': len(value['files']), 'startup': 'NOT_RUN'}))
        elif args.action == 'check':
            verify(read_json(args.candidate))
            print('Frozen candidate inputs match; no server was launched.')
        else:
            print(json.dumps(verify_receipt(read_json(args.candidate), args.registration, args.report), indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, RecursionError, BadZipFile, yaml.YAMLError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
