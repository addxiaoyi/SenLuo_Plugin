"""Bake static CE composite geometry without changing transforms, textures or element order."""
from __future__ import annotations

import copy
import hashlib
import json


def merge_composite(node: dict, assets: dict[str, bytes]) -> dict | None:
    """Return a plain model only when every layer has compatible vanilla geometry."""
    leaves = []

    def collect(part):
        kind = part.get('type', '').removeprefix('minecraft:')
        if kind == 'empty' and set(part) == {'type'}:
            return True
        if kind == 'model' and set(part) == {'type', 'path'}:
            leaves.append(part['path'])
            return True
        return kind == 'composite' and set(part) == {'type', 'models'} and all(collect(p) for p in part['models'])

    if node.get('type', '').removeprefix('minecraft:') != 'composite' or not collect(node) or not leaves:
        return None
    if len(leaves) == 1:
        return {'type': 'minecraft:model', 'path': leaves[0]}

    def load(id_, trail=()):
        if id_ in trail or len(trail) > 64:
            raise ValueError('Composite model parent cycle/depth: ' + id_)
        namespace, path = id_.split(':', 1) if ':' in id_ else ('minecraft', id_)
        file = f'{namespace}/models/{path}.json'
        if file not in assets:
            raise ValueError('Composite model parent unavailable: ' + id_)
        own = json.loads(assets[file])
        # Unknown rendering features, generated/special renderers and legacy override
        # trees cannot be collapsed into a static cuboid mesh.
        if set(own) - {'parent', 'textures', 'elements', 'display', 'ambientocclusion', 'gui_light',
                       'credit', 'texture_size', 'format_version', 'groups'}:
            raise ValueError('Unsupported composite geometry fields: ' + id_)
        parent = load(own['parent'], (*trail, id_)) if 'parent' in own else {}
        return {**parent, **own, 'textures': {**parent.get('textures', {}), **own.get('textures', {})},
                'display': {**parent.get('display', {}), **own.get('display', {})}}

    try:
        models = [load(path) for path in leaves]
    except ValueError:
        return None
    settings = lambda m: (m.get('display', {}), m.get('ambientocclusion', True), m.get('gui_light', 'side'))
    if any(settings(model) != settings(models[0]) or not isinstance(model.get('elements'), list) for model in models):
        return None
    result = {'display': copy.deepcopy(models[0]['display']), 'ambientocclusion': models[0].get('ambientocclusion', True),
              'gui_light': models[0].get('gui_light', 'side'), 'textures': {}, 'elements': []}
    for index, model in enumerate(models):
        textures = model['textures']

        def texture(value):
            seen = set()
            while value.startswith('#'):
                key = value[1:]
                if key in seen or key not in textures:
                    raise ValueError('Missing/cyclic composite texture ' + value)
                seen.add(key)
                value = textures[key]
            return value

        try:
            for key, value in textures.items():
                result['textures'][f'p{index}_{key}'] = texture(value)
            if index == 0 and 'particle' in textures:
                result['textures']['particle'] = texture(textures['particle'])
            for element in model['elements']:
                element = copy.deepcopy(element)
                for face in element.get('faces', {}).values():
                    original = face['texture']
                    texture(original)  # Validate aliases even when retaining their renamed reference.
                    face['texture'] = f'#p{index}_{original[1:]}' if original.startswith('#') else texture(original)
                result['elements'].append(element)
        except (KeyError, ValueError):
            return None
    encoded = (json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    name = 'composite/' + hashlib.sha256(encoded).hexdigest()[:24]
    path = f'kaleidoscope_deco/models/{name}.json'
    if path in assets and assets[path] != encoded:
        raise ValueError('Generated composite hash collision')
    assets[path] = encoded
    return {'type': 'minecraft:model', 'path': 'kaleidoscope_deco:' + name}


def legacy_composite(node: dict, assets: dict[str, bytes]) -> dict | None:
    merged = merge_composite(node, assets)
    return {'path': merged['path']} if merged is not None else None
