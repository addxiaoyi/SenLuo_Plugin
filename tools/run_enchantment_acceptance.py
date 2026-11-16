"""Run isolated Paper/Folia restart probes; preserve existing acceptance servers."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import zipfile

from export_cookery_catalog import COMMIT, publish
from prepare_test_servers import SERVERS, digest, verify_plugin

WORKSPACE = Path(__file__).resolve().parents[1]
ACCEPTANCE = WORKSPACE / 'build' / 'acceptance'
PORTS = {'paper': 25583, 'folia': 25584}
MISSING_BLOCKS = (
    'chopping_board', 'enamel_basin', 'kitchenware_racks', 'pot', 'shawarma_spit',
    'steamer', 'stockpot', 'stove', 'fruit_basket', 'teapot', 'teacup_coaster',
)
ANSI_ESCAPE = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')


def console_text(path: Path) -> str:
    return ANSI_ESCAPE.sub('', path.read_text(encoding='utf-8', errors='replace'))


def runtime_failures(log: str) -> list[str]:
    """CE reports rejected configs and crashed tickers as warnings, not errors."""
    failures = []
    for line in log.splitlines():
        error = '/ERROR]' in line or ' ERROR]:' in line
        engine_failure = '[CraftEngine]' in line and (
            re.search(r'Failed to (?:tick|load|unload|save|serialize)\b', line) or re.search(
                r'发现\s*[1-9]\d*\s*个问题|(?:found|detected)\s+[1-9]\d*\s+(?:issue(?:\(s\)|s)?|errors|problems)',
                line, re.IGNORECASE))
        if error or engine_failure:
            failures.append(line)
    return failures


def missing_block_error(line: str) -> bool:
    """Keep this known fixture gap visible without masking new runtime errors."""
    if '[KaleidoscopeCookeryPlugin]' not in line:
        return False
    message = line.split('[KaleidoscopeCookeryPlugin]', 1)[1].strip()
    if message == 'craftengine.registry.blocks.missing':
        return True
    if message.startswith('请检查上述 CraftEngine custom 配置是否存在'):
        return True
    return any(message.startswith(f'- kaleidoscopecookery:{name}    配置：') for name in MISSING_BLOCKS)


def prepare(platform: str, root: Path, accept_eula: bool) -> Path:
    if not accept_eula:
        raise ValueError('Explicit --accept-eula is required before launching a test server')
    root = root.resolve()
    if not root.is_relative_to(ACCEPTANCE) or root == ACCEPTANCE:
        raise ValueError('Copies must live in a separate directory under build/acceptance')
    source = ACCEPTANCE / platform
    instance = root / platform
    if instance.exists():
        raise ValueError(f'{instance}: use a new run directory to preserve prior evidence')
    port = PORTS[platform]
    with socket.socket() as check:
        check.bind(('127.0.0.1', port))
    if digest(source / 'server.jar') != SERVERS[platform][1]:
        raise ValueError(f'{platform}: pinned server JAR changed')
    instance.mkdir(parents=True)
    for name in ('cache', 'libraries'):
        shutil.copytree(source / name, instance / name)
    shutil.copyfile(source / 'server.jar', instance / 'server.jar')
    plugins = instance / 'plugins'
    plugins.mkdir()
    jars = (
        source / 'plugins' / 'craft-engine-paper-plugin-26.9.1.jar',
        WORKSPACE / 'build' / 'libs' / 'KaleidoscopeCookeryPlugin-1.3.0.jar',
        ACCEPTANCE / 'probe' / 'CookeryAcceptanceProbe.jar',
    )
    for jar, name in zip(jars, ('CraftEngine', 'KaleidoscopeCookery', 'CookeryAcceptanceProbe'), strict=True):
        verify_plugin(jar, name)
        shutil.copyfile(jar, plugins / jar.name)
    engine = plugins / 'CraftEngine'
    engine.mkdir()
    for name in ('resources', 'libs'):
        shutil.copytree(source / 'plugins' / 'CraftEngine' / name, engine / name)
    shutil.copyfile(source / 'plugins' / 'CraftEngine' / 'config.yml', engine / 'config.yml')
    (instance / 'eula.txt').write_text('# Explicitly accepted by the user for local tests.\neula=true\n', encoding='utf-8')
    (instance / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=true\nenable-rcon=false\n'
        'enable-query=false\nmax-players=8\nview-distance=2\nsimulation-distance=2\n'
        'spawn-protection=0\nlevel-name=acceptance-world\nlevel-seed=8675309\n', encoding='utf-8')
    datapacks = instance / 'acceptance-world' / 'datapacks'
    shutil.copytree(WORKSPACE / 'examples' / 'cookery-enchantments', datapacks / 'cookery_enchantments')
    return instance


def install_kitchen(instance: Path, archive: Path) -> dict:
    """Replace only the new copy's food fixture; keep its original files as evidence."""
    instance = instance.resolve()
    if not instance.is_relative_to(ACCEPTANCE) or instance.parent == ACCEPTANCE:
        raise ValueError('Kitchen pack installation requires an isolated acceptance copy')
    engine = instance / 'plugins' / 'CraftEngine'
    resources = engine / 'resources'
    with zipfile.ZipFile(archive) as package:
        manifest = json.loads(package.read('manifest.json'))
        if manifest['sourceCommit'] != COMMIT or manifest['target'] != {'minecraft': '1.21.11', 'craftEngine': '26.9.1'}:
            raise ValueError('Kitchen pack baseline mismatch')
        names = package.namelist()
        if len(names) != len(set(names)) or set(names) != set(manifest['files']) | {'manifest.json'}:
            raise ValueError('Kitchen pack contains duplicate or unmanifested entries')
        contents = {}
        for name, expected in manifest['files'].items():
            target = (resources / name).resolve()
            if (not name.startswith('cookery_kitchen/') or '..' in Path(name).parts or '\\' in name
                    or not target.is_relative_to((resources / 'cookery_kitchen').resolve())):
                raise ValueError(f'Unsafe kitchen pack path: {name}')
            content = package.read(name)
            if hashlib.sha256(content).hexdigest() != expected:
                raise ValueError(f'Kitchen pack digest mismatch: {name}')
            if target.exists():
                raise ValueError(f'Kitchen fixture already installed: {target}')
            contents[target] = content
    backup = engine / 'fixture-backups' / 'cookery_foods'
    original = (resources / 'cookery_foods').resolve()
    if not original.is_relative_to(instance) or not backup.resolve().is_relative_to(instance):
        raise ValueError('Fixture backup escapes isolated instance')
    backup.parent.mkdir(parents=True, exist_ok=True)
    original.rename(backup)
    for target, content in contents.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    probe = instance / 'plugins' / 'CookeryAcceptanceProbe'
    probe.mkdir(exist_ok=True)
    settings = 'kitchen-smoke:\n  enabled: true\n'
    if manifest.get('recipes'):
        settings += 'recipe-smoke:\n  enabled: true\n'
    if manifest.get('recipes', {}).get('pot_recipes'):
        settings += 'pot-smoke:\n  enabled: true\n'
    if manifest.get('millstoneModel'):
        settings += 'millstone-smoke:\n  enabled: true\n'
    if manifest.get('recipes', {}).get('bamboo_tray_recipes'):
        settings += 'bamboo-smoke:\n  enabled: true\n'
    if manifest.get('plates'):
        settings += 'plate-smoke:\n  enabled: true\n'
    if manifest.get('placedMeals'):
        settings += 'meal-smoke:\n  enabled: true\n'
    if manifest.get('riceBowls'):
        settings += 'rice-smoke:\n  enabled: true\n'
    (probe / 'config.yml').write_text(settings, encoding='utf-8')
    publish(instance / 'ce-kitchen-manifest.json', manifest)
    return manifest


