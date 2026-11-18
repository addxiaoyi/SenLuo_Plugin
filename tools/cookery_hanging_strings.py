"""Pinned hanging decorations, their four visual stages and conditional harvest loot."""
import hashlib
import json
from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_seating import ART, PLUGIN, JAVA

SOURCES = [JAVA + 'block/misc/' + name + '.java' for name in ('ChiliRistraBlock', 'StrungMushroomsBlock')]


def hanging_strings(source, assets, items, definitions, names, display):
    blocks = {}
    loot_paths = ['src/generated/resources/data/' + ART + '/loot_tables/blocks/' + n + '.json'
                  for n in ('chili_ristra', 'strung_mushrooms')]
    sources = read_blobs(source, COMMIT, SOURCES + loot_paths)
    for name, harvest, color, loot_path in zip(('chili_ristra', 'strung_mushrooms'),
            (PLUGIN + ':red_chili', 'minecraft:brown_mushroom'), ('red', 'brown'), loot_paths):
        key = PLUGIN + ':' + name
        expected = {f'is_head={head},sheared={sheared}': {'model': ART + ':block/' + name + '/'
                    + ('head' if head == 'true' else 'body') + ('_sheared' if sheared == 'true' else '')}
                    for head in ('false', 'true') for sheared in ('false', 'true')}
        if json.loads(assets[ART + '/blockstates/' + name + '.json']) != {'variants': expected}:
            raise AuditError('hanging string: unsupported source states ' + name)
        upstream_item = harvest.replace(PLUGIN + ':', ART + ':')
        children = [{'type': 'minecraft:item', 'functions': [{'add': False, 'count': float(count),
                     'function': 'minecraft:set_count'}], 'name': upstream_item} for count in (3, 6)]
        children[0]['conditions'] = [{'block': ART + ':' + name, 'condition': 'minecraft:block_state_property',
                                      'properties': {'sheared': 'true'}}]
        expected_loot = {'type': 'minecraft:block', 'pools': [{'bonus_rolls': 0.0,
            'conditions': [{'condition': 'minecraft:survives_explosion'}],
            'entries': [{'type': 'minecraft:alternatives', 'children': children}], 'rolls': 1.0}],
            'random_sequence': ART + ':blocks/' + name}
        if json.loads(sources[loot_path]) != expected_loot:
            raise AuditError('hanging string: unsupported source loot ' + name)
        if key in items: raise AuditError('hanging string: duplicate item ' + key)
        items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['block.' + ART + '.' + name]},
                      'model': definitions[ART + ':item/' + name], 'behavior': {'type': 'block_item', 'block': key}}
        appearances, variants = {}, {}
        for selector, model in expected.items():
            app = 's' + str(len(appearances))
            appearances[app] = {'auto_state': {'type': 'higher_tripwire', 'id': 'cookery_kitchen_clear'},
                                'transparent': True, 'entity_renderer': display(assets, items, model['model'])}
            variants[selector] = {'appearance': app}
        pools = [{'rolls': 1, 'conditions': [{'type': 'survives_explosion'},
                    {'type': 'match_block_property', 'properties': {'sheared': sheared}}],
                  'entries': [{'type': 'item', 'item': harvest,
                               'functions': [{'type': 'set_count', 'count': count}]}]}
                 for sheared, count in [('false', 6), ('true', 3)]]
        blocks[key] = {'settings': {'item': key, 'hardness': 0, 'resistance': 0, 'map_color': color,
                          'push_reaction': 'destroy', 'can_occlude': False,
                          'is_suffocating': False, 'is_view_blocking': False,
                          'sounds': {k: 'minecraft:block.grass.' + k for k in ('break', 'place', 'step', 'hit', 'fall')}},
                       'behavior': [{'type': PLUGIN + ':hanging_string', 'harvest_item': harvest, 'harvest_amount': 3}],
                       'loot': {'pools': pools}, 'states': {'properties': {
                           'is_head': {'type': 'boolean', 'default': True},
                           'sheared': {'type': 'boolean', 'default': False}},
                           'appearances': appearances, 'variants': variants}}
    return blocks, {'sourceCommit': COMMIT, 'blocks': 2, 'recipes': 2,
        'sourceFiles': {p: hashlib.sha256(v).hexdigest() for p, v in sources.items()},
        'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
        'differences': ['Tripwire selection shape does not match the source head/body selection boxes; collision is empty in both.',
                        'Block particles use CE block data; source textures and range need client acceptance.',
                        'Damage events, support cascades, piston destruction and explosion loot need server acceptance.']}
