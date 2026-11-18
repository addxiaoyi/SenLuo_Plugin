"""Oil and straw storage blocks with pinned source physics, models and recipes."""
import hashlib
import json
from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_seating import ART, PLUGIN, JAVA

SOURCES = [JAVA + 'block/misc/OilBlock.java', JAVA + 'block/misc/StrawBlocks.java']

def storage_blocks(source, assets, items, definitions, names):
    blocks = {}
    for name in ('oil_block', 'straw_block'):
        key = PLUGIN + ':' + name
        model = ART + ':block/' + name
        expected = {'': {'model': model}} if name == 'oil_block' else {
            'axis=x': {'model': model + '_horizontal', 'x': 90, 'y': 90},
            'axis=y': {'model': model},
            'axis=z': {'model': model + '_horizontal', 'x': 90}}
        states = json.loads(assets[ART + '/blockstates/' + name + '.json'])
        if states != {'variants': expected}:
            raise AuditError('storage block: unsupported source states ' + name)
        if key in items: raise AuditError('storage block: duplicate item ' + key)
        items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['block.' + ART + '.' + name]},
                      'model': definitions[ART + ':item/' + name], 'behavior': {'type': 'block_item', 'block': key}}
        properties = {} if name == 'oil_block' else {'axis': {'type': 'axis', 'default': 'y'}}
        appearances, variants = {}, {}
        for index, (selector, value) in enumerate(expected.items()):
            app = 's' + str(index)
            appearances[app] = {'auto_state': 'note_block',
                                'model': {('path' if k == 'model' else k): v for k, v in value.items()}}
            variants[selector] = {'appearance': app}
        sound = 'slime_block' if name == 'oil_block' else 'grass'
        settings = {'item': key, 'hardness': 0 if name == 'oil_block' else .5,
                    'resistance': 0 if name == 'oil_block' else .5,
                    'sounds': {k: 'minecraft:block.' + sound + '.' + k for k in ('break', 'place', 'step', 'hit', 'fall')}}
        if name == 'oil_block':
            settings.update({'friction': .985, 'can_occlude': False, 'map_color': 'pale_purple',
                             'tags': ['minecraft:infiniburn_' + d for d in ('overworld', 'nether', 'end')]})
        else:
            settings.update({'map_color': 'yellow', 'instrument': 'banjo', 'tags': ['minecraft:mineable/hoe']})
            items[key]['settings'] = {'tags': [PLUGIN + ':straw_bale']}
        blocks[key] = {'settings': settings, 'behavior': [{'type': key}],
                       'loot': {'pools': [{'rolls': 1, 'entries': [{'type': 'item', 'item': key}]}]},
                       'states': {'properties': properties, 'appearances': appearances, 'variants': variants}}
    sources = read_blobs(source, COMMIT, SOURCES)
    return blocks, {'sourceCommit': COMMIT, 'blocks': 2, 'recipes': 4,
        'sourceFiles': {p: hashlib.sha256(v).hexdigest() for p, v in sources.items()},
        'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
        'differences': ['Oil server friction is 0.985; vanilla-client movement on its note-block appearance still needs reconciliation.',
                        'Oil rejects natural/chunk/patrol/raid/reinforcement spawns through Bukkit; other spawn-placement paths remain unverified.',
                        'Oil ambient particles run on a server ticker instead of client animateTick.',
                        'Oil cannot yet opt out of slime/honey adhesion while permitting normal piston pushes.',
                        'Straw fall destruction adds protection and EntityChangeBlockEvent cancellation; emitted drops need third-party cancellation acceptance.']}
