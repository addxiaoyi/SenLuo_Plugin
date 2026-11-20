"""Pinned village structures and native loot, without baking transient CE state IDs."""
from __future__ import annotations

import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct

from audit_upstream import AuditError, literal_registrations, read_blobs, unique_object
from cookery_recipes import ART, PLUGIN, RecipeSources, mapped, read_sources
from cookery_stockpot import SOUP_BUCKETS
from export_cookery_catalog import COMMIT

JAVA = 'src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/'
RES = 'src/main/resources/data/' + ART + '/'
GEN = 'src/generated/resources/data/' + ART + '/'
BIOMES = ('plains', 'snowy', 'savanna', 'desert', 'taiga')
PROCESSOR_KEY = PLUGIN + ':crop_replace'
PROCESSOR_PATH = 'data/' + PLUGIN + '/worldgen/processor_list/crop_replace.json'
STRUCTURES = dict(zip(BIOMES, (
    'ddca3c5a30a77379c231373af9cc0cee10efe17b897bbb94fdf419e60ccc13f5',
    'f7f14b7099d58e12ce4b9a956d07ee3b926d9771213eb89de50ec1c7684a0d2e',
    'abe8791c813e7cae34e9c2ef32666b111252f39a64198b96845df0343cddf7c7',
    'e1ef8db86682c1aae5b08c317d7f10dff3a45570d8f8c80d4a0e6fe91b3fde85',
    '633f05d20a864946dae8a781372544b1fc504ad67ac0719bdf6c7869b4b6baf1')))
JAVA_HASHES = {
    JAVA + 'init/registry/FoodBiteRegistry.java': '60a1dd6af63ab34ddcab7954ed2add0f1f807aa87576efcadc2af3a7e36ed1ac',
    JAVA + 'loot/RecipeRandomlyFunction.java': '8b54ecc674fda42b2756ee4dcbe922dfd13c43e44ced430fb11cc17a34fd68e5',
    JAVA + 'datagen/lootable/ChestLootTables.java': 'cd8424027886a9b7cb6fbba24b176e5e9f7c4d6bdb9df339b33cc8b0c90d87bb',
}
RULE_HASHES = {**JAVA_HASHES,
    JAVA + 'event/AddVillageStructuresEvent.java': '40062db4a4073a4e198e9f3b30f5f8ddc9f86cc7d0f3b16d42b9a8134e6cc93d',
    RES + 'worldgen/processor_list/crop_replace.json': '153ec621cfc9c16367c936956ecf405ac156d5177e9dffc8e0c300b0b588cf15',
}
TOKEN_KEY = PLUGIN + ':village_loot'
TOKEN_PREFIX = 'v1|'
TABLES = ('village_chest', 'village_hide_chest')
NORMAL = (('tomato', 10, 1, 7), ('red_chili', 10, 1, 4), ('oil', 10, 1, 5),
          ('tomato_seed', 10, 1, 3), ('chili_seed', 10, 1, 3), ('lettuce_seed', 10, 1, 3),
          ('wild_rice', 10, 1, 3), ('rice_panicle', 8, 2, 3), ('straw_block', 8, 1, 2),
          ('recipe_item', 8, 1, 1), ('iron_kitchen_knife', 5, 1, 1), ('straw_hat', 5, 1, 1))
HIDDEN = tuple((name, 10, 1, 1) for name in ('seafood_miso_soup', 'chicken_and_mushroom_stew',
    'pork_bone_soup', 'braised_beef_with_potatoes', 'beef_meatball_soup', 'egg_fried_rice', 'slime_ball_meal'))


def require(value: bool, reason: str) -> None:
    if not value:
        raise AuditError('village: ' + reason)


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf8')