def validate_kitchen(report: dict, manifest: dict, cycle: int) -> None:
    meals = manifest.get('placedMeals', {})
    blocks = manifest['blocks'] - len(meals)
    states = sum(manifest['states'].values()) - sum(meal['states'] for meal in meals.values())
    if (report['failures'] or report['checkedBlocks'] != blocks
            or report['checkedStates'] != states
            or report['checkedItems'] != manifest['items'] or report['nonemptyContainers'] != 2
            or (cycle == 1 and report['placedBlocks'] != blocks)
            or (cycle > 1 and report['reloadedBlocks'] != blocks)):
        raise ValueError(f'Kitchen smoke differs from the installed manifest: {report}')


def validate_recipes(report: dict, manifest: dict, cycle: int) -> None:
    counts = manifest['recipes']
    expected_plans = sum(counts[name] for name in ('teapot_result', 'accurate_foods', 'chopping_board_raws'))
    expected_plans += counts.get('stockpot_recipes', 0)
    if (report['failures'] or report['checkedPlans'] != expected_plans
            or report['checkedCrafting'] != counts['recipes'] or report['teaCups'] != manifest['teaCups']
            or report['soupBases'] != manifest['soupBases']):
        raise ValueError(f'Recipe registration or output construction failed: {report}')
    phase = 'IN_PROGRESS_SAVED' if cycle == 1 else 'COMPLETED_RELOADED'
    if report.get('phase') != phase:
        raise ValueError(f'Recipe lifecycle requires {phase}: {report}')
    steamers = manifest.get('accurateAppliances', {}).get('STEAMER', counts['accurate_foods'])
    if cycle > 1 and (report.get('steamedOutputs') != steamers or report.get('teaServings') != 8):
        raise ValueError(f'Recipe controllers did not produce the expected outputs: {report}')
    if cycle == 2 and (report.get('reloadedBatches') != 3 or report.get('cuttingDrops') != {
            'kaleidoscopecookery:raw_cut_small_meats': 3, 'minecraft:bone': 1}):
        raise ValueError(f'Recipe reload or cutting conservation failed: {report}')
    if counts.get('stockpot_recipes'):
        if report.get('exactStockpotRecipes', 0) + report.get('distinctStockpotRecipes', 0) != counts['stockpot_recipes']:
            raise ValueError(f'Stockpot recipe coverage differs: {report}')
        if cycle > 1 and (report.get('stockpotServings') != [2, 0] or not report.get('stockpotEmptyNoDuplicate')
                          or abs(report.get('stockpotLiquidOffset', 1) + 0.105) > 0.00001):
            raise ValueError(f'Stockpot serving or liquid state differs: {report}')
        if cycle == 2 and report.get('reloadedStockpots') != 2:
            raise ValueError(f'Stockpot batch reload missing: {report}')


