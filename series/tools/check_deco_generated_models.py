"""Check actual CE-generated Deco models using its persistent CMD assignments or item model paths."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def plain(node: dict) -> dict:
    """Normalize namespaced model/property types and CE's path field for comparison."""
    result = {}
    for key, value in node.items():
        if key in ('type', 'property') and isinstance(value, str):
            value = value.removeprefix('minecraft:')
        if key in ('path', 'model') and isinstance(value, str) and ':' not in value:
            value = 'minecraft:' + value
        if isinstance(value, dict):
            value = plain(value)
        elif isinstance(value, list):
            value = [plain(v) if isinstance(v, dict) else v for v in value]
        result['model' if key == 'path' else key] = value
    return result


def check(runtime: Path) -> dict:
    config_path = runtime / 'resources/deco/configuration/content.yml'
    config = json.loads(config_path.read_text(encoding='utf-8'))
    pack = runtime / 'generated/resource_pack.zip'
    assignments_path = runtime / 'cache/custom_model_data/paper.json'
    assignments = json.loads(assignments_path.read_text(encoding='utf-8')) if assignments_path.exists() else {}
    modern, legacy, unresolved, missing_modern = [], [], [], []
    with ZipFile(pack) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f'Resource pack CRC failure: {bad}')
        names = set(archive.namelist())
        paper = 'assets/minecraft/items/paper.json'
        ranged = {}
        if paper in names:
            root = json.loads(archive.read(paper))['model']
            if root['type'].removeprefix('minecraft:') != 'range_dispatch':
                raise ValueError('Expected CE CMD dispatch for paper')
            ranged = {entry['threshold']: entry['model'] for entry in root['entries']}
        old = 'assets/minecraft/models/item/paper.json'
        overrides = {row['predicate']['custom_model_data']: row['model']
                     for row in json.loads(archive.read(old)).get('overrides', [])
                     if set(row.get('predicate', {})) == {'custom_model_data'}} if old in names else None
        for id_, item in config['items'].items():
            expected = plain(item['model'])
            namespace, name = id_.split(':', 1)
            path = f'assets/{namespace}/items/{name}.json'
            expected_old = item.get('legacy_model', {}).get('path')
            if expected_old is None and set(expected) == {'type', 'model'} and expected['type'] == 'model':
                expected_old = expected['model']
            if path not in names and assignments.get(id_) not in ranged:
                # CE's legacy conversion failure omits the whole item from old-server
                # generated output, including the modern CMD tree. Retain this gap.
                if overrides is None or expected_old is not None:
                    raise ValueError(f'Missing generated modern model: {id_}')
                missing_modern.append(id_)
                unresolved.append(id_)
                continue
            actual = plain(json.loads(archive.read(path))['model'] if path in names else ranged[assignments[id_]])
            # CE omits the default index, and parses normalized RGB strings into JSON numbers.
            def normalize(node):
                if isinstance(node, list):
                    return [normalize(v) for v in node]
                if not isinstance(node, dict):
                    return node
                result = {k: normalize(v) for k, v in node.items() if not (k == 'index' and v == 0)}
                if 'when' in result and not isinstance(result['when'], list):
                    result['when'] = [result['when']]
                if result.get('type') in ('custom_model_data', 'constant', 'dye', 'potion', 'map_color'):
                    for field in ('default', 'value'):
                        if isinstance(result.get(field), list):
                            result[field] = [float(v) for v in result[field]]
                return result
            if normalize(actual) != normalize(expected):
                raise ValueError(f'Generated modern model differs: {id_}: {actual} != {expected}')
            modern.append(id_)
            if overrides is not None:
                if expected_old is None:
                    unresolved.append(id_)
                elif overrides.get(assignments[id_], '').removeprefix('minecraft:') != expected_old.removeprefix('minecraft:'):
                    raise ValueError(f'Generated legacy model differs: {id_}')
                else:
                    legacy.append(id_)
        # Independent pinned-source expectation catches normalized-color regressions in both conversion stages.
        sheep = config['items']['kaleidoscope_deco:doll_62']['model']['tints'][0]['default']
        if [float(v) for v in sheep] != [1, 1, 1]:
            raise ValueError('Pinned sheep default color must remain white')
    return {'runtime': str(runtime), 'resourcePack': str(pack), 'bytes': pack.stat().st_size,
            'sha256': hashlib.sha256(pack.read_bytes()).hexdigest(), 'entries': len(names),
            'checkedModernModels': len(modern), 'checkedLegacyModels': len(legacy),
            'modernModelsOmittedByLegacyConversion': missing_modern,
            'legacyOutsideExactComparison': unresolved, 'failures': [], 'clientRendering': 'NOT_TESTED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=Path, help='Server plugins/CraftEngine directory')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = check(args.runtime)
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding='utf-8')
    print(encoded)
