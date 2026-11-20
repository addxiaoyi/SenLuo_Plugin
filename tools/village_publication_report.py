"""Check a cold-start rollback receipt; never infer natural-generation acceptance.

The expected manifest must be frozen independently before starting a fresh isolated
candidate. This module reads files only and never launches or stops a server.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
import uuid

from cookery_village import BIOMES, COMMIT, STRUCTURES

MAX_REPORT_BYTES = 4_194_304
ARTIFACTS = frozenset(('production', 'probe', 'craftEngine', 'server'))
SHA = re.compile(r'[0-9a-f]{64}\Z')
IDENTITY = re.compile(r'[A-Za-z_$][A-Za-z0-9_.$]*@[0-9a-f]{1,8}\Z')
LOCATION = re.compile(r'[a-z0-9_.-]+:[a-z0-9_./-]+\Z')
PAIR = 'com.mojang.datafixers.util.Pair'
POOL_CLASS = 'net.minecraft.world.level.levelgen.structure.pools.'
TEMPLATE_CLASS = 'net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate'
RULE_CLASS = 'net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor'
SLOTS = {**{'template:kaleidoscopecookery:village/houses/' + biome + '_kitchen': 'CACHE_ENTRY'
            for biome in BIOMES}, 'processor:list': 'REFERENCE',
         **{'pool:minecraft:village/' + biome + '/houses:' + field: kind
            for biome in BIOMES for field, kind in
            (('raw', 'REFERENCE'), ('expanded', 'REFERENCE'), ('maximum', 'INTEGER'))}}


class ReportError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def _object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON member: ' + key)
        result[key] = value
    return result


def read_json(path: Path) -> dict:
    with path.open('rb') as stream:
        raw = stream.read(MAX_REPORT_BYTES + 1)
    return decode_json(raw)


def decode_json(raw: bytes) -> dict:
    require(0 < len(raw) <= MAX_REPORT_BYTES, 'Report exceeds its byte bound or is empty')
    def invalid_constant(value: str) -> None:
        raise ReportError('Invalid JSON constant: ' + value)
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=_object, parse_constant=invalid_constant)
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
        raise ReportError('Invalid complete UTF-8 JSON report') from error
    require(type(value) is dict, 'Report must be a JSON object')
    remaining = [(value, 0)]
    nodes = 0
    while remaining:
        child, depth = remaining.pop()
        nodes += 1
        require(depth <= 64 and nodes <= 200_000, 'Report object graph exceeds its inspection bound')
        if type(child) is dict:
            remaining.extend((item, depth + 1) for item in child.values())
        elif type(child) is list:
            remaining.extend((item, depth + 1) for item in child)
    return value


def _same(first: object, second: object) -> bool:
    if type(first) is not type(second):
        return False
    if type(first) is dict:
        return first.keys() == second.keys() and all(_same(value, second[key]) for key, value in first.items())
    if type(first) is list:
        return len(first) == len(second) and all(_same(a, b) for a, b in zip(first, second))
    return first == second


def _run_id(value: object) -> None:
    require(type(value) is str, 'Missing canonical run UUID')
    try:
        require(str(uuid.UUID(value)) == value, 'Run UUID is not canonical')
    except (ValueError, AttributeError) as error:
        raise ReportError('Run UUID is not canonical') from error


def _manifest(expected: dict) -> None:
    require(expected.get('schema') == 'village-publication-expected-v1', 'Unknown expected-manifest schema')
    _run_id(expected.get('runId'))
    require(expected.get('platform') in ('Paper', 'Folia'), 'Expected platform must be Paper or Folia')
    require(type(expected.get('artifacts')) is dict and expected['artifacts'].keys() == ARTIFACTS,
            'Freeze all three expected artifact identities')
    for key in ARTIFACTS:
        value = expected['artifacts'][key]
        require(type(value) is dict and type(value.get('bytes')) is int and value['bytes'] > 0
                and type(value.get('sha256')) is str and SHA.fullmatch(value['sha256']),
                'Invalid frozen artifact: ' + key)


def _identity(value: object, expected_type: str | None = None) -> None:
    require(type(value) is str and IDENTITY.fullmatch(value), 'Missing typed native identity')
    if expected_type is not None:
        require(value.startswith(expected_type + '@'), 'Native identity has the wrong defining type')


def _cache_entry(value: dict, fresh: bool = False) -> None:
    require(type(value.get('present')) is bool, 'Cache presence is not a boolean')
    if not value['present']:
        require(value.get('type') == 'ABSENT' and value.get('identity') == 'null'
                and 'templateIdentity' not in value, 'Invalid absent cache witness')
    else:
        require(value.get('type') == 'java.util.Optional', 'Native cache must contain an Optional')
        _identity(value.get('identity'), 'java.util.Optional')
        template = value.get('templateIdentity')
        if template != 'null':
            _identity(template, TEMPLATE_CLASS)
        if fresh:
            require(template == 'null', 'Fresh smoke had a populated kitchen template key')


def _reference(value: dict, raw: bool) -> None:
    require(value.get('present') is True and type(value.get('type')) is str, 'Invalid native list reference')
    _identity(value.get('identity'), value['type'])
    contents = value.get('contents')
    require(type(value.get('size')) is int and 0 <= value['size'] <= 8_192
            and type(contents) is list and len(contents) == value['size'], 'Incomplete native list witness')
    identities = []
    for item in contents:
        if raw:
            require(type(item) is dict and type(item.get('weight')) is int
                    and 0 <= item['weight'] <= 1_048_576, 'Invalid native weighted pair')
            _identity(item.get('identity'), PAIR)
            _identity(item.get('elementIdentity'))
            identities.append(item['identity'])
        else:
            _identity(item)
            identities.append(item)
    require(value.get('contentsSha256') == hashlib.sha256('\n'.join(identities).encode()).hexdigest(),
            'Native list content checksum differs')


def _whole_cache(value: object) -> dict:
    require(type(value) is dict, 'Missing full template cache witness')
    _identity(value.get('identity'), 'java.util.concurrent.ConcurrentHashMap')
    entries = value.get('entries')
    require(type(value.get('size')) is int and 0 <= value['size'] <= 4_096
            and type(entries) is list and len(entries) == value['size'], 'Incomplete full template cache')
    result = {}
    for row in entries:
        require(type(row) is dict and type(row.get('key')) is str and LOCATION.fullmatch(row['key'])
                and row['key'] not in result and type(row.get('value')) is dict, 'Invalid full cache entry')
        _cache_entry(row['value'])
        require(row['value']['present'] is True, 'A full cache entry cannot be absent')
        result[row['key']] = row['value']
    return result


def _time(value: object) -> datetime:
    require(type(value) is str and value.endswith('Z'), 'Missing UTC receipt time')
    try:
        parsed = datetime.fromisoformat(value[:-1] + '+00:00')
    except ValueError as error:
        raise ReportError('Invalid UTC receipt time') from error
    require(parsed.tzinfo is not None and parsed.utcoffset() == timezone.utc.utcoffset(parsed), 'Invalid UTC receipt time')
    return parsed


def validate(registration: dict, report: dict, expected: dict) -> dict:
    _manifest(expected)
    require(registration.get('schema') == 'village-publication-smoke-v1', 'Unknown registration schema')
    require(registration.get('runId') == expected['runId'], 'Registration run differs from frozen run')
    require(registration.get('status') == 'NOT_RUN' and registration.get('passed') is False,
            'Registration must remain an explicit NOT_RUN receipt')
    require(registration.get('phase') == 'REGISTERED_DURING_ON_LOAD', 'Registration occurred outside onLoad')
    require(report.get('schema') == 'village-publication-smoke-v1', 'Unknown final report schema')
    require(report.get('runId') == expected['runId'], 'Final report run differs from frozen run')
    require(report.get('status') == 'PASS' and report.get('passed') is True,
            'Only an explicit successful rollback receipt can pass')
    require(report.get('phase') == 'CE_INITIAL_AFTER_ENABLE', 'Wrong native lifecycle phase')
    require(report.get('minecraftVersion') == '1.21.11' and report.get('platform') == expected['platform'],
            'Wrong target version or platform')
    for key in ('registeredAt', 'artifacts', 'platform', 'minecraftVersion', 'serverVersion', 'packIdentity'):
        require(key in registration and key in report and _same(registration[key], report[key]),
                'Registration and final receipt disagree: ' + key)
    require(type(report.get('serverVersion')) is str and 0 < len(report['serverVersion']) <= 512,
            'Missing actual server version')
    require(_time(report.get('completedAt')) >= _time(report.get('registeredAt')), 'Receipt completion predates registration')
    require(report.get('naturalVillageIntegration') == 'NOT_ACTIVATED'
            and report.get('runtimeScope') == 'NATIVE_COLD_START_PUBLICATION_ROLLBACK_ONLY'
            and report.get('controlledFailureBoundary') == 'UNPROVEN_IF_UNEXPECTED_PARTIAL_STATE',
            'Rollback smoke cannot establish natural generation or controlled startup abort')
    require(report.get('packIdentity') == 'NOT_CERTIFIED_BY_THIS_PROBE', 'Pack identity is outside this probe scope')
    for key in ('nativeWorldCountBefore', 'nativeWorldCountAfter'):
        require(type(report.get(key)) is int and report[key] == 0, 'Native worlds existed during smoke: ' + key)
    for key in ('publishedWitnessObserved', 'sentinelObserved', 'cleanRollback', 'fullOriginalsRestored',
                'scopeClosed', 'writerAvailableAfterClose', 'writerExcludedInsideWindow'):
        require(report.get(key) is True, 'Incomplete native rollback proof: ' + key)
    require(report.get('transactionStatus') == 'ROLLED_BACK' and type(report.get('rollbackFailures')) is list
            and not report['rollbackFailures'] and type(report.get('unconfirmedSlots')) is list
            and not report['unconfirmedSlots'], 'Transaction contradicts the claimed complete rollback')
    require('failure' in report and report['failure'] is None, 'Native rollback reported a failure')
    artifacts = report.get('artifacts')
    require(type(artifacts) is dict and artifacts.keys() == ARTIFACTS, 'Incomplete actual artifact set')
    for key in ARTIFACTS:
        actual, frozen = artifacts[key], expected['artifacts'][key]
        require(type(actual) is dict and type(actual.get('bytes')) is int
                and actual['bytes'] == frozen['bytes'] and actual.get('sha256') == frozen['sha256'],
                'Actual artifact differs from frozen candidate: ' + key)
    templates = report.get('sourceTemplates')
    require(type(templates) is list and len(templates) == 5, 'Expected five source template receipts')
    seen = set()
    for row in templates:
        require(type(row) is dict, 'Invalid source template receipt')
        biome = row.get('biome')
        require(type(biome) is str and biome in BIOMES and biome not in seen, 'Unknown or repeated source biome')
        seen.add(biome)
        require(row.get('location') == 'kaleidoscopecookery:village/houses/' + biome + '_kitchen'
                and row.get('sourceCommit') == COMMIT and row.get('sourceSha256') == STRUCTURES[biome]
                and type(row.get('targetDataVersion')) is int and row['targetDataVersion'] == 4671,
                'Source template provenance differs: ' + biome)
    rows = report.get('slots')
    require(type(rows) is list and len(rows) == len(SLOTS), 'Incomplete native publication write set')
    seen = set()
    by_name = {}
    changed = 0
    for row in rows:
        require(type(row) is dict, 'Invalid native slot receipt')
        name = row.get('name')
        require(type(name) is str and name in SLOTS and name not in seen, 'Unknown or duplicate native slot')
        seen.add(name)
        by_name[name] = row
        require(row.get('kind') == SLOTS[name] and type(row.get('changed')) is bool, 'Wrong native slot kind or count')
        for field in ('before', 'staged', 'publishedWitness', 'restoredWitness'):
            require(type(row.get(field)) is dict and bool(row[field]), 'Missing typed slot witness: ' + name)
        require(_same(row['staged'], row['publishedWitness']) and _same(row['before'], row['restoredWitness']),
                'Published or restored slot witness differs: ' + name)
        require(row['changed'] is (not _same(row['before'], row['staged'])), 'Incorrect changed-slot accounting: ' + name)
        if row['kind'] == 'INTEGER':
            for field in ('before', 'staged'):
                require(type(row[field].get('value')) is int, 'Invalid native maximum-size primitive')
            require(row['staged']['value'] == -2_147_483_648, 'Changed pools must invalidate the native size cache')
        elif row['kind'] == 'CACHE_ENTRY':
            _cache_entry(row['before'], fresh=True)
            _cache_entry(row['staged'])
            require(row['staged']['present'] is True and row['staged'].get('templateIdentity') != 'null',
                    'Kitchen template was not actually published')
            require(row['changed'] is True, 'Fresh smoke must transplant each template')
        else:
            _reference(row['before'], name.endswith(':raw'))
            _reference(row['staged'], name.endswith(':raw'))
            require(row['changed'] is True, 'Fresh smoke must actually transplant all template/list slots')
        changed += row['changed']
    processor = by_name['processor:list']
    require(processor['before']['contents'] == [] and len(processor['staged']['contents']) == 1,
            'Fresh smoke must replace exactly one empty processor list')
    _identity(processor['staged']['contents'][0], RULE_CLASS)
    for biome in BIOMES:
        prefix = 'pool:minecraft:village/' + biome + '/houses:'
        raw, expanded = by_name[prefix + 'raw'], by_name[prefix + 'expanded']
        old_pairs, next_pairs = raw['before']['contents'], raw['staged']['contents']
        require(len(next_pairs) == len(old_pairs) + 1 and all(
            a['elementIdentity'] == b['elementIdentity'] and a['weight'] == b['weight']
            for a, b in zip(old_pairs, next_pairs)), 'Native pool prefix or weights changed')
        kitchen = next_pairs[-1]
        require(kitchen['weight'] == 4, 'Native kitchen weight differs')
        _identity(kitchen['elementIdentity'], POOL_CLASS + 'LegacySinglePoolElement')
        for state in ('before', 'staged'):
            require(sum(item['weight'] for item in raw[state]['contents']) <= 8_192,
                    'Native expanded witness exceeds its inspection bound')
            weighted = [item['elementIdentity'] for item in raw[state]['contents'] for _ in range(item['weight'])]
            require(len(weighted) <= 8_192 and weighted == expanded[state]['contents'],
                    'Native expanded pool selection differs from encoded order/weights')
    cache_before = _whole_cache(report.get('templateCacheBefore'))
    _whole_cache(report.get('templateCacheRestored'))
    require(_same(report['templateCacheBefore'], report['templateCacheRestored']), 'Full native template cache did not restore')
    for name, row in by_name.items():
        if row['kind'] == 'CACHE_ENTRY':
            key = name[len('template:'):]
            if row['before']['present']:
                require(key in cache_before and _same(cache_before[key], row['before']), 'Kitchen before-state differs from full cache')
            else:
                require(key not in cache_before, 'Absent kitchen was present in the full cache')
    require(type(report.get('expectedChangedSlots')) is int and report['expectedChangedSlots'] == changed
            and type(report.get('expectedNoOpSlots')) is int and report['expectedNoOpSlots'] == len(SLOTS) - changed
            and changed >= 16, 'Native slot counts disagree')
    return {'runId': expected['runId'], 'platform': expected['platform'], 'changedSlots': changed,
            'noOpSlots': len(SLOTS) - changed, 'result': 'PASS',
            'scope': 'NATIVE_COLD_START_PUBLICATION_ROLLBACK_ONLY', 'naturalVillageIntegration': 'NOT_ACTIVATED'}


def validate_files(registration_path: Path, report_path: Path, expected: dict) -> dict:
    _manifest(expected)
    stem = 'village-publication-smoke-' + expected['runId']
    require(report_path.name == stem + '.json' and registration_path.name == stem + '-registration.json'
            and report_path.resolve().parent == registration_path.resolve().parent, 'Receipt filenames do not bind the frozen run')
    directory = report_path.parent
    require(not (directory / (stem + '-missing-after-enable.json')).exists()
            and not any(directory.glob(stem + '-duplicate-callback-*.json')), 'This run has a supplemental failure marker')
    return validate(read_json(registration_path), read_json(report_path), expected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--registration', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--expected', type=Path, required=True)
    args = parser.parse_args()
    try:
        expected = read_json(args.expected)
        result = validate_files(args.registration, args.report, expected)
        print(json.dumps(result, indent=2))
        return 0
    except (OSError, ReportError, RecursionError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