def validate_pots(report: dict, manifest: dict, cycle: int) -> None:
    count = manifest['recipes']['pot_recipes']
    if (report['failures'] or report.get('checkedPlans') != count
            or report.get('exactRecipes', 0) + report.get('distinctRecipes', 0) != count):
        raise ValueError(f'Pot recipe coverage differs: {report}')
    phase = 'IN_PROGRESS_SAVED' if cycle == 1 else 'COMPLETED_RELOADED'
    if report.get('phase') != phase:
        raise ValueError(f'Pot lifecycle requires {phase}: {report}')
    if cycle == 1 and report.get('stirs') != [1, 1]:
        raise ValueError(f'Pot must save unfinished batches: {report}')
    if cycle == 2 and report.get('reloadedPots') != 2:
        raise ValueError(f'Pot reload missing: {report}')
    if cycle > 1 and (report.get('servings') != [2, 0] or not report.get('emptyNoDuplicate')):
        raise ValueError(f'Pot serving conservation failed: {report}')


def validate_millstone(report: dict, manifest: dict, cycle: int) -> None:
    if report['failures'] or report.get('checkedFurniture') != manifest['furniture']:
        raise ValueError(f'Millstone furniture registration failed: {report}')
    phase = {1: 'IN_PROGRESS_SAVED', 2: 'COMPLETED_RELOADED', 3: 'EMPTY_RELOADED'}[cycle]
    if report.get('phase') != phase:
        raise ValueError(f'Millstone lifecycle requires {phase}: {report}')
    if cycle == 1:
        progress = report.get('progressDegrees', [])
        if (len(progress) != 2 or any(not 0 < value < 360 for value in progress)
                or report.get('rawCount') != 2 or report.get('completionEvents') != 0 or report.get('spawnedOutput') != 0):
            raise ValueError(f'Millstone did not save an unfinished animal-driven batch: {report}')
    elif report.get('reloadedFurniture') != 1 or not report.get('emptyNoDuplicate'):
        raise ValueError(f'Millstone reload or conservation missing: {report}')
    if cycle == 2 and (report.get('completionEvents') != 1 or report.get('spawnedOutput') != 10
                       or report.get('outputCount') != 10 or not report.get('animalRestored')):
        raise ValueError(f'Millstone resumed output or animal state differs: {report}')
    if cycle == 3 and (report.get('completionEvents') != 0 or report.get('spawnedOutput') != 0):
        raise ValueError(f'Millstone produced again after restart: {report}')


