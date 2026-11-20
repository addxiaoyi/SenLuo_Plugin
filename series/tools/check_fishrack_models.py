"""Compare all 40 generated fishrack meshes with the pinned source's ordered layers."""
from __future__ import annotations

import argparse
import copy
import hashlib
import itertools
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile

SOURCE_COMMIT = '6ce180e1226ce93b0fb0d45ea80e0ed5b01b1dc0'


def geometry(model_id, read, trail=()):
    if model_id in trail:
        raise ValueError('Cyclic model: ' + model_id)
    own = read(model_id)
    parent = geometry(own['parent'], read, (*trail, model_id)) if 'parent' in own else {}
    return {**parent, **own, 'textures': {**parent.get('textures', {}), **own.get('textures', {})},
            'display': {**parent.get('display', {}), **own.get('display', {})}}


def mesh(model):
    elements = copy.deepcopy(model['elements'])
    for element in elements:
        for face in element.get('faces', {}).values():
            texture, seen = face['texture'], set()
            while texture.startswith('#'):
                if texture in seen:
                    raise ValueError('Cyclic texture: ' + texture)
                seen.add(texture)
                texture = model['textures'][texture[1:]]
            face['texture'] = 'kdeco_' + texture if texture.startswith('kaleidoscope_cookery_plus:') else texture
    return elements


def check(runtime: Path, source: Path):
    actual_commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if actual_commit != SOURCE_COMMIT:
        raise ValueError('Unexpected fishrack reference commit: ' + actual_commit)
    config = json.loads((runtime / 'resources/deco/configuration/content.yml').read_text(encoding='utf-8'))
    source_assets = source / 'src/kaleidoscope_cookery_plus/assets'
    def read_source(id_):
        namespace, name = id_.split(':', 1)
        return json.loads((source_assets / namespace / 'models' / (name + '.json')).read_text(encoding='utf-8'))
    base_id = 'kaleidoscope_cookery_plus:fishrack/'
    layers = {name: geometry(base_id + name, read_source) for name in
              ['empty'] + [f'{fish}_{slot}' for fish in ('cod', 'salmon', 'tropical') for slot in range(3)]}
    checked, textures = [], set()
    pack = runtime / 'generated/resource_pack.zip'
    with ZipFile(pack) as archive:
        def read_output(id_):
            namespace, name = id_.split(':', 1)
            return json.loads(archive.read(f'assets/{namespace}/models/{name}.json'))
        for count in range(4):
            for fish in itertools.product(('cod', 'salmon', 'tropical'), repeat=count):
                state = '/'.join(fish) or 'empty'
                id_ = 'kaleidoscope_deco:fishrack/display/' + state
                item = config['items'][id_]
                if item['model']['path'] != item['legacy_model']['path']:
                    raise ValueError('Legacy fishrack geometry differs: ' + state)
                actual = geometry(item['model']['path'], read_output)
                expected_layers = [layers['empty'], *[layers[f'{name}_{slot}'] for slot, name in enumerate(fish)]]
                expected = [element for layer in expected_layers for element in mesh(layer)]
                if mesh(actual) != expected:
                    raise ValueError('Fishrack elements/UV/rotations/textures/order differ: ' + state)
                for layer in expected_layers:
                    for key, default in [('display', {}), ('ambientocclusion', True), ('gui_light', 'side')]:
                        if actual.get(key, default) != layer.get(key, default):
                            raise ValueError(f'Fishrack {key} differs: {state}')
                textures.update(face['texture'] for element in expected for face in element.get('faces', {}).values())
                for index in range(4):
                    furniture = config['furniture'][f'kaleidoscope_deco:fishrack_{index}']
                    variant = furniture['variants']['fishrack/' + state]
                    if variant['elements'][0]['item'] != id_:
                        raise ValueError('Wrong fishrack display item: ' + state)
                checked.append(state)
        for id_ in textures:
            namespace, name = id_.split(':', 1)
            raw = archive.read(f'assets/{namespace}/textures/{name}.png')
            original = source_assets / namespace.removeprefix('kdeco_') / 'textures' / (name + '.png')
            if raw != original.read_bytes():
                raise ValueError('Fishrack texture differs from pinned source: ' + id_)
    return {'sourceCommit': actual_commit, 'checkedStates': len(checked), 'checkedFurnitureVariants': len(checked) * 4,
            'checkedTextures': len(textures), 'resourcePackSha256': hashlib.sha256(pack.read_bytes()).hexdigest(),
            'failures': [], 'clientRendering': 'NOT_TESTED; compares generated geometry and texture bytes'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=Path)
    parser.add_argument('source', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = json.dumps(check(args.runtime, args.source), ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(report, encoding='utf-8')
    print(report)
