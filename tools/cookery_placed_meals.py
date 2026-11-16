"""Export all pinned directly edible meals, including the Java-rendered 3x3 dish."""
from __future__ import annotations

import copy
import hashlib
import itertools
import json
from pathlib import Path
import re
from typing import Callable

from audit_upstream import AuditError, read_blobs, uncomment_java, unique_object
from cookery_millstone import Cube, Part, SOURCE_FACES, face_uv
from cookery_models import json_bytes, model_node, model_path
from export_cookery_catalog import COMMIT, arguments, chain, number

ART = 'kaleidoscope_cookery'
PLUGIN = 'kaleidoscopecookery'
FACING = ('north', 'east', 'south', 'west')
PARTS = ('left_up', 'up', 'right_up', 'left_center', 'center', 'right_center', 'left_down', 'down', 'right_down')
HAM_SOURCE = 'src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/client/model/ColdCutHamSlicesModel.java'
HAM_SHA256 = '99dec8798001844b77d77d6ab05f811fc658da6849437e353464ffcfbfdeb2b3'
DisplayBuilder = Callable[[dict[str, bytes], dict, str, int], dict]


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise AuditError(detail)


def read_asset(assets: dict[str, bytes], path: str) -> dict:
    require(path in assets, f'missing placed meal asset: {path}')
    return json.loads(assets[path], object_pairs_hook=unique_object)


def meal_states(assets: dict[str, bytes], name: str, bite: dict) -> tuple[dict[tuple, dict], int]:
    """Collapse quality only after proving every source quality uses identical visuals."""
    source = read_asset(assets, f'{ART}/blockstates/{name}.json')
    require(set(source) == {'variants'}, f'unknown placed meal blockstate structure: {name}')
    expected = {'facing': FACING, 'bites': tuple(map(str, range(bite['maxBites'] + 1))), 'quality': tuple('01234')}
    if bite['layout'] == '1x2':
        expected['position'] = ('0', '1')
    grouped: dict[tuple, dict] = {}
    observed = set()
    for selector, model in source['variants'].items():
        pairs = [pair.split('=', 1) for pair in selector.split(',') if pair]
        require(all(len(pair) == 2 for pair in pairs) and len(pairs) == len({pair[0] for pair in pairs}),
                f'invalid placed meal selector: {name}/{selector}')
        values = dict(pairs)
        require(set(values) == set(expected) and all(value in expected[key] for key, value in values.items()),
                f'unknown placed meal state: {name}/{selector}')
        require(isinstance(model, dict) and 'model' in model and not set(model) - {'model', 'x', 'y', 'uvlock'},
                f'unknown placed meal model: {name}/{selector}')
        require(model.get('x', 0) == 0 and model.get('y', 0) in (0, 90, 180, 270),
                f'unsupported placed meal rotation: {name}/{selector}')
        require(type(model.get('uvlock', False)) is bool, f'invalid placed meal UV lock: {name}/{selector}')
        signature = tuple(values[key] for key in expected)
        require(signature not in observed, f'duplicate placed meal selector: {name}/{selector}')
        observed.add(signature)
        collapsed = (values['facing'], int(values['bites']), int(values.get('position', 0)))
        require(collapsed not in grouped or grouped[collapsed] == model,
                f'quality changes placed meal visual: {name}/{selector}')
        grouped[collapsed] = model
    require(observed == set(itertools.product(*expected.values())), f'incomplete placed meal states: {name}')
    return grouped, len(observed)