def validate_bamboo(report: dict, manifest: dict, cycle: int) -> None:
    if report['failures'] or report.get('checkedRecipes') != manifest['recipes']['bamboo_tray_recipes']:
        raise ValueError(f'Bamboo recipe coverage differs: {report}')
    phase = {1: 'IN_PROGRESS_SAVED', 2: 'COMPLETED_RELOADED', 3: 'EMPTY_RELOADED'}[cycle]
    if report.get('phase') != phase:
        raise ValueError(f'Bamboo lifecycle requires {phase}: {report}')
    if cycle == 1:
        required = {'sky_drying', 'opaque_pause', 'glass_drying', 'stacked_drying',
                    'invalid_dripstone_pause', 'water_dripstone_wetting'}
        if set(report.get('environmentChecks', [])) != required:
            raise ValueError(f'Bamboo environmental processing evidence is incomplete: {report}')
        # Pinned sample durations: drying 1800 ticks, clay wetting 900 ticks.
        for name, material, amount, duration in (('drying', 'WET_SPONGE', 2, 1800),
                                               ('sheltered', 'KELP', 3, 1800),
                                               ('dripping', 'CLAY', 2, 900), ('stacked', 'MUD', 1, 1800)):
            state = report.get('states', {}).get(name, {})
            progress = state.get('progress', [])
            if (state.get('items') != [material, 'STONE' if name == 'drying' else 'AIR', 'AIR', 'AIR']
                    or state.get('amounts') != [amount, 3 if name == 'drying' else 0, 0, 0]
                    or state.get('completion') != [0, 0, 0, 0] or len(progress) != 4
                    or not 0 < progress[0] < duration or progress[1:] != [0, 0, 0]):
                raise ValueError(f'Bamboo did not save an unfinished {name} batch: {state}')
        return
    if report.get('reloadedTrays') != 4 or not report.get('emptyNoDuplicate') or report.get('hopperCount') != 1:
        raise ValueError(f'Bamboo restart or conservation failed: {report}')
    if cycle == 2 and (report.get('completedOutputs') != {'sponge': 2, 'dried_kelp': 3, 'mud': 2, 'clay': 1}
                       or report.get('takenSponge') != 1 or not report.get('reverseWetting')
                       or report.get('canceledExtractions', 0) < 2 or report.get('acceptedExtractions') != 1):
        raise ValueError(f'Bamboo real processing, reversal or hopper cancellation failed: {report}')


def validate_plates(report: dict, manifest: dict, cycle: int) -> None:
    plates = manifest['plates']
    count = len(plates)
    phase = {1: 'EMPTY_PLATES_SAVED', 2: 'PLATES_REMOVED', 3: 'REMOVAL_RELOADED'}[cycle]
    if report['failures'] or report.get('checkedPlates') != count or report.get('phase') != phase:
        raise ValueError(f'Plate coverage or lifecycle differs: {report}')
    if cycle == 1:
        expected = Counter()
        for plate in plates.values():
            for item in plate['servings']:
                expected[item] += plate['maxServings']
        servings = sum(plate['maxServings'] for plate in plates.values())
        empty = {name.split(':', 1)[1]: 0 for name in plates}
        if (report.get('defaultFull') != count or report.get('emptySaved') != count
                or report.get('takenServings') != servings or report.get('takenItems') != dict(expected)
                or report.get('servings') != empty or not report.get('noEarlyBowl')
                or report.get('reentryChecks') != servings):
            raise ValueError(f'Plate full-to-empty conservation failed: {report}')
    elif cycle == 2:
        expected = Counter()
        for plate in plates.values():
            for item in plate['servings']:
                expected[item] += plate['maxServings'] - 1
            expected.update(plate['drops'])
        single = sum(len(plate['servings']) == 1 for plate in plates.values())
        bowls = sum(plate['drops'].count('minecraft:bowl') for plate in plates.values())
        if (report.get('reloadedEmpty') != count or report.get('singleRefills') != single
                or not report.get('berryRefillRejected') or report.get('emptyBowls') != bowls
                or report.get('breakBowls') != bowls or report.get('breakDrops') != dict(expected)
                or report.get('removedPlates') != count * 2 or report.get('servings') != {}
                or report.get('reentryChecks') != count + single):
            raise ValueError(f'Plate refill or removal conservation failed: {report}')
    elif (report.get('absentPlates') != count or report.get('servings') != {}
          or not report.get('emptyNoDuplicate')):
        raise ValueError(f'Removed plates returned on restart: {report}')


