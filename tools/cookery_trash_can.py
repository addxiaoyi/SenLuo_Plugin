"""Convert the pinned Java trash-can mesh, including its zero-thickness handle and eyes."""
from __future__ import annotations

import hashlib
import itertools
import json
import re
from pathlib import Path

from audit_upstream import AuditError, read_blobs, uncomment_java
from export_cookery_catalog import COMMIT, arguments, chain, number
from cookery_millstone import Cube, Part, SOURCE_FACES, FACE_CORNERS
from cookery_kitchen_displays import display_item

ART = 'kaleidoscope_cookery'
PLUGIN = 'kaleidoscopecookery'
JAVA = 'src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/'
MODEL_SOURCE = JAVA + 'client/model/TrashCanModel.java'
SOURCES = (MODEL_SOURCE, JAVA + 'block/misc/TrashCanBlock.java',
           JAVA + 'blockentity/misc/TrashCanBlockEntity.java',
           JAVA + 'client/render/block/TrashCanBlockEntityRender.java',
           JAVA + 'client/animation/TrashCanAnimation.java')


def parse_mesh(source: str) -> dict[str, Part]:
    match = re.search(r'public static LayerDefinition createBodyLayer\(\)\s*\{([^{}]+)\}', uncomment_java(source))
    if not match:
        raise AuditError('trash can: unknown mesh factory')
    statements = [row.strip() for row in match[1].split(';') if row.strip()]
    if statements[:2] != ['MeshDefinition meshdefinition = new MeshDefinition()',
                          'PartDefinition partdefinition = meshdefinition.getRoot()'] or statements[-1] != 'return LayerDefinition.create(meshdefinition, 64, 64)':
        raise AuditError('trash can: unknown mesh root/texture size')
    parts = {}
    for statement in statements[2:-1]:
        declaration = re.fullmatch(r'PartDefinition (\w+) = (\w+)\.addOrReplaceChild\((.*)\)', statement, re.S)
        if not declaration:
            raise AuditError('trash can: unknown part')
        name, parent, params = declaration.groups()
        args = arguments(params)
        prefix = 'CubeListBuilder.create()'
        if len(args) != 3 or args[0] != '"' + name + '"' or name in parts or not args[1].startswith(prefix):
            raise AuditError('trash can: unknown builder')
        uv, cubes = None, []
        for method, values in chain(args[1][len(prefix):]):
            if method == 'texOffs' and len(values) == 2:
                uv = tuple(number(v) for v in values)
            elif method == 'addBox' and len(values) == 7 and uv is not None and values[6] == 'new CubeDeformation(0.0F)':
                size = tuple(number(v) for v in values[3:6])
                if sum(v > 0 for v in size) < 2 or any(v < 0 for v in size):
                    raise AuditError('trash can: degenerate cube')
                cubes.append(Cube(uv, tuple(number(v) for v in values[:3]), size, 0))
            else:
                raise AuditError('trash can: unsupported cube call ' + method)
        pose = re.fullmatch(r'PartPose\.(offset|offsetAndRotation)\(([^()]+)\)', args[2])
        if not pose:
            raise AuditError('trash can: unsupported pose')
        values = tuple(number(v) for v in arguments(pose[2]))
        if len(values) != (3 if pose[1] == 'offset' else 6):
            raise AuditError('trash can: invalid pose')
        parts[name] = Part(parent, values[:3], values[3:] if len(values) == 6 else (0, 0, 0), tuple(cubes))
    expected = {'root': ('partdefinition', 0), 'bone': ('root', 1), 'bone2': ('root', 1),
                'cube_r1': ('bone2', 3), 'eye': ('root', 1), 'bone3': ('eye', 1)}
    if {name: (p.parent, len(p.cubes)) for name, p in parts.items()} != expected:
        raise AuditError('trash can: unknown part hierarchy')
    for name, part in parts.items():
        rotation = (0, -1.5708, 0) if name == 'cube_r1' else (0, 0, 0)
        if part.rotation != rotation:
            raise AuditError('trash can: unsupported rotation')
    return parts


