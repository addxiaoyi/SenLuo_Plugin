"""Pinned four-part table geometry and source crafting/loot."""
import hashlib
import json
from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_seating import ART, PLUGIN, JAVA

SOURCES = [JAVA + 'block/decoration/EightImmortalsTableBlock.java',
           'src/generated/resources/data/' + ART + '/loot_tables/blocks/eight_immortals_table.json']


def eight_immortals_table(source, assets, items, definitions, names, display):
    key = PLUGIN + ':eight_immortals_table'
    expected = {}
    for facing, yaw in [('north', 180), ('east', 270), ('south', 0), ('west', 90)]:
        for part in ('right_bottom', 'left_bottom', 'right_top', 'left_top'):
            model = {'model': ART + ':block/eight_immortals_table/' + part}
            if yaw: model['y'] = yaw
            expected[f'facing={facing},part={part}'] = model
    if json.loads(assets[ART + '/blockstates/eight_immortals_table.json']) != {'variants': expected}:
        raise AuditError('eight immortals table: unsupported source states')
    sources = read_blobs(source, COMMIT, SOURCES)
    expected_loot = {'type': 'minecraft:block', 'pools': [{'bonus_rolls': 0.0,
        'conditions': [{'condition': 'minecraft:survives_explosion'}],
        'entries': [{'type': 'minecraft:item', 'name': ART + ':eight_immortals_table'}], 'rolls': 1.0}],
        'random_sequence': ART + ':blocks/eight_immortals_table'}
    if json.loads(sources[SOURCES[-1]]) != expected_loot:
        raise AuditError('eight immortals table: unsupported source loot')
    if key in items: raise AuditError('eight immortals table: duplicate item')
    items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['block.' + ART + '.eight_immortals_table']},
                  'model': definitions[ART + ':item/eight_immortals_table'], 'behavior': {'type': PLUGIN + ':eight_immortals_table_item', 'block': key}}
    appearances, variants = {}, {}
    for selector, model in expected.items():
        app = 's' + str(len(appearances))
        appearances[app] = {'auto_state': {'type': 'higher_tripwire', 'id': 'cookery_kitchen_clear'},
                            'transparent': True,
                            'entity_renderer': display(assets, items, model['model'], model.get('y', 0))}
        variants[selector] = {'appearance': app}
    block = {'settings': {'item': key, 'hardness': 2, 'resistance': 3,
                  'push_reaction': 'block', 'can_occlude': False,
                  'is_suffocating': False, 'is_view_blocking': False,
                  'sounds': {k: 'minecraft:block.bamboo_wood.' + k for k in ('break', 'place', 'step', 'hit', 'fall')}},
             'behavior': [{'type': PLUGIN + ':eight_immortals_table'}],
             'loot': {'pools': [{'rolls': 1, 'conditions': [{'type': 'survives_explosion'}],
                                'entries': [{'type': 'item', 'item': key}]}]},
             'states': {'properties': {'facing': {'type': 'horizontal_direction', 'default': 'north'},
                                       'part': {'type': 'string', 'values': ['right_bottom', 'left_bottom', 'right_top', 'left_top'], 'default': 'right_bottom'}},
                        'appearances': appearances, 'variants': variants}}
    return {key: block}, {'sourceCommit': COMMIT, 'blocks': 1, 'recipes': 1, 'partsPerTable': 4,
        'sourceFiles': {p: hashlib.sha256(v).hexdigest() for p, v in sources.items()},
        'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
        'differences': ['Tripwire backing does not reproduce the full selection cube or solid top-quarter collision.',
                        'Source lava ignition is not implemented.',
                        'Placement requires all four cells to be loaded and owned by the current region.',
                        'Deferred removal skips unloaded cells; reconciliation waits until the entire footprint is loaded and owned.',
                        'Protection, native explosion loot, reload and Folia region splitting still require server/client acceptance.']}
