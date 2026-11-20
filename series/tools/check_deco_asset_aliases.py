"""Check final generated model aliases and texture bytes against the fixed Deco source."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile


def check(pack: Path, source: Path) -> dict:
    commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != '6ce180e1226ce93b0fb0d45ea80e0ed5b01b1dc0':
        raise ValueError('Unexpected Deco source commit')
    assets = source / 'src/kaleidoscope_cookery/assets/kaleidoscope_cookery'
    rows = []
    with ZipFile(pack) as z:
        for food in ('slime_ball_meal', 'mysterious'):
            path = f'assets/kdeco_kaleidoscope_cookery/models/item/{food}.json'
            actual = json.loads(z.read(path))
            expected = json.loads((assets / f'models/meals/item/{food}.json').read_text(encoding='utf-8'))
            expected['textures']['layer0'] = f'kdeco_kaleidoscope_cookery:item/{food}'
            # CE may make the vanilla namespace explicit without changing it.
            actual['parent'] = actual['parent'].removeprefix('minecraft:')
            if actual != expected:
                raise ValueError('Generated alias changed source rendering: ' + food)
            for suffix in ('.png', '.png.mcmeta'):
                original = assets / f'textures/item/{food}{suffix}'
                if original.is_file() and z.read(f'assets/kdeco_kaleidoscope_cookery/textures/item/{food}{suffix}') != original.read_bytes():
                    raise ValueError('Generated alias texture/animation differs: ' + food + suffix)
            rows.append({'item': food, 'model': path, 'sha256': hashlib.sha256(z.read(path)).hexdigest(),
                         'animated': (assets / f'textures/item/{food}.png.mcmeta').is_file()})
    return {'sourceCommit': commit, 'resourcePackSha256': hashlib.sha256(pack.read_bytes()).hexdigest(),
            'checkedAliases': len(rows), 'models': rows, 'failures': [], 'clientRendering': 'NOT_TESTED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', type=Path)
    parser.add_argument('source', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    encoded = json.dumps(check(args.pack, args.source), indent=2) + '\n'
    if args.output:
        args.output.write_text(encoded, encoding='utf-8')
    print(encoded)