def mesh_cube(cube: Cube, name: str, parts: dict[str, Part], *, mirrored: bool = False,
              texture_size: int = 64, render_y: float = 24) -> dict:
    def transform(point):
        x, y, z = point
        current = name
        while current != 'partdefinition':
            part = parts[current]
            if part.rotation != (0, 0, 0):
                x, z = -z, x  # source's -90-degree handle rotation
            x, y, z = (a + b for a, b in zip((x, y, z), part.offset, strict=True))
            current = part.parent
        # Source renderer facing south: translate(.5,1.5,.5), Z(-180), Y(-180).
        # Item display's center is at .5 block; model coordinates are 0..16.
        return tuple(round(v, 6) for v in (8 + x, render_y - y, 8 - z))

    lo = cube.origin
    hi = tuple(a + b for a, b in zip(lo, cube.size, strict=True))
    vertices = [transform(tuple((lo, hi)[1-bit if mirrored and axis==0 else bit][axis] for axis, bit in enumerate(corner)))
                for corner in ((0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0),
                               (0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1))]
    low = [min(p[i] for p in vertices) for i in range(3)]
    high = [max(p[i] for p in vertices) for i in range(3)]
    u, v = cube.uv
    dx, dy, dz = cube.size
    rects = ((u + dz, v, u + dz + dx, v + dz), (u + dz + dx, v + dz, u + dz + 2 * dx, v),
             (u, v + dz, u + dz, v + dz + dy), (u + dz, v + dz, u + dz + dx, v + dz + dy),
             (u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy),
             (u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy))
    faces = {}
    for indices, rect in zip(SOURCE_FACES, rects, strict=True):
        winding = tuple(reversed(indices)) if mirrored else indices
        a, b, c = (vertices[index] for index in winding[:3])
        ab, ac = [b[i] - a[i] for i in range(3)], [c[i] - a[i] for i in range(3)]
        normal = (ab[1]*ac[2]-ab[2]*ac[1], ab[2]*ac[0]-ab[0]*ac[2], ab[0]*ac[1]-ab[1]*ac[0])
        if not any(normal):
            continue  # zero-area sides of source planes are never rendered
        axis = next(i for i in range(3) if normal[i])
        face = (('west', 'east'), ('down', 'up'), ('north', 'south'))[axis][normal[axis] > 0]
        u0, v0, u1, v1 = rect
        points = {vertices[index]: uv for index, uv in zip(indices, ((u1,v0),(u0,v0),(u0,v1),(u1,v1)), strict=True)}
        expected = [points[tuple((low,high)[bit][i] for i,bit in enumerate(corner))] for corner in FACE_CORNERS[face]]
        found = None
        for us, vs in itertools.product(((u0,u1),(u1,u0)), ((v0,v1),(v1,v0))):
            corners = [(us[0],vs[0]),(us[0],vs[1]),(us[1],vs[1]),(us[1],vs[0])]
            for quarter in range(4):
                if [corners[(i+quarter)%4] for i in range(4)] == expected:
                    found = {'uv': [n * 16 / texture_size for n in (us[0],vs[0],us[1],vs[1])], 'texture': '#body',
                             **({'rotation': quarter*90} if quarter else {})}
                    break
            if found: break
        if found is None: raise AuditError('trash can: cannot preserve UV orientation')
        faces[face] = found
    return {'from': low, 'to': high, 'faces': faces}


def trash_can(source: Path, assets: dict[str, bytes], items: dict, definitions: dict) -> tuple[dict, dict]:
    sources = read_blobs(source, COMMIT, list(SOURCES))
    parts = parse_mesh(sources[MODEL_SOURCE].decode('utf-8'))
    groups = {'trashcan_1': ('bone',), 'trashcan_2': ('bone2', 'cube_r1'), 'trashcan_3': ('eye',), 'trashcan_4': ('bone3',)}
    for name, members in groups.items():
        model = {'textures': {'body': ART + ':block/trash_can', 'particle': ART + ':block/trash_can'},
                 'elements': [mesh_cube(cube, member, parts) for member in members for cube in parts[member].cubes]}
        display_item(assets, items, 'show:' + name, model)
    key = PLUGIN + ':trash_can'
    items[key] = {'material': 'paper', 'data': {'item_name': '<!i>垃圾桶'}, 'model': definitions[ART + ':item/trash_can'],
                  'behavior': {'type': 'furniture_item', 'furniture': key,
                               'rules': {'ground': {'rotation': 'four', 'alignment': 'center'}}}}
    # Packet-only helmet. 1.21.1 has no item-specific camera_overlay component;
    # its versioned pack retains the vanilla pumpkin mask without a global texture override.
    display_item(assets, items, PLUGIN + ':trashcan_helmet', {'elements': []})
    items[PLUGIN + ':trashcan_helmet'].update({'material': 'carved_pumpkin',
        'data': {'item_name': '<!i>垃圾桶视角', 'components': {'minecraft:equippable': {
            'slot': 'head', 'camera_overlay': ART + ':misc/trash_can_overlay',
            'dispensable': False, 'swappable': False, 'damage_on_hurt': False}}}})
    overlay = ART + '/textures/gui/trash_can_overlay.png'
    assets[ART + '/textures/misc/trash_can_overlay.png'] = assets[overlay]
    furniture = {'settings': {'item': key, 'hit_times': 3,
                             'sounds': {'place': {'id': 'minecraft:block.metal.place'}, 'break': {'id': 'minecraft:block.metal.break'}}},
        'behavior': [{'type': PLUGIN + ':trashcan'}],
        'variants': {'ground': {'elements': [], 'hitboxes': [{'type': 'interaction', 'position': '0,0,0',
            'width': .875, 'height': .9375, 'interactive': True, 'blocks_building': True}]}},
        'loot': {'pools': [{'rolls': 1, 'entries': [{'type': 'item', 'item': key}]}]}}
    report = {'sourceCommit': COMMIT, 'sourceFiles': {path: hashlib.sha256(raw).hexdigest() for path,raw in sources.items()},
              'parts': {name: list(members) for name,members in groups.items()},
              'geometry': 'Java cubes, hierarchy, quarter-turn handle and per-face UVs; zero-area sides omitted.',
              'lidPivotY': (24-parts['root'].offset[1]-parts['bone2'].offset[1]-8)/16,
              'runtime': 'NOT_TESTED', 'vanillaClient': 'NOT_TESTED',
              'differences': ['Interaction hitbox has no solid collision; fall-to-hide needs a landing surface.',
                              'Waterlogging, piston and explosion behavior are not implemented.',
                              'Hiding uses a spectator camera; source uses a seat. Source inventory HUD is not reproduced.',
                              '1.21.1 uses the vanilla pumpkin mask; 1.21.2+ uses the source overlay component.',
                              'Custom items are filtered by CE ID, so distinct custom paper items do not destroy one another.']}
    return {key: furniture}, report