class NbtReader:
    """Small bounded Java-NBT reader; raw source bytes remain the storage authority."""
    def __init__(self, raw: bytes):
        require(len(raw) <= 1_048_576, 'compressed structure is too large')
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
                self.raw = stream.read(8_388_609)
                require(len(self.raw) <= 8_388_608 and not stream.read(1), 'inflated structure is too large')
        except (OSError, EOFError) as error:
            raise AuditError('village: malformed gzip structure') from error
        self.at = self.nodes = 0

    def take(self, size: int) -> bytes:
        require(size >= 0 and self.at + size <= len(self.raw), 'truncated NBT')
        value = self.raw[self.at:self.at + size]
        self.at += size
        return value

    def number(self, code):
        return struct.unpack('>' + code, self.take(struct.calcsize(code)))[0]

    def text(self):
        raw = self.take(self.number('H'))
        try:
            return raw.decode('utf8')
        except UnicodeDecodeError as error:
            raise AuditError('village: unsupported NBT modified-UTF8 text') from error

    def length(self):
        value = self.number('i')
        require(0 <= value <= 100_000, 'invalid NBT collection length')
        return value

    def payload(self, kind: int, depth: int = 0):
        self.nodes += 1
        require(depth <= 64 and self.nodes <= 100_000, 'NBT structure budget exceeded')
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
        if kind == 8:
            return self.text()
        if kind == 9:
            child, count = self.number('B'), self.length()
            require(0 <= child <= 12, 'unknown NBT list element tag')
            require(child != 0 or count == 0, 'nonempty END list')
            return [self.payload(child, depth + 1) for _ in range(count)]
        if kind == 10:
            result = {}
            while True:
                child = self.number('B')
                if child == 0:
                    return result
                key = self.text()
                require(key not in result, 'duplicate NBT compound key')
                result[key] = self.payload(child, depth + 1)
        if kind in (7, 11, 12):
            code = {7: 'b', 11: 'i', 12: 'q'}[kind]
            return [self.number(code) for _ in range(self.length())]
        raise AuditError('village: unknown NBT tag ' + str(kind))

    def read(self):
        require(self.number('B') == 10 and self.text() == '', 'expected unnamed root compound')
        result = self.payload(10)
        require(self.at == len(self.raw), 'trailing NBT data')
        return result


def structure_manifest(raw: bytes, biome: str) -> dict:
    require(biome in STRUCTURES, 'unknown biome')
    require(hashlib.sha256(raw).hexdigest() == STRUCTURES[biome], 'unsupported structure ' + biome)
    root = NbtReader(raw).read()
    require(set(root) == {'DataVersion', 'size', 'palette', 'blocks', 'entities'}, 'unknown structure root')
    require(root['DataVersion'] == 3465 and root['entities'] == [], 'unsupported structure version/entities')
    size, palette, blocks = root['size'], root['palette'], root['blocks']
    require(all(isinstance(value, list) for value in (size, palette, blocks)), 'invalid structure collections')
    require(len(size) == 3 and all(type(v) is int and 1 <= v <= 48 for v in size), 'invalid structure size')
    require(len(blocks) == size[0] * size[1] * size[2] and palette, 'incomplete source structure volume')
    custom, block_entities, positions = [], [], set()
    for state in palette:
        require(isinstance(state, dict) and set(state) <= {'Name', 'Properties'} and isinstance(state.get('Name'), str), 'bad palette state')
        require(isinstance(state.get('Properties', {}), dict) and all(isinstance(key, str) and isinstance(value, str)
                for key, value in state.get('Properties', {}).items()), 'bad palette properties')
        name = state['Name']
        require(name.startswith(('minecraft:', ART + ':')), 'unmapped source block ' + name)
        if name.startswith(ART + ':'):
            short = name.split(':')[1]
            props = state.get('Properties', {})
            allowed = ({'facing', 'lit'} if short == 'stove' else {'age'} if short == 'tomato_crop'
                       else {'sheared', 'is_head'} if short == 'chili_ristra' else set())
            require(allowed and set(props) == allowed, 'unsupported custom palette state ' + name)
            values = ({'facing': {'north', 'south', 'west', 'east'}, 'lit': {'true', 'false'}} if short == 'stove'
                      else {'age': {'7'}} if short == 'tomato_crop'
                      else {'sheared': {'true', 'false'}, 'is_head': {'true', 'false'}})
            require(all(value in values[key] for key, value in props.items()), 'invalid custom palette property value ' + name)
    for block in blocks:
        require(isinstance(block, dict) and set(block) <= {'state', 'pos', 'nbt'} and {'state', 'pos'} <= set(block), 'bad structure block')
        pos, index = block['pos'], block['state']
        require(type(index) is int and 0 <= index < len(palette), 'palette index out of bounds')
        require(isinstance(pos, list) and len(pos) == 3 and all(type(v) is int and 0 <= v < size[i] for i, v in enumerate(pos)), 'block outside volume')
        require(tuple(pos) not in positions, 'duplicate block position')
        positions.add(tuple(pos))
        if palette[index]['Name'].startswith(ART + ':'):
            custom.append({'position': pos, **palette[index]})
        if 'nbt' in block:
            require(isinstance(block['nbt'], dict), 'invalid block-entity NBT')
            block_entities.append({'position': pos, 'block': palette[index], 'nbt': block['nbt']})
    return {'sourceSha256': STRUCTURES[biome], 'dataVersion': root['DataVersion'], 'size': size,
            'bounds': {'min': [0, 0, 0], 'max': [value - 1 for value in size]},
            'blocks': len(blocks), 'palette': palette, 'customBlocks': custom, 'blockEntities': block_entities,
            'pool': 'minecraft:village/' + biome + '/houses', 'weight': 4, 'projection': 'rigid',
            'elementType': 'minecraft:legacy_single_pool_element', 'template': PLUGIN + ':village/houses/' + biome + '_kitchen',
            'processor': PLUGIN + ':crop_replace', 'runtime': 'NOT_IMPLEMENTED'}


