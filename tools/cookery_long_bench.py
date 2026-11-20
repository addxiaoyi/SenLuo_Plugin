"""Pinned long-bench geometry, one seat per block and source crafting/loot."""
import hashlib
import json
from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_seating import ART, PLUGIN, JAVA

SOURCES = [JAVA + 'block/decoration/LongBenchBlock.java', JAVA + 'entity/SitEntity.java',
           'src/generated/resources/data/' + ART + '/loot_tables/blocks/long_bench.json']


def long_bench(source, assets, items, definitions, names, display):
    key = PLUGIN + ':long_bench'
    expected = {}
    for axis in ('x', 'z'):
        for position, shape in enumerate(('single', 'left', 'middle', 'right')):
            model = {'model': ART + ':block/long_bench/' + shape}
            if axis == 'z': model['y'] = 90
            expected[f'axis={axis},position={position}'] = model
    if json.loads(assets[ART + '/blockstates/long_bench.json']) != {'variants': expected}:
        raise AuditError('long bench: unsupported source states')
    sources = read_blobs(source, COMMIT, SOURCES)
    expected_loot = {'type': 'minecraft:block', 'pools': [{'bonus_rolls': 0.0,
        'conditions': [{'condition': 'minecraft:survives_explosion'}],
        'entries': [{'type': 'minecraft:item', 'name': ART + ':long_bench'}], 'rolls': 1.0}],
        'random_sequence': ART + ':blocks/long_bench'}
    if json.loads(sources[SOURCES[-1]]) != expected_loot:
        raise AuditError('long bench: unsupported source loot')
    if key in items: raise AuditError('long bench: duplicate item')
    items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['block.' + ART + '.long_bench']},
                  'model': definitions[ART + ':item/long_bench'], 'behavior': {'type': 'block_item', 'block': key}}
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
                  'sounds': {k: 'minecraft:block.wood.' + k for k in ('break', 'place', 'step', 'hit', 'fall')}},
             'behavior': [{'type': PLUGIN + ':long_bench'}],
             'loot': {'pools': [{'rolls': 1, 'conditions': [{'type': 'survives_explosion'}],
                                'entries': [{'type': 'item', 'item': key}]}]},
             'states': {'properties': {'axis': {'type': 'axis', 'values': ['x', 'z'], 'default': 'x'},
                                       'position': {'type': 'int', 'range': '0~3', 'default': 0}},
                        'appearances': appearances, 'variants': variants}}
    return {key: block}, {'sourceCommit': COMMIT, 'blocks': 1, 'recipes': 1, 'seatsPerBlock': 1,
        'sourceFiles': {p: hashlib.sha256(v).hexdigest() for p, v in sources.items()},
        'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
        'differences': ['Tripwire backing does not reproduce the solid half-height bench collision/selection shape.',
                        'Piston movement is blocked until seat migration is implemented; source allows movement.',
                        'Source lava ignition is not implemented.',
                        'CE removes empty seats immediately instead of the source ten-tick grace period.',
                        'Client riding height, neighbor update ordering and Folia boundary behavior need runtime acceptance.']}