def validate_meals(report: dict, manifest: dict, cycle: int) -> None:
    meals = manifest['placedMeals']
    count = len(meals)
    cells = sum(meal['cells'] for meal in meals.values())
    phase = {1: 'PARTIAL_SAVED', 2: 'REMOVED', 3: 'REMOVAL_RELOADED'}[cycle]
    if (report['failures'] or report.get('checkedMeals') != count
            or report.get('checkedCells') != cells or report.get('layouts') != dict(Counter(meal['layout'] for meal in meals.values()))
            or report.get('checkedStates') != sum(meal['states'] for meal in meals.values()) or report.get('phase') != phase):
        raise ValueError(f'Placed meal coverage or phase differs: {report}')
    if cycle == 1:
        if any(report.get(key) != count for key in ('placedMeals', 'blockedPlacements', 'cancelledConsumes',
                                                   'committedBites', 'reentryChecks', 'badSaveRejected', 'expiredCommitsRejected')):
            raise ValueError(f'Placed meal first-bite evidence differs: {report}')
        states = report.get('states', {})
        if set(states) != set(meals):
            raise ValueError('Placed meal snapshot coverage differs')
        for id_, saved in states.items():
            state = json.loads(saved['state'])
            if (state['itemId'] != id_ or state['maxBites'] != meals[id_]['maxBites']
                    or state['consumed'] != 1 or state['sourceCommit'] != COMMIT or not saved.get('group')):
                raise ValueError(f'Placed meal partial snapshot differs: {id_}')
    elif cycle == 2:
        extras = Counter(drop for meal in meals.values() for drop in meal['drops'])
        bites = sum(meal['maxBites'] - 1 for meal in meals.values())
        if (any(report.get(key) != count for key in ('reloadedMeals', 'exhaustedMeals', 'removedExhausted'))
                or report.get('extraDrops') != dict(extras) or report.get('untouchedWholeDrops') != dict.fromkeys(meals, 1)
                or report.get('nonAnchorBreaks') != sum(meal['cells'] > 1 for meal in meals.values())
                or report.get('committedBites') != bites or report.get('reentryChecks') != bites or report.get('states') != {}):
            raise ValueError(f'Placed meal restart/drop conservation differs: {report}')
    elif (report.get('absentMeals') != count or report.get('absentCells') != cells
          or not report.get('emptyNoDuplicate') or report.get('states') != {}):
        raise ValueError(f'Placed meal removal did not persist: {report}')


def validate_rice(report: dict, manifest: dict, cycle: int) -> None:
    bowls = manifest['riceBowls']
    count = len(bowls)
    phase = {1: 'CRAFTED_SAVED', 2: 'RESTORED', 3: 'EMPTY_RELOADED'}[cycle]
    if report['failures'] or report.get('checkedRecipes') != count or report.get('phase') != phase:
        raise ValueError(f'Rice bowl native recipe coverage differs: {report}')
    if not re.fullmatch('[0-9a-f]{64}', report.get('chestMetadata', '')):
        raise ValueError('Rice bowl checkpoint lacks a serialized item hash')
    if cycle == 1:
        expected_counts = {'qualityCases': count * 25, 'extraQualityCases': count,
                           'extraConsumptionCases': count * 3, 'duplicateRejected': count * 2,
                           'mismatchRejected': count, 'repeatedCrafts': count * 3,
                           'overflowBowls': count * 2, 'storedResults': count * 3, 'storedBowls': count * 3}
        expected_items = dict(Counter({row['result']: 3 for row in bowls.values()}) + Counter({'minecraft:bowl': count * 3}))
        if any(report.get(key) != value for key, value in expected_counts.items()) or report.get('storedItems') != expected_items:
            raise ValueError(f'Rice bowl quality, input or container conservation failed: {report}')
    elif cycle == 2:
        if (report.get('reloadedResults') != count * 3 or report.get('reloadedBowls') != count * 3
                or not report.get('metadataPreserved') or report.get('storedItems') != {}):
            raise ValueError(f'Rice bowl disk state changed: {report}')
    elif not report.get('emptyNoDuplicate') or report.get('storedItems') != {}:
        raise ValueError(f'Rice bowl clear did not persist: {report}')