def ham_parts(raw: bytes) -> dict[str, Part]:
    """Fail closed when the pinned Java geometry or visibility order changes."""
    require(hashlib.sha256(raw).hexdigest() == HAM_SHA256, 'cold cut ham Java mesh changed; explicit conversion required')
    text = uncomment_java(raw.decode('utf-8'))
    factory = re.search(r'public static LayerDefinition createBodyLayer\(\)\s*\{([^{}]+)\}', text)
    require(factory is not None, 'unknown cold cut ham model factory')
    statements = [row.strip() for row in factory[1].split(';') if row.strip()]
    require(statements[:2] == ['MeshDefinition meshdefinition = new MeshDefinition()',
                              'PartDefinition partdefinition = meshdefinition.getRoot()']
            and statements[-1] == 'return LayerDefinition.create(meshdefinition, 256, 64)',
            'cold cut ham model root or texture dimensions changed')
    parts = {}
    for statement in statements[2:-1]:
        declaration = re.fullmatch(r'PartDefinition (\w+) = (\w+)\.addOrReplaceChild\((.*)\)', statement, re.S)
        require(declaration is not None, 'unknown cold cut ham part declaration')
        name, parent, params = declaration.groups()
        args = arguments(params)
        require(len(args) == 3 and args[0] == '"' + name + '"' and name not in parts, 'invalid cold cut ham part')
        require(parent == 'partdefinition' or parent in parts, 'unknown cold cut ham parent')
        prefix = 'CubeListBuilder.create()'
        require(args[1].startswith(prefix), 'unknown cold cut ham cube builder')
        uv, cubes = None, []
        for method, values in chain(args[1][len(prefix):]):
            if method == 'texOffs' and len(values) == 2:
                uv = tuple(number(value) for value in values)
            elif method == 'addBox' and len(values) == 7 and uv is not None:
                deformation = re.fullmatch(r'new CubeDeformation\(([^()]+)\)', values[6])
                require(deformation is not None, 'unknown cold cut ham cube deformation')
                size = tuple(number(value) for value in values[3:6])
                require(all(value > 0 for value in size), 'invalid cold cut ham cube size')
                cubes.append(Cube(uv, tuple(number(value) for value in values[:3]), size, number(deformation[1])))
            else:
                raise AuditError(f'unsupported cold cut ham cube call: {method}')
        pose = re.fullmatch(r'PartPose\.(offset|offsetAndRotation)\(([^()]+)\)', args[2])
        require(pose is not None, 'unknown cold cut ham pose')
        values = tuple(number(value) for value in arguments(pose[2]))
        require(len(values) == (3 if pose[1] == 'offset' else 6), 'invalid cold cut ham pose dimensions')
        parts[name] = Part(parent, values[:3], values[3:] if len(values) == 6 else (0, 0, 0), tuple(cubes))
    expected = {'base': ('partdefinition', 2), 'food': ('partdefinition', 0)}
    for index in range(1, 9):
        expected[f'bite{index}'] = ('food', 0 if index % 2 else 1)
        for child in (1, 2):
            expected[f'bite{index}_r{child}'] = (f'bite{index}', 1)
    require({name: (part.parent, len(part.cubes)) for name, part in parts.items()} == expected,
            'unknown cold cut ham hierarchy')
    return parts


