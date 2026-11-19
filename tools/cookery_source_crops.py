"""Three source vegetables as persistent CE blocks, not display-only farm decorations."""
from __future__ import annotations

import copy
import hashlib
import json

from audit_upstream import AuditError, read_blobs
from cookery_seating import ART, PLUGIN, JAVA
from export_cookery_catalog import COMMIT

CROPS = {'tomato': ('tomato', None, None),
         'chili': ('red_chili', 'green_chili', .2),
         'lettuce': ('lettuce', 'caterpillar', .1)}
# Source code is part of the conversion contract, including the two use overrides
# and SickleItem's empty-tool / age-zero path. Never silently generalize a new revision.
JAVA_HASHES = {
    JAVA + 'block/crop/BaseCropBlock.java': '53ab73ada2607e7502c877adc4dd80d2e0d29ee1ae4602b9647fa7cefa758e37',
    JAVA + 'block/crop/ChiliCropBlock.java': 'c12f5b9318a8d49d5013714db99c64334ac3be1993cfad9e2cbb07c89014764f',
    JAVA + 'block/crop/LettuceCropBlock.java': '9be4f61273ce834061fb274d3cfca3d82608717a26206d591ee645c9ef371a1c',
    JAVA + 'init/ModBlocks.java': '19058c8549243d4f8c348a621d992ac4817c664e502f7c9a15056815c30fb5df',
    JAVA + 'init/ModItems.java': 'ac7a1f6bd6b88f1b8a019307e6aba5a39b8211c7522874017ab99b8887ef2379',
    JAVA + 'item/SickleItem.java': '84f84dd3f309d765c8a2754fdc7ca78745c12f1b3ec91d815b30b9f1a79493ed',
}


def mature(name: str, upstream: bool = False) -> dict:
    return ({'block': ART + ':' + name + '_crop', 'condition': 'minecraft:block_state_property',
             'properties': {'age': '7'}} if upstream else
            {'type': 'match_block_property', 'properties': {'age': '7'}})


def source_loot(name: str) -> dict:
    result, extra, chance = CROPS[name]
    pools = [{'bonus_rolls': 0.0, 'entries': [{'type': 'minecraft:alternatives', 'children': [
        {'type': 'minecraft:item', 'conditions': [mature(name, True)], 'name': ART + ':' + result},
        {'type': 'minecraft:item', 'name': ART + ':' + name + '_seed'}]}], 'rolls': 1.0},
        {'bonus_rolls': 0.0, 'conditions': [mature(name, True)], 'entries': [
            {'type': 'minecraft:item', 'functions': [{'enchantment': 'minecraft:fortune',
                'formula': 'minecraft:binomial_with_bonus_count', 'function': 'minecraft:apply_bonus',
                'parameters': {'extra': 3, 'probability': .5714286}}],
             'name': ART + ':' + name + '_seed'}], 'rolls': 1.0}]
    if extra:
        pools.append({'bonus_rolls': 0.0, 'conditions': [mature(name, True)], 'entries': [
            {'type': 'minecraft:item', 'conditions': [{'chance': chance, 'condition': 'minecraft:random_chance'}],
             'name': ART + ':' + extra}], 'rolls': 1.0})
    return {'type': 'minecraft:block', 'functions': [{'function': 'minecraft:explosion_decay'}],
            'pools': pools, 'random_sequence': ART + ':blocks/' + name + '_crop'}


def crop_loot(name: str) -> dict:
    result, extra, chance = CROPS[name]
    pools = [{'rolls': 1, 'entries': [{'type': 'alternatives', 'children': [
        {'type': 'item', 'conditions': [mature(name)], 'item': PLUGIN + ':' + result},
        {'type': 'item', 'item': PLUGIN + ':' + name + '_seed'}]}]},
        {'rolls': 1, 'conditions': [mature(name)], 'entries': [
            {'type': 'item', 'item': PLUGIN + ':' + name + '_seed', 'functions': [
                {'type': 'apply_bonus', 'enchantment': 'minecraft:fortune', 'formula': {
                    'type': 'binomial_with_bonus_count', 'extra': 3, 'probability': .5714286}}]}]}]
    if extra:
        pools.append({'rolls': 1, 'conditions': [mature(name)], 'entries': [
            {'type': 'item', 'item': PLUGIN + ':' + extra,
             'conditions': [{'type': 'random', 'value': chance}]}]})
    return {'functions': [{'type': 'explosion_decay'}], 'pools': pools}


def validate_sources(files: dict[str, bytes]) -> None:
    for path, expected in JAVA_HASHES.items():
        if hashlib.sha256(files[path]).hexdigest() != expected:
            raise AuditError('source crop: unsupported Java rules ' + path)
    for name in CROPS:
        path = 'src/generated/resources/data/' + ART + '/loot_tables/blocks/' + name + '_crop.json'
        if json.loads(files[path]) != source_loot(name):
            raise AuditError('source crop: unsupported loot ' + name)