def boot(instance: Path, cycle: int, timeout: float, kitchen: bool = False) -> dict:
    log = instance / f'boot-{cycle}.log'
    probe = instance / 'plugins' / 'CookeryAcceptanceProbe'
    reports = ('food-smoke.json', 'enchantment-smoke.json') + (('kitchen-smoke.json',) if kitchen else ())
    manifest = json.loads((instance / 'ce-kitchen-manifest.json').read_text(encoding='utf-8')) if kitchen else {}
    if manifest.get('recipes'):
        reports += ('recipe-smoke.json',)
    if manifest.get('recipes', {}).get('pot_recipes'):
        reports += ('pot-smoke.json',)
    if manifest.get('millstoneModel'):
        reports += ('millstone-smoke.json',)
    if manifest.get('recipes', {}).get('bamboo_tray_recipes'):
        reports += ('bamboo-smoke.json',)
    if manifest.get('plates'):
        reports += ('plate-smoke.json',)
    if manifest.get('placedMeals'):
        reports += ('meal-smoke.json',)
    if manifest.get('riceBowls'):
        reports += ('rice-smoke.json',)
    # New report files are required; do not accept a previous successful boot as this boot's evidence.
    for name in reports:
        if (probe / name).exists():
            (probe / name).rename(probe / f'previous-{cycle}-{name}')
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen(
            ['java', '-Xms512M', '-Xmx2G', '-Dfile.encoding=UTF-8', '-jar', 'server.jar', 'nogui'],
            cwd=instance, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0)
        try:
            deadline = time.monotonic() + timeout
            while True:
                text = console_text(log)
                if ('Done (' in text and 'COOKERY_ENCHANTMENT_SMOKE' in text
                        and all((probe / name).is_file() for name in reports)):
                    break
                if process.poll() is not None:
                    raise RuntimeError(f'{instance.name}: exited {process.returncode}; see {log}')
                if time.monotonic() >= deadline:
                    raise TimeoutError(f'{instance.name}: smoke probe timed out; see {log}')
                time.sleep(0.5)
            loaded = {name: json.loads((probe / name).read_text(encoding='utf-8')) for name in reports}
            food, enchanted = loaded['food-smoke.json'], loaded['enchantment-smoke.json']
            if food['checkedConsumables'] != 103 or food['failures']:
                raise ValueError(f'{instance.name}: food smoke failed: {food}')
            if enchanted['checkedTools'] != 5 or enchanted['failures'] or (cycle > 1 and enchanted['reloadedTools'] != 5):
                raise ValueError(f'{instance.name}: enchantment smoke failed: {enchanted}')
            for report in loaded.values():
                if report['sourceCommit'] != COMMIT or report['craftEngine'] != '26.9.1':
                    raise ValueError(f'{instance.name}: smoke baseline mismatch')
            validators = {'kitchen-smoke.json': validate_kitchen, 'recipe-smoke.json': validate_recipes,
                          'pot-smoke.json': validate_pots, 'millstone-smoke.json': validate_millstone,
                          'bamboo-smoke.json': validate_bamboo, 'plate-smoke.json': validate_plates,
                          'meal-smoke.json': validate_meals, 'rice-smoke.json': validate_rice}
            for name, validate in validators.items():
                if name in loaded:
                    validate(loaded[name], manifest, cycle)
            assert process.stdin is not None
            process.stdin.write('ce reload pack\n')
            process.stdin.flush()
            while ('资源包生成完成（' not in console_text(log)
                   and 'Resource pack generated' not in console_text(log)):
                generation_log = console_text(log)
                if 'Incorrect argument for command' in generation_log or 'Unknown or incomplete command' in generation_log:
                    raise ValueError(f'{instance.name}: resource command rejected; see {log}')
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise TimeoutError(f'{instance.name}: resource pack generation failed; see {log}')
                time.sleep(0.5)
            errors = runtime_failures(console_text(log))
            unexpected = errors if kitchen else [line for line in errors if not missing_block_error(line)]
            if unexpected:
                raise ValueError(f'{instance.name}: unexpected runtime errors: {unexpected}')
            pack = instance / 'plugins' / 'CraftEngine' / 'cache' / 'hosted' / 'default' / 'resource_pack.zip'
            if not pack.is_file():
                raise ValueError(f'{instance.name}: generated pack missing')
            evidence = {'cycle': cycle, 'food': food, 'enchantments': enchanted,
                        'resourcePackSha256': digest(pack), 'knownFixtureErrors': errors,
                        'startup': 'PASS' if kitchen else 'PASS_WITH_MISSING_APPLIANCE_DEFINITIONS',
                        'nativeEnchantmentSerialization': 'PASS', 'resourceGeneration': 'PASS',
                        'pluginArtifacts': {path.relative_to(instance).as_posix(): digest(path)
                                            for path in (instance / 'plugins').glob('*.jar')}}
            for name, key in (('kitchen-smoke.json', 'kitchen'), ('recipe-smoke.json', 'recipes'),
                              ('pot-smoke.json', 'pots'), ('millstone-smoke.json', 'millstone'),
                              ('bamboo-smoke.json', 'bamboo'), ('plate-smoke.json', 'plates'), ('meal-smoke.json', 'meals'),
                              ('rice-smoke.json', 'riceBowls')):
                if name in loaded:
                    evidence[key] = loaded[name]
        finally:
            if process.poll() is None:
                assert process.stdin is not None
                try:
                    process.stdin.write('stop\n')
                    process.stdin.flush()
                    process.wait(timeout=45)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                    raise RuntimeError(f'{instance.name}: graceful shutdown failed; see {log}')
            if process.stdin is not None:
                process.stdin.close()
            if process.returncode != 0:
                raise RuntimeError(f'{instance.name}: shutdown exit code {process.returncode}; see {log}')
            shutdown_errors = [line for line in runtime_failures(console_text(log))
                               if kitchen or not missing_block_error(line)]
            if shutdown_errors:
                raise ValueError(f'{instance.name}: runtime/shutdown errors retained in {log}')
    evidence['stoppedCleanly'] = True
    publish(instance / f'smoke-{cycle}.json', evidence)
    return evidence


