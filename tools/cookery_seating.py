"""Export all pinned wooden chairs/stools with real CE seats and chair cushions."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from audit_upstream import AuditError, read_blobs
from export_cookery_catalog import COMMIT
from cookery_models import model_node, model_path

ART = 'kaleidoscope_cookery'
PLUGIN = 'kaleidoscopecookery'
WOODS = ('oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove',
         'cherry', 'bamboo', 'crimson', 'warped')
COLORS = ('white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
          'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black')
JAVA = 'src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/'
SOURCES = (JAVA + 'block/decoration/ChairBlock.java', JAVA + 'block/decoration/CookStoolBlock.java',
           JAVA + 'entity/SitEntity.java', JAVA + 'client/render/block/ChairBlockEntityRender.java')


def seating_furniture(source: Path, assets: dict[str, bytes], items: dict, definitions: dict,
                      names: dict) -> tuple[dict, dict]:
    source_files = read_blobs(source, COMMIT, list(SOURCES))
    furniture = {}
    for family in ('chair', 'cook_stool'):
        source_seat_y = .5125 if family == 'chair' else .4375
        # Source SitEntity has -0.25 passenger offset; CE adds 0.6 before spawning
        # its zero-sized item-display seat. Keep the intended passenger anchor.
        seat_offset = source_seat_y - .25 - .6
        for wood in WOODS:
            name = family + '_' + wood
            key = PLUGIN + ':' + name
            source_model = ART + ':block/' + family + '/' + wood
            if key in items or ART + ':item/' + name not in definitions:
                raise AuditError('seating: missing or duplicate item ' + key)
            state_path = ART + '/blockstates/' + name + '.json'
            if state_path not in assets or model_path(source_model) not in assets:
                raise AuditError('seating: missing source model/state ' + name)
            states = json.loads(assets[state_path])
            # No hidden geometry branches may be flattened into a single model.
            for selector, variant in states['variants'].items():
                facing = dict(p.split('=') for p in selector.split(','))['facing']
                expected = {'model': source_model}
                rotation = ('north', 'east', 'south', 'west').index(facing) * 90
                if rotation:
                    expected['y'] = rotation
                if variant != expected:
                    raise AuditError('seating: unhandled model variant ' + name + '/' + selector)
            items[key] = {'material': 'paper',
                          'data': {'item_name': '<!i>' + names['block.' + ART + '.' + name]},
                          'model': definitions[ART + ':item/' + name],
                          'behavior': {'type': 'furniture_item', 'furniture': key,
                                       'rules': {'ground': {'rotation': 'four', 'alignment': 'center'}}}}
            hitbox = {'type': 'interaction', 'position': '0,0,0', 'width': .75,
                      'height': 1.1875 if family == 'chair' else .4375,
                      'interactive': True, 'blocks_building': True,
                      'seats': [f'0,{seat_offset:.4f},0 180 false']}
            value = {'settings': {'item': key, 'hit_times': 3,
                                  'sounds': {'place': 'minecraft:block.wood.place',
                                             'break': 'minecraft:block.wood.break', 'hit': 'minecraft:block.wood.hit'}},
                     'variants': {'ground': {
                         'elements': [{'item': key, 'display_transform': 'none', 'position': '0,0.5,0'}],
                         'hitboxes': [hitbox]}},
                     'loot': {'pools': [{'rolls': 1, 'entries': [{'type': 'item', 'item': key}]}]}}
            if family == 'chair':
                value['behavior'] = [{'type': PLUGIN + ':chair', 'carpet_offset': '0,0.5,0',
                                      'carpet_scale': 1, 'yaw_offset': 0}]
            furniture[key] = value
    for color in COLORS:
        key = 'show:chair_carpet_' + color
        model = ART + ':block/carpet/chair/' + color
        if key in items or model_path(model) not in assets:
            raise AuditError('seating: missing or duplicate cushion ' + color)
        items[key] = {'material': 'paper', 'data': {'item_name': '<!i>chair cushion ' + color},
                      'model': model_node(model)}
    report = {'sourceCommit': COMMIT, 'chairs': 11, 'stools': 11, 'cushions': 16,
              'sourceFiles': {path: hashlib.sha256(raw).hexdigest() for path, raw in source_files.items()},
              'seatCoordinates': {'chairSourceEntityY': .5125, 'stoolSourceEntityY': .4375,
                                  'sourcePassengerOffset': -.25, 'craftEngineSeatOffset': .6},
              'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
              'differences': ['Interaction hitboxes do not reproduce solid source voxel collision.',
                              'Waterlogging, fire spread and piston behavior of source blocks are not yet implemented.',
                              'CE seat rider offsets and four-direction placement require client acceptance.']}
    return furniture, report
