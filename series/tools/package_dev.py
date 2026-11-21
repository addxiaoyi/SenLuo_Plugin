"""Assemble the local development handoff after both Gradle builds pass."""
from __future__ import annotations

import hashlib
import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from validate_packs import load_files, validate

SUITE = Path(__file__).resolve().parents[1]
ROOT = SUITE.parent
DIST = SUITE / 'build/distribution'
MODULES = ('doll', 'tavern', 'deco', 'music')
PACK_MODULES = MODULES + ('shared',)
BASE_RUNTIME_REPORTS = frozenset(('food-smoke.json', 'enchantment-smoke.json', 'smithing-smoke.json',
                                  'steamer-item-smoke.json', 'kitchen-smoke.json'))


def validate_runtime_reports(evidence: dict, run: dict) -> None:
    required = evidence.get('requiredReports', sorted(BASE_RUNTIME_REPORTS))
    if (not isinstance(required, list) or any(not isinstance(name, str) for name in required)
            or len(set(required)) != len(required) or not BASE_RUNTIME_REPORTS.issubset(required)):
        raise ValueError('Runtime evidence has an invalid required report list')
    reports = run.get('reports', {})
    if (set(reports) != set(required)
            or any(not isinstance(report, dict) or report.get('failures') != [] for report in reports.values())):
        raise ValueError('Runtime evidence contains a failed or incomplete probe run')


def digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def tests(folder: Path) -> dict[str, int]:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in folder.glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    if totals['tests'] == 0 or totals['failures'] or totals['errors'] or totals['skipped']:
        raise ValueError(f'Missing or unsuccessful tests at {folder}: {totals}')
    return totals


def load_module_packs(folder: Path) -> tuple[dict[str, bytes], list[Path]]:
    """Load one independent CE ZIP per module into validator-relative paths."""
    if not folder.is_dir():
        raise ValueError(f'Module pack directory does not exist: {folder}')
    archives = sorted(folder.glob('*-ce-pack-*.zip'))
    if not archives:
        raise ValueError(f'No independent CE packs found in {folder}')
    merged: dict[str, bytes] = {}
    seen: set[str] = set()
    for archive in archives:
        module = archive.name.split('-ce-pack-', 1)[0]
        if module not in PACK_MODULES or module in seen:
            raise ValueError(f'Unexpected or duplicate CE pack: {archive.name}')
        seen.add(module)
        files = load_files(archive)
        if 'pack.yml' not in files or (module != 'shared' and 'configuration/content.yml' not in files):
            raise ValueError(f'Incomplete independent CE pack: {archive.name}')
        for relative, content in files.items():
            path = f'{module}/{relative}'
            if path in merged:
                raise ValueError(f'Duplicate independent CE entry: {path}')
            merged[path] = content
    missing = set(MODULES) - seen
    if missing:
        raise ValueError(f'Missing independent CE packs: {sorted(missing)}')
    return merged, archives