def run(root: Path, accept_eula: bool, timeout: float, kitchen_pack: Path | None = None) -> dict:
    evidence: dict = {'sourceCommit': COMMIT, 'instances': {}, 'playerFlow': 'NOT_TESTED', 'clientResourcePack': 'NOT_TESTED'}
    for platform in PORTS:
        print(f'{platform}: preparing isolated copy', flush=True)
        instance = prepare(platform, root, accept_eula)
        if kitchen_pack is not None:
            manifest = install_kitchen(instance, kitchen_pack)
            evidence['kitchenPack'] = {'sha256': digest(kitchen_pack), 'manifest': manifest}
        cycles = []
        for cycle in ((1, 2, 3) if kitchen_pack is not None and manifest.get('recipes', {}).get('stockpot_recipes') else (1, 2)):
            print(f'{platform}: boot {cycle}', flush=True)
            cycles.append(boot(instance, cycle, timeout, kitchen=kitchen_pack is not None))
            print(f'{platform}: boot {cycle} passed, stopped cleanly', flush=True)
        installed = {path.relative_to(instance).as_posix(): digest(path) for path in (instance / 'plugins').glob('*.jar')}
        installed['server.jar'] = digest(instance / 'server.jar')
        datapack = instance / 'acceptance-world' / 'datapacks' / 'cookery_enchantments'
        installed.update({path.relative_to(instance).as_posix(): digest(path) for path in datapack.rglob('*') if path.is_file()})
        evidence['instances'][platform] = {'port': PORTS[platform], 'artifacts': installed, 'cycles': cycles, 'stoppedCleanly': True}
        publish(root / 'acceptance.json', evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ACCEPTANCE / 'enchantments')
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--timeout', type=float, default=360,
                        help='Seconds per boot, including source-duration processing and resource generation')
    parser.add_argument('--kitchen-pack', type=Path)
    args = parser.parse_args()
    try:
        run(args.root.resolve(), args.accept_eula, args.timeout, args.kitchen_pack)
    except (OSError, ValueError, RuntimeError, TimeoutError) as exception:
        print(f'Enchantment acceptance failed: {exception}', file=sys.stderr)
        return 1
    print(f'Both platforms passed; evidence: {args.root / "acceptance.json"}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