def expected_loot(hidden: bool) -> dict:
    entries = []
    for name, weight, lower, upper in HIDDEN if hidden else NORMAL:
        entry = {'type': 'minecraft:item', 'name': ART + ':' + name, 'weight': weight}
        if lower != upper:
            entry['functions'] = [{'function': 'minecraft:set_count', 'add': False,
                'count': {'type': 'minecraft:uniform', 'min': float(lower), 'max': float(upper)}}]
        if name == 'recipe_item':
            entry['functions'] = [{'function': ART + ':recipe_randomly'}]
        entries.append(entry)
    name = TABLES[int(hidden)]
    return {'type': 'minecraft:chest', 'pools': [{'bonus_rolls': 0.0, 'entries': entries,
            'rolls': {'type': 'minecraft:uniform', 'min': float(2 if hidden else 3), 'max': float(3 if hidden else 8)}}],
            'random_sequence': ART + ':chest/' + name}


def recipe_choices(java: bytes, sources: RecipeSources) -> list[dict]:
    foods = literal_registrations(java.decode('utf8'), 'registry.registerFoodData')
    require(len(foods) == 37, 'unsupported FoodBite candidate count')
    choices = []
    for index, row in enumerate(foods):
        key = row['id']
        choice = {'index': index, 'sourceResult': key, 'result': mapped(key), 'recipe': None}
        for kind in ('pot', 'stockpot'):
            matches = [(recipe_id, doc) for recipe_id, doc in sources.recipes.items()
                       if doc['type'] == ART + ':' + kind and doc['result']['item'] == key]
            # Current pinned candidates have at most one exact recipe of each type.
            # Refuse to invent recipe-manager iteration ordering if the source changes.
            require(len(matches) <= 1, 'ambiguous first recipe for ' + key)
            if matches:
                recipe_id, recipe = matches[0]
                ingredients = [sources.expand(raw)[0] for raw in recipe['ingredients']]
                require(1 <= len(ingredients) <= 9, 'invalid recipe ingredient count')
                choice['recipe'] = {'id': mapped(recipe_id), 'type': kind, 'ingredients': ingredients,
                    'result': mapped(recipe['result']['item']), 'resultCount': recipe['result'].get('count', 1),
                    'soupBase': SOUP_BUCKETS[recipe['soup_base']] if kind == 'stockpot' else None,
                    'sourceSoupBase': recipe.get('soup_base'), 'source': sources.origins[recipe_id]}
                break
        choices.append(choice)
    return choices


def token(value: str) -> dict:
    # PDC string is carried through the vanilla custom-data component, not a fake named item.
    encoded = json.dumps(TOKEN_KEY) + ':' + json.dumps(TOKEN_PREFIX + value)
    return {'function': 'minecraft:set_custom_data', 'tag': '{PublicBukkitValues:{' + encoded + '}}'}


def native_loot(source: dict, choices: list[dict]) -> dict:
    result = copy.deepcopy(source)
    # Preserve random_sequence, pool/entry order, all weights/count functions and original rolls.
    for entry in result['pools'][0]['entries']:
        name = entry['name']
        if name == ART + ':recipe_item':
            entry.pop('name'); entry['type'] = 'minecraft:loot_table'
            entry['value'] = PLUGIN + ':chest/recipe_randomly'
            entry.pop('functions')
        else:
            entry['name'] = 'minecraft:paper'
            entry.setdefault('functions', []).insert(0, token('item|' + name))
    return result