def ham_cube(cube: Cube, part: Part, pivot: tuple[float, ...], group: str) -> dict:
    def transform(point: tuple[float, ...]) -> tuple[float, ...]:
        x, y, z = (a + b for a, b in zip(point, pivot, strict=True))
        # The source renderer at SOUTH translates (0.5,1.5,0.5), then rotates Z/Y by -180 degrees.
        return (round(8 + x, 6), round(24 - y, 6), round(8 - z, 6))

    require(cube.inflation == 0, 'cold cut ham inflated geometry requires conversion')
    lo = cube.origin
    hi = tuple(a + b for a, b in zip(cube.origin, cube.size, strict=True))
    vertices = [transform(tuple((lo, hi)[bit][axis] for axis, bit in enumerate(corner)))
                for corner in ((0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0),
                               (0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1))]
    low = [min(point[axis] for point in vertices) for axis in range(3)]
    high = [max(point[axis] for point in vertices) for axis in range(3)]
    require(all(-16 <= value <= 32 for value in low + high), 'cold cut ham geometry exceeds vanilla bounds')
    u, v = cube.uv
    dx, dy, dz = cube.size
    rects = ((u + dz, v, u + dz + dx, v + dz), (u + dz + dx, v + dz, u + dz + 2 * dx, v),
             (u, v + dz, u + dz, v + dz + dy), (u + dz, v + dz, u + dz + dx, v + dz + dy),
             (u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy),
             (u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy))
    faces = dict(face_uv(vertices, indices, rect, low, high) for indices, rect in zip(SOURCE_FACES, rects, strict=True))
    for face in faces.values():
        # Millstone's UV helper uses 128x128; this Java model uses 256x64.
        face['uv'] = [value / 2 if axis % 2 == 0 else value * 2 for axis, value in enumerate(face['uv'])]
    element = {'name': group, 'from': low, 'to': high, 'faces': faces}
    rotations = [(axis, angle) for axis, angle in enumerate(part.rotation) if angle]
    require(len(rotations) <= 1, 'cold cut ham multi-axis rotation requires conversion')
    if rotations:
        axis, radians = rotations[0]
        require(axis in (0, 2) and abs(abs(radians) - .3927) < .000001, 'unknown cold cut ham rotation')
        angle = 22.5 if radians > 0 else -22.5
        if axis == 2:
            angle = -angle
        element['rotation'] = {'origin': list(transform((0, 0, 0))), 'axis': 'xyz'[axis], 'angle': angle, 'rescale': False}
    return element


def ham_stages(source: Path, assets: dict[str, bytes]) -> tuple[list[str], dict]:
    raw = read_blobs(source, COMMIT, [HAM_SOURCE])[HAM_SOURCE]
    parts = ham_parts(raw)
    texture = ART + '/textures/block/cold_cut_ham_slices.png'
    require(texture in assets, 'missing cold cut ham texture')
    groups: dict[str, list[dict]] = {'base': [], **{f'bite{index}': [] for index in range(1, 9)}}
    for name, part in parts.items():
        pivot = part.offset
        parent = part.parent
        while parent != 'partdefinition':
            ancestor = parts[parent]
            require(ancestor.rotation == (0, 0, 0), 'cold cut ham parent rotation requires conversion')
            pivot = tuple(a + b for a, b in zip(pivot, ancestor.offset, strict=True))
            parent = ancestor.parent
        group = name.split('_', 1)[0]
        for cube in part.cubes:
            groups[group].append(ham_cube(cube, part, pivot, group))
    stages = []
    for bites in range(9):
        identifier = PLUGIN + f':block/imported/cold_cut_ham_slices_bites_{bites}'
        path = model_path(identifier)
        require(path not in assets, f'generated meal model collision: {path}')
        elements = groups['base'] + [cube for index in range(1, 9) if bites < 9 - index for cube in groups[f'bite{index}']]
        assets[path] = json_bytes({'textures': {'body': ART + ':block/cold_cut_ham_slices'}, 'elements': elements})
        stages.append(identifier)
    return stages, {'path': HAM_SOURCE, 'sha256': hashlib.sha256(raw).hexdigest(), 'textureSha256': hashlib.sha256(assets[texture]).hexdigest(),
                    'sourceCubes': sum(len(group) for group in groups.values()), 'stageModels': stages,
                    'visibility': {f'bite{index}': f'bites < {9-index}' for index in range(1, 9)},
                    'differences': ['Source 0.3927-radian angles use vanilla 22.5-degree rotations; client visual acceptance pending.']}