def load_versioned_packs(folder: Path) -> tuple[dict[str, bytes], list[dict]]:
    entries, reports = {}, []
    for family in ('1.21.1', '1.21.2-1.21.8', '1.21.9-26.3'):
        archives = sorted((folder / ('mc' + family)).glob('*-ce-pack-*.zip'))
        expected = {'cookery', *PACK_MODULES}
        if len(archives) != 6 or {path.name.split('-ce-pack-')[0] for path in archives} != expected:
            raise ValueError(f'Expected six independent CE packs for {family}')
        module_files = {}
        for path in archives:
            with ZipFile(path) as archive:
                if archive.testzip():
                    raise ValueError(f'Invalid CE ZIP: {path.name}')
                report = json.loads(archive.read('VERSION-MANIFEST.json'))
                if report['configurationFamily'] != family:
                    raise ValueError(f'CE configuration family mismatch: {path}')
                if set(archive.namelist()) != {*report['files'], 'VERSION-MANIFEST.json'}:
                    raise ValueError(f'Unaccounted CE files: {path}')
                for name, checksum in report['files'].items():
                    if digest(archive.read(name)) != checksum:
                        raise ValueError(f'CE file checksum mismatch: {path.name}/{name}')
                module = path.name.split('-ce-pack-')[0]
                if module != 'cookery':
                    module_files.update({f'{module}/{name}': archive.read(name) for name in archive.namelist()})
            relative = f'versioned-ce-packs/mc{family}/{path.name}'
            entries[relative] = path.read_bytes()
            reports.append({'file': relative, 'family': family, 'sourceSha256': report['sourceSha256'],
                            'changes': report['changes'], 'runtime': report['runtime']})
        validation = validate(module_files)
        if validation['errors']:
            raise ValueError(f'Invalid CE content for {family}: {validation["errors"]}')
    return entries, reports


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packs', type=Path, default=DIST / 'KaleidoscopeSeries-CE-Packs-0.1.0-dev.zip')
    parser.add_argument('--module-packs-dir', type=Path,
                        help='Directory containing one <module>-ce-pack-<version>.zip per plugin')
    parser.add_argument('--cookery-pack', type=Path,
                        help='Independent cookery CE ZIP produced by export_cookery_kitchen_pack.py')
    parser.add_argument('--versioned-cookery', action='store_true', help='Include all ten individually compiled Cookery NMS families')
    parser.add_argument('--versioned-ce-packs', type=Path, help='Three configuration families produced by export_versioned_ce_pack.py')
    parser.add_argument('--runtime-evidence', type=Path, help='Local runtime report whose tested plugin hashes must match this bundle')
    parser.add_argument('--output', type=Path, default=DIST / 'KaleidoscopeSeries-0.1.0-dev.zip')
    args = parser.parse_args()
    DIST.mkdir(parents=True, exist_ok=True)
    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise FileExistsError(f'Development bundle already exists: {destination}')
    packs = args.packs.resolve()
    module_archives: list[Path] = []
    if args.module_packs_dir:
        pack_files, module_archives = load_module_packs(args.module_packs_dir.resolve())
        validation = validate(pack_files)
    else:
        validation = validate(load_files(packs))
    if validation['errors']:
        raise ValueError(f'Invalid CE content: {validation["errors"]}')
    java_tests = {'cookery': tests(ROOT / 'build/test-results/test')}
    for module in ('common', 'doll', 'tavern', 'deco', 'music'):
        java_tests[module] = tests(SUITE / module / 'build/test-results/test')
    entries: dict[str, bytes] = {}
    for module in MODULES:
        jar = SUITE / module / f'build/libs/Kaleidoscope{module.capitalize()}-0.1.0-dev.jar'
        with ZipFile(jar) as archive:
            names = archive.namelist()
            forbidden = ('org/bukkit/', 'io/papermc/', 'org/junit/', 'net/momirealms/', 'net/kaleidoscope/series/')
            if any(name.endswith('.class') and name.startswith(forbidden) for name in names):
                raise ValueError(f'Unrelocated or server/test classes bundled in {jar.name}')
            metadata = archive.read('plugin.yml').decode('utf-8')
            if "api-version: '1.21.11'" not in metadata or '${version}' in metadata:
                raise ValueError(f'Incorrect plugin metadata: {jar.name}')
            catalog = (SUITE / module / 'src/main/resources/catalog.json').read_bytes()
            if archive.read('catalog.json') != catalog:
                raise ValueError(f'Stale catalog inside {jar.name}; rebuild first')
            if not any(name.startswith(f'net/kaleidoscope/{module}/internal/') for name in names):
                raise ValueError(f'Missing relocated shared classes: {jar.name}')
        entries['plugins/' + jar.name] = jar.read_bytes()
    kitchen = ROOT / 'build/libs/KaleidoscopeCookeryPlugin-1.3.0.jar'
    entries['plugins/' + kitchen.name] = kitchen.read_bytes()
    with ZipFile(kitchen) as archive:
        kitchen_metadata = archive.read('plugin.yml')
    versioned = []
    if args.versioned_cookery:
        variants = sorted((ROOT / 'build/libs/versions').glob('KaleidoscopeCookeryPlugin-*-mc*.jar'))
        if len(variants) != 10:
            raise ValueError(f'Expected ten Cookery version families, found {len(variants)}')
        for jar in variants:
            with ZipFile(jar) as archive:
                bridges = [name for name in archive.namelist() if name.startswith('net/kaleidoscope/cookery/nms/')
                           and name.rsplit('/', 1)[-1].startswith('NmsV') and name.endswith('.class')]
                if len(bridges) != 1:
                    raise ValueError(f'Expected one NMS bridge in {jar.name}: {bridges}')
                attributes = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\n ', '')
                versions = next((line.split(': ', 1)[1] for line in attributes.splitlines()
                                 if line.startswith('SenLuo-Minecraft-Versions: ')), None)
                if not versions or 'paperweight-mappings-namespace: mojang' not in attributes:
                    raise ValueError(f'Missing version/mapping metadata: {jar.name}')
                if archive.read('plugin.yml') != kitchen_metadata:
                    raise ValueError(f'Versioned metadata differs from universal JAR: {jar.name}')
            entries['versions/' + jar.name] = jar.read_bytes()
            versioned.append({'file': 'versions/' + jar.name, 'minecraft': versions.split(','), 'bridge': bridges[0],
                              'runtime': 'NOT_TESTED'})
    cookery_pack = None
    if args.cookery_pack:
        cookery_pack = args.cookery_pack.resolve()
        if not cookery_pack.is_file():
            raise ValueError(f'Cookery CE pack does not exist: {cookery_pack}')
        with ZipFile(cookery_pack) as archive:
            names = archive.namelist()
            if 'cookery_kitchen/pack.yml' not in names or 'cookery_kitchen/configuration/kitchen.yml' not in names:
                raise ValueError(f'Invalid independent cookery CE pack: {cookery_pack.name}')
        entries['ce-packs/cookery-ce-pack-1.3.0.zip'] = cookery_pack.read_bytes()
    if module_archives:
        for archive in module_archives:
            entries['ce-packs/' + archive.name] = archive.read_bytes()
    else:
        entries[packs.name] = packs.read_bytes()
    versioned_packs = []
    if args.versioned_ce_packs:
        adapted, versioned_packs = load_versioned_packs(args.versioned_ce_packs)
        entries.update(adapted)
    entries['README.md'] = (SUITE / 'docs/INSTALL.md').read_bytes()
    for path in (SUITE / 'docs').glob('*'):
        if path.is_file():
            entries['docs/' + path.name] = path.read_bytes()
    # Keep the cross-plugin installation/upgrade instructions next to the
    # independent artifacts; series/docs/INSTALL.md remains the module-local
    # guide for backwards-compatible bundles.
    for path in (ROOT / 'docs/install-guide.md', ROOT / 'docs/upgrade-guide.md'):
        if path.is_file():
            entries['docs/' + path.name] = path.read_bytes()
    for name in ('VERSION-COMPATIBILITY.md', 'LUNCH-BAG.md', 'SEATING.md', 'TABLES.md', 'STORAGE-BLOCKS.md', 'HANGING-STRINGS.md', 'LONG-BENCH.md', 'EIGHT-IMMORTALS-TABLE.md', 'RED-LANTERN.md', 'KITCHEN-KNIFE-SMITHING.md', 'STEAMER-STATE.md', 'STEAMER-PORTABLE.md', 'STEAMER-INTERACTION.md', 'DECO-MODELS.md', 'FISHRACK.md', 'RESOURCE-PATHS.md', 'TRASH-CAN.md', 'TRASH-CAN-ANIMATION.md', 'SCARECROW.md', 'SCARECROW-HITS.md'):
        guide = ROOT / 'docs' / name
        if guide.is_file():
            entries['docs/' + name] = guide.read_bytes()
    matrix = SUITE / 'docs/acceptance-matrix.json'
    if matrix.is_file():
        entries['docs/acceptance-matrix.json'] = matrix.read_bytes()
    entries['organization-checkouts.json'] = (ROOT / 'docs/research/organization-checkouts.json').read_bytes()
    runtime_evidence = None
    runtime_evidence_path = None
    if args.runtime_evidence:
        raw = args.runtime_evidence.read_bytes()
        runtime_evidence = json.loads(raw)
        shipped = {Path(name).name: digest(content) for name, content in entries.items() if name.endswith('.jar')}
        for run in runtime_evidence['verifiedRuns']:
            tested = run['jarHashes']
            for name in (name for name in tested if name.startswith('Kaleidoscope')):
                if shipped.get(name) != tested[name]:
                    raise ValueError(f'Runtime evidence uses a different plugin JAR: {name}')
            validate_runtime_reports(runtime_evidence, run)
            for variant in versioned:
                if Path(variant['file']).name in tested:
                    variant['runtime'] = 'PARTIAL_PAPER_PROBES_PASS; no player/client/Folia proof'
                    server = run['reports']['food-smoke.json']['server']
                    coverage = variant.setdefault('tested_servers', [])
                    if server not in coverage:
                        coverage.append(server)
        runtime_evidence_path = 'docs/research/' + args.runtime_evidence.name
        entries[runtime_evidence_path] = raw
    manifest = {
        'created_at': datetime.now().astimezone().isoformat(),
        'status': 'development; see VERSION-COMPATIBILITY.md for runtime coverage; vanilla-client acceptance not performed',
        'baseline_commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
        'uncommitted_work': bool(subprocess.check_output(
            ['git', '-C', str(ROOT), 'status', '--porcelain'], text=True).strip()),
        'compile_target': {'java': 21, 'paper_api': '1.21.11', 'modern_components_api': '1.21.11', 'craftengine_api': '26.9.1'},
        'java_tests': java_tests,
        'legacy_compat_tests': tests(SUITE / 'common/build/test-results/legacyCompatTest'),
        'legacy_gson_tests': tests(ROOT / 'build/test-results/testLegacyGson'),
        'runtime_evidence': runtime_evidence_path,
        'versioned_cookery': versioned,
        'versioned_ce_packs': versioned_packs,
        'pack_validation': validation,
        'independent_ce_packs': (['cookery-ce-pack-1.3.0.zip'] if cookery_pack else [])
                                + [path.name for path in module_archives],
        'independent_plugins': ['cookery', *MODULES],
        'files': {name: {'size': len(content), 'sha256': digest(content)} for name, content in sorted(entries.items())},
    }
    entries['BUILD-MANIFEST.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    entries['SHA256SUMS.txt'] = ''.join(f'{digest(content)}  {name}\n' for name, content in sorted(entries.items())).encode()
    with ZipFile(destination, 'x', compression=ZIP_DEFLATED) as archive:
        for name, content in sorted(entries.items()):
            archive.writestr(name, content)
    with ZipFile(destination) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f'Bundle CRC failed: {bad}')
        for name, content in entries.items():
            if digest(archive.read(name)) != digest(content):
                raise ValueError(f'Bundle checksum failed: {name}')
    manifest_path = destination.with_name(destination.stem + '-BUILD-MANIFEST.json')
    manifest_path.write_bytes(entries['BUILD-MANIFEST.json'])
    checksum = digest(destination.read_bytes())
    destination.with_suffix('.sha256').write_text(f'{checksum}  {destination.name}\n', encoding='utf-8')
    print(json.dumps({'bundle': str(destination), 'bytes': destination.stat().st_size, 'sha256': checksum,
                      'java_tests': java_tests, 'pack_validation': validation}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