def export(source: Path) -> tuple[dict[str, bytes], dict]:
    paths = list(RULE_HASHES) + [RES + 'structures/village/houses/' + name + '_kitchen.nbt' for name in BIOMES]
    paths += [GEN + 'loot_tables/chest/' + name + '.json' for name in TABLES]
    files = read_blobs(source, COMMIT, paths)
    for path, digest in RULE_HASHES.items():
        require(hashlib.sha256(files[path]).hexdigest() == digest, 'unsupported source rules ' + path)
    structures = {name: structure_manifest(files[RES + 'structures/village/houses/' + name + '_kitchen.nbt'], name) for name in BIOMES}
    sources = read_sources(source)
    choices = recipe_choices(files[JAVA + 'init/registry/FoodBiteRegistry.java'], sources)
    tables = {}
    for name in TABLES:
        original = json.loads(files[GEN + 'loot_tables/chest/' + name + '.json'], object_pairs_hook=unique_object)
        require(original == expected_loot(name == TABLES[1]), 'unsupported loot rules ' + name)
        tables[name] = native_loot(original, choices)
    nested = {'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'minecraft:paper',
        'weight': 1, 'functions': [token('recipe|' + str(row['index']))]} for row in choices]}]}
    # Register only the stable named native holder. CE states cannot be encoded here: their
    # allocation exists only after CE configuration load. A separate guarded runtime publication
    # must install the real rules before any kitchen can enter a natural generation pool.
    placeholder = json_bytes({'processors': []})
    manifest = {'schemaVersion': 1, 'sourceCommit': COMMIT,
        'sourceFiles': {path: hashlib.sha256(raw).hexdigest() for path, raw in files.items()},
        'recipeSourceFiles': sources.files,
        'target': {'minecraft': '1.21.11', 'dataPackVersion': [94, 1]},
        'structures': structures, 'recipes': choices,
        'itemMappings': {ART + ':' + name: mapped(ART + ':' + name) for name, *_ in NORMAL + HIDDEN if name != 'recipe_item'},
        'tables': {PLUGIN + ':chest/' + name: name for name in TABLES},
        'tokenKey': TOKEN_KEY, 'tokenPrefix': TOKEN_PREFIX,
        'nativeProcessorRegistration': {'key': PROCESSOR_KEY, 'path': PROCESSOR_PATH,
            'sha256': hashlib.sha256(placeholder).hexdigest(), 'mode': 'DORMANT_EMPTY_PLACEHOLDER',
            'installation': 'PREINSTALL_AND_ENABLE_BEFORE_NATIVE_WORLDGEN_REGISTRY_LOAD',
            'runtimeRules': 'NOT_PUBLISHED', 'sourceRules': 'source/crop_replace.json'},
        'naturalVillageIntegration': 'NOT_IMPLEMENTED', 'runtime': 'NOT_TESTED',
        'open': ['Five natural pools/templates/runtime processor rules and reload barrier are not installed by this loot pack.',
                 'The empty named processor requires this pack enabled before native registry load; no automatic installation or activation is provided.',
                 'Native1.21.11 codec/RNG, Bukkit token bridge, once-only chest and recipe player flows require runtime acceptance.',
                 'Ingredient first alternatives use the pinned target tag expansion; external Forge mods/tags are unavailable.',
                 'Native1.21.11 RNG algorithms are used; cross-version1.20.1 seed equivalence is not claimed.']}
    output = {'pack.mcmeta': json_bytes({'pack': {'description': 'SenLuo pinned village loot; requires Cookery plugin bridge',
                'min_format': [94, 1], 'max_format': [94, 1]}}), 'village-manifest.json': json_bytes(manifest)}
    output[PROCESSOR_PATH] = placeholder
    for name, table in tables.items():
        output['data/' + PLUGIN + '/loot_table/chest/' + name + '.json'] = json_bytes(table)
    output['data/' + PLUGIN + '/loot_table/chest/recipe_randomly.json'] = json_bytes(nested)
    for name in BIOMES:
        path = RES + 'structures/village/houses/' + name + '_kitchen.nbt'
        output['source/structure/' + name + '_kitchen.nbt'] = files[path]
    output['source/crop_replace.json'] = files[RES + 'worldgen/processor_list/crop_replace.json']
    return output, manifest
