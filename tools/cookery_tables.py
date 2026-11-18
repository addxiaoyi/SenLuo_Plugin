"""Source-validated connected table blocks and their 64 carpet overlays."""
import hashlib
import json
from pathlib import Path
from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_models import model_node, model_path
from cookery_seating import WOODS, COLORS, ART, PLUGIN, JAVA

SOURCES = [JAVA + 'block/decoration/TableBlock.java',
           JAVA + 'blockentity/decoration/TableBlockEntity.java',
           JAVA + 'client/render/block/TableBlockEntityRender.java']


def table_blocks(source: Path, assets: dict, items: dict, definitions: dict, names: dict, display):
    blocks = {}
    for wood in WOODS:
        name = 'table_' + wood
        key = PLUGIN + ':' + name
        state = json.loads(assets[ART + '/blockstates/' + name + '.json'])
        models = {}
        for axis in ('x', 'z'):
            for position in range(4):
                suffix = ('single', 'right', 'middle', 'left')[position]
                if axis == 'z' and position: suffix += '_rot'
                expected = {'model': ART + ':block/table/' + wood + '_' + suffix}
                for carpet in ('false', 'true'):
                    for water in ('false', 'true'):
                        selector = f'axis={axis},has_carpet={carpet},position={position},waterlogged={water}'
                        if state['variants'].get(selector) != expected:
                            raise AuditError('table: unsupported source geometry ' + name + '/' + selector)
                models[axis, position] = expected['model']
        if len(state['variants']) != 32: raise AuditError('table: unexpected state branches ' + name)
        if key in items or key in blocks: raise AuditError('table: duplicate ' + key)
        items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names['block.' + ART + '.' + name]},
                      'model': definitions[ART + ':item/' + name],
                      'behavior': {'type': 'block_item', 'block': key}}
        appearances, variants = {}, {}
        for line, axis in enumerate(('x', 'z')):
            for position in range(4):
                variant = f'line={line},position={position}'
                app = f'{axis}_{position}'
                appearances[app] = {'auto_state': {'type': 'higher_tripwire', 'id': 'cookery_kitchen_clear'},
                                    'transparent': True,
                                    'entity_renderer': display(assets, items, models[axis, position])}
                variants[variant] = {'appearance': app}
        blocks[key] = {'settings': {'item': key, 'hardness': 2, 'resistance': 3,
                          'push_reaction': 'block', 'can_occlude': False,
                          'is_suffocating': False, 'is_view_blocking': False,
                          'sounds': {kind: 'minecraft:block.wood.' + kind
                                     for kind in ('break', 'place', 'step', 'hit', 'fall')}},
                       'behavior': [{'type': PLUGIN + ':table'}],
                       'loot': {'pools': [{'rolls': 1, 'entries': [{'type': 'item', 'item': key}]}]},
                       'states': {'properties': {
                           'line': {'type': 'int', 'range': '0~1', 'default': 1},
                           'position': {'type': 'int', 'range': '0~3', 'default': 0}},
                           'appearances': appearances, 'variants': variants}}
    for color in COLORS:
        for shape in ('single', 'left', 'middle', 'right'):
            key = f'show:table_carpet_{color}_{shape}'
            model = f'{ART}:block/carpet/table/{color}_{shape}'
            if key in items or model_path(model) not in assets:
                raise AuditError('table: missing or duplicate carpet ' + key)
            items[key] = {'material': 'paper', 'model': model_node(model)}
    sources = read_blobs(source, COMMIT, SOURCES)
    report = {'sourceCommit': COMMIT, 'tables': 11, 'carpetModels': 64, 'slots': 4,
              'sourceFiles': {path: hashlib.sha256(raw).hexdigest() for path, raw in sources.items()},
              'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
              'differences': ['Tripwire backing does not reproduce the solid top-slab collision.',
                              'Source waterlogging, fire behavior and piston movement are not implemented.',
                              'Source Forge NBT is not imported; new plugin item snapshots preserve current components.',
                              'Player interaction, connection update ordering and display placement need runtime acceptance.']}
    return blocks, report
