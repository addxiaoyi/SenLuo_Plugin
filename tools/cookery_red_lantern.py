"""Pinned red-lantern appearance, lighting, sounds and crafting/loot."""
import hashlib
import json
from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_seating import ART, PLUGIN, JAVA

SOURCES = [JAVA + 'block/decoration/RedLanternBlock.java',
           'src/generated/resources/data/' + ART + '/loot_tables/blocks/red_lantern.json']


def red_lantern(source, assets, items, definitions, names, display):
    key = PLUGIN + ':red_lantern'
    model = ART + ':block/red_lantern'
    if json.loads(assets[ART + '/blockstates/red_lantern.json']) != {'variants': {'': {'model': model}}}:
        raise AuditError('red lantern: unsupported source states')
    sources = read_blobs(source, COMMIT, SOURCES)
    expected_loot = {'type': 'minecraft:block', 'pools': [{'bonus_rolls': 0.0,
        'conditions': [{'condition': 'minecraft:survives_explosion'}],
        'entries': [{'type': 'minecraft:item', 'name': ART + ':red_lantern'}], 'rolls': 1.0}],
        'random_sequence': ART + ':blocks/red_lantern'}
    if json.loads(sources[SOURCES[-1]]) != expected_loot:
        raise AuditError('red lantern: unsupported source loot')
    if key in items: raise AuditError('red lantern: duplicate item')
    items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['block.' + ART + '.red_lantern']},
                  'model': definitions[ART + ':item/red_lantern'], 'behavior': {'type': 'block_item', 'block': key}}
    block = {'settings': {'item': key, 'hardness': .3, 'resistance': .3, 'luminance': 14,
                  'map_color': 'red', 'push_reaction': 'normal', 'can_occlude': False,
                  'is_suffocating': False, 'is_view_blocking': False,
                  'sounds': {k: 'minecraft:block.wool.' + k for k in ('break', 'place', 'step', 'hit', 'fall')}},
             'loot': {'pools': [{'rolls': 1, 'conditions': [{'type': 'survives_explosion'}],
                                'entries': [{'type': 'item', 'item': key}]}]},
             'states': {'properties': {}, 'appearances': {'default': {
                 'auto_state': {'type': 'higher_tripwire', 'id': 'cookery_kitchen_clear'}, 'transparent': True,
                 'entity_renderer': display(assets, items, model)}}, 'variants': {'': {'appearance': 'default'}}}}
    return {key: block}, {'sourceCommit': COMMIT, 'blocks': 1, 'recipes': 1,
        'sourceFiles': {p: hashlib.sha256(v).hexdigest() for p, v in sources.items()},
        'sourceShape': [0, 0, 0, 16, 12, 16], 'lightLevel': 14,
        'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
        'differences': ['Tripwire backing does not reproduce the solid three-quarter-height collision/selection shape.',
                        'Light propagation, piston display relocation and client rendering require runtime acceptance.']}