def source_crops(source, assets, items, definitions, names):
    loot_paths = ['src/generated/resources/data/' + ART + '/loot_tables/blocks/' + n + '_crop.json' for n in CROPS]
    files = read_blobs(source, COMMIT, list(JAVA_HASHES) + loot_paths)
    validate_sources(files)
    blocks = {}
    for name, (result, extra, _) in CROPS.items():
        key, seed = PLUGIN + ':' + name + '_crop', PLUGIN + ':' + name + '_seed'
        states = {f'age={age}': {'model': f'{ART}:block/crop/{name}/stage{age}'} for age in range(8)}
        path = ART + '/blockstates/' + name + '_crop.json'
        if json.loads(assets[path]) != {'variants': states}:
            raise AuditError('source crop: unsupported states ' + name)
        for age in range(8):
            path = f'{ART}/models/block/crop/{name}/stage{age}.json'
            expected = {'parent': ART + ':block/cross', 'render_type': 'minecraft:cutout',
                        'textures': {'cross': f'{ART}:block/crop/{name}/stage{age}'}}
            if json.loads(assets[path]) != expected:
                raise AuditError('source crop: unsupported stage model ' + name + '/' + str(age))
        for item_name in filter(None, (result, extra)):
            if PLUGIN + ':' + item_name not in items:
                raise AuditError('source crop: uninstalled result ' + item_name)
        if seed in items:
            raise AuditError('source crop: duplicate seed ' + seed)
        items[seed] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['item.' + ART + '.' + name + '_seed']},
                       'model': copy.deepcopy(definitions[ART + ':item/' + name + '_seed']),
                       'behavior': {'type': 'block_item', 'block': key}}
        appearances = {f'age{age}': {
            'auto_state': {'type': 'lower_tripwire', 'id': f'cookery_crop_{name}_{age}'},
            'model': {'path': states[f'age={age}']['model']}}
            for age in range(8)}
        blocks[key] = {
            'settings': {'item': seed, 'hardness': 0, 'resistance': 0, 'map_color': 'plant',
                         'push_reaction': 'destroy', 'can_occlude': False, 'is_suffocating': False,
                         'is_view_blocking': False, 'is_redstone_conductor': False, 'block_light': 0,
                         'sounds': {'break': 'minecraft:block.crop.break', 'place': 'minecraft:item.crop.plant',
                                    **{k: 'minecraft:block.grass.' + k for k in ('step', 'hit', 'fall')}}},
            'behavior': [{'type': PLUGIN + ':source_crop', 'crop': name}],
            'loot': crop_loot(name),
            'states': {'properties': {'age': {'type': 'int', 'range': '0~7', 'default': 0}},
                       'appearances': appearances,
                       'variants': {f'age={age}': {'appearance': f'age{age}'} for age in range(8)}}}
    return blocks, {'sourceCommit': COMMIT, 'blocks': 3, 'seeds': 3, 'ageStates': 24,
        'sourceFiles': {p: hashlib.sha256(v).hexdigest() for p, v in files.items()},
        'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
        'growth': {'source': 'Minecraft 1.20.1 CropBlock', 'farmlandOnly': True, 'growLight': 9,
                   'survivalLight': 8, 'skyLightSurvival': True, 'soilAndCrowding': True, 'boneMealBonus': [2, 5]},
        'harvest': {'tomato': {'rightClick': {'result': PLUGIN + ':tomato', 'count': 1, 'resetAge': 5}},
                    'chili': {'rightClick': {'results': {PLUGIN + ':green_chili': .2, PLUGIN + ':red_chili': .8},
                                             'count': 1, 'resetAge': 5}},
                    'lettuce': {'rightClick': 'PASS'},
                    'sickle': {'loot': 'source block loot', 'tool': 'EMPTY', 'resetAge': 0}},
        'serverSelectionHeights': list(range(2, 10)), 'collision': 'EMPTY',
        'open': ['Vanilla client selection outline remains the allocated tripwire outline; source 2..9/16 boxes are server-side only.',
                 'Paper/Folia growth, bonemeal cancellation, protection callbacks, survival drops and CE reload persistence require runtime acceptance.',
                 'Forge CropGrowEvent hooks and third-party Forge soil extensions are unavailable on Bukkit.',
                 'Random distributions are preserved; source world RNG sequence is not replayed.',
                 'Support loss uses CE one-tick delayed destruction; source update timing still needs acceptance.',
                 'Source custom-block mining statistics/exhaustion and villager crop recognition are not ported.']}