def placed_meals(source: Path, catalog: dict, assets: dict[str, bytes], items: dict,
                 definitions: dict, names: dict, display: DisplayBuilder) -> tuple[dict, dict]:
    entries = [entry for entry in catalog['entries'] if entry.get('bites')]
    require(len(entries) == 38 and len({entry['pluginId'] for entry in entries}) == 38, 'Expected all 38 placed meals')
    blocks, manifest = {}, {}
    for entry in entries:
        key, bite = entry['pluginId'], entry['bites']
        name = entry['sourceId'].split(':', 1)[1]
        layout = bite['layout']
        require(layout in ('1x1', '1x2', '3x3'), f'unknown placed meal layout: {name}')
        maximum = bite['maxBites']
        properties = {'facing': {'type': 'horizontal_direction', 'default': 'south'},
                      'bites': {'type': 'int', 'range': f'0~{maximum}', 'default': 0}}
        dimensions = {'facing': FACING, 'bites': tuple(range(maximum + 1))}
        provenance = None
        if layout == '3x3':
            require(name == 'cold_cut_ham_slices' and maximum == 8, 'unknown Java-rendered placed meal')
            source_state = read_asset(assets, f'{ART}/blockstates/{name}.json')
            require(source_state == {'variants': {'': {'model': ART + ':block/cold_cut_ham_slices'}}}, '3x3 source blockstate changed')
            stages, provenance = ham_stages(source, assets)
            state_models, source_count = {}, 1
            properties['part'] = {'type': 'string', 'values': list(PARTS), 'default': 'center'}
            dimensions['part'] = PARTS
        else:
            state_models, source_count = meal_states(assets, name, bite)
            if layout == '1x2':
                properties['position'] = {'type': 'int', 'range': '0~1', 'default': 1}
                dimensions['position'] = (0, 1)
        appearances, variants = {}, {}
        for combination in itertools.product(*dimensions.values()):
            values = dict(zip(dimensions, combination, strict=True))
            selector = ','.join(f'{field}={value}' for field, value in values.items())
            appearance = 's' + str(len(appearances))
            visual = {'auto_state': {'type': 'lower_tripwire', 'id': 'cookery_kitchen_meal'}, 'transparent': True}
            if layout != '3x3':
                model = state_models[(values['facing'], values['bites'], values.get('position', 0))]
                visual['entity_renderer'] = display(assets, items, model['model'], model.get('y', 0))
            elif values['part'] == 'center':
                visual['entity_renderer'] = display(assets, items, stages[values['bites']], (FACING.index(values['facing']) * 90 + 180) % 360)
            appearances[appearance] = visual
            variants[selector] = {'appearance': appearance}
        if key not in items:
            model = ART + ':item/' + name
            require(model in definitions, f'missing placed meal item model: {name}')
            items[key] = {'material': 'paper', 'data': {'item_name': '<!i>' + names.get(f'item.{ART}.{name}', name)},
                          'model': copy.deepcopy(definitions[model])}
        items[key]['behavior'] = {'type': PLUGIN + ':placed_meal_item', 'block': key}
        if layout != '3x3':
            items[key]['data']['max_stack_size'] = 16
        blocks[key] = {
            'settings': {'item': key, 'hardness': 0, 'resistance': 0, 'push_reaction': 'destroy',
                         'can_occlude': False, 'is_suffocating': False, 'is_view_blocking': False},
            'behavior': [{'type': PLUGIN + ':placed_meal', 'food_id': key, 'max_bites': maximum,
                          'layout': layout, 'drops': list(bite['drops']), 'animation': bite['animation']}],
            'loot': {'pools': []},
            'states': {'properties': properties, 'appearances': appearances, 'variants': variants}}
        manifest[key] = {
            'sourceId': entry['sourceId'], 'sourceCommit': COMMIT, 'maxBites': maximum, 'layout': layout,
            'drops': list(bite['drops']), 'animation': bite['animation'], 'cells': {'1x1': 1, '1x2': 2, '3x3': 9}[layout],
            'anchor': 'self' if layout == '1x1' else 1 if layout == '1x2' else 'center',
            'states': len(variants), 'sourceBlockstateVariants': source_count,
            'quality': 'Controller snapshot; identical source quality visuals folded',
            'sourceCollision': bite['collision'], 'collision': 'PENDING_VANILLA_SHAPE_SUBSTITUTE',
            'animationDifference': 'Server particles approximate client animateTick frequency and custom cooking particles' if bite['animation'] else None,
            'clientVisual': 'NOT_TESTED', **({'javaModel': provenance} if provenance else {})}
    return blocks, manifest
