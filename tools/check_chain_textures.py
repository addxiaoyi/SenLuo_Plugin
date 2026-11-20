"""Verify the six Tavern lamp particles against original models and official client assets."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def check(source: Path, adapted: Path, client: Path, generated: Path | None = None) -> dict:
    reports = []
    with ZipFile(client) as vanilla, ZipFile(source) as original, ZipFile(adapted) as target:
        manifest = json.loads(target.read('VERSION-MANIFEST.json'))
        pngs = set(vanilla.namelist())
        candidates = ['chain', 'iron_chain']
        available = [name for name in candidates if f'assets/minecraft/textures/block/{name}.png' in pngs]
        if len(available) != 1:
            raise ValueError('Expected exactly one vanilla chain block texture')
        texture = 'minecraft:block/' + available[0]
        texture_bytes = vanilla.read(f'assets/minecraft/textures/block/{available[0]}.png')
        # Source lamp uses the unchanged block sprite. This fixed digest comes
        # from official 1.21.1 and 1.21.8 client jars, not from the exporter.
        if hashlib.sha256(texture_bytes).hexdigest() != '44c712141c2914ef9d49143d5236b9f138810f947ab9c9992618e3b11d902ef4':
            raise ValueError('Vanilla chain pixels differ from pinned source-era sprite')
        for lamp in ('bell', 'blue', 'yellow'):
            for half in ('top', 'bottom'):
                path = f'resourcepack/assets/kaleidoscope_tavern/models/block/deco/{lamp}_pendant_lamp/{half}.json'
                before = json.loads(original.read(path))
                after = json.loads(target.read(path))
                raw = target.read(path)
                if hashlib.sha256(raw).hexdigest() != manifest['files'][path]:
                    raise ValueError('Adapted manifest mismatch: ' + path)
                particle = after['textures']['particle']
                particle = particle if ':' in particle else 'minecraft:' + particle
                if particle != texture:
                    raise ValueError('Wrong target texture: ' + path)
                expected = json.loads(original.read(path))
                expected['textures']['particle'] = after['textures']['particle']
                if after != expected:
                    raise ValueError('Unexpected source model changes: ' + path)
                if generated:
                    with ZipFile(generated) as pack:
                        actual = json.loads(pack.read(path.removeprefix('resourcepack/')))
                    actual_texture = actual['textures']['particle']
                    if actual_texture not in (texture, texture.removeprefix('minecraft:')):
                        raise ValueError('Generated texture differs: ' + path)
                    if actual.get('elements') != after.get('elements'):
                        raise ValueError('Generated lamp geometry differs: ' + path)
                reports.append({'file': path, 'originalParticle': before['textures']['particle'], 'particle': particle,
                                'sha256': hashlib.sha256(raw).hexdigest()})
    return {'source': str(source), 'adapted': str(adapted), 'minecraft': manifest['minecraft'],
            'clientSha1': hashlib.sha1(client.read_bytes()).hexdigest(), 'texture': texture,
            'checkedModels': len(reports), 'models': reports,
            'generatedPackSha256': hashlib.sha256(generated.read_bytes()).hexdigest() if generated else None,
            'failures': [], 'clientRendering': 'NOT_TESTED; verifies source geometry and official sprite bytes'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--adapted', required=True, type=Path)
    parser.add_argument('--client', required=True, type=Path)
    parser.add_argument('--generated', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(check(args.source, args.adapted, args.client, args.generated), indent=2) + '\n'
    if args.output:
        args.output.write_text(result, encoding='utf-8')
    print(result)
