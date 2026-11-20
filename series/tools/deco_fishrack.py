"""Compile the pinned fishrack's three independently selected fish layers."""
import copy
import itertools

from deco_geometry import merge_composite


def add_fishrack_variants(items: dict, furniture: dict, assets: dict) -> None:
    ids = [f'kaleidoscope_deco:fishrack_{i}' for i in range(4)]
    present = [id_ for id_ in ids if id_ in furniture]
    if not present:
        return
    if present != ids:
        raise ValueError('Incomplete fishrack source definitions')
    for id_ in ids:
        # All four source states share the same loot table, which places an
        # empty fishrack_0. One left click invokes destruct in the datapack.
        furniture[id_]['settings']['item'] = ids[0]
        furniture[id_]['settings']['hit_times'] = 1
        furniture[id_]['settings']['sounds'] = {
            'place': {'id': 'minecraft:block.wood.hit', 'volume': 1, 'pitch': 1},
            'break': {'id': 'minecraft:block.wood.break', 'volume': 1, 'pitch': 1},
        }
        furniture[id_]['loot'] = {'pools': [{'rolls': 1, 'entries': [{'type': 'item', 'item': ids[0]}]}]}
    for count in range(4):
        for fish in itertools.product(('cod', 'salmon', 'tropical'), repeat=count):
            state = '/'.join(fish) or 'empty'
            visual = 'kaleidoscope_deco:fishrack/display/' + state
            if visual in items:
                raise ValueError('Generated fishrack display collision: ' + visual)
            models = [{'type': 'minecraft:model', 'path': 'kdeco_kaleidoscope_cookery_plus:fishrack/empty'}]
            models.extend({'type': 'minecraft:model', 'path': f'kdeco_kaleidoscope_cookery_plus:fishrack/{name}_{slot}'}
                          for slot, name in enumerate(fish))
            combined = merge_composite({'type': 'minecraft:composite', 'models': models}, assets)
            if combined is None:
                raise ValueError('Fishrack layers cannot be merged without changing appearance: ' + state)
            items[visual] = {'material': 'paper', 'model': combined, 'legacy_model': {'path': combined['path']}}
            for id_ in ids:
                variant = copy.deepcopy(furniture[id_]['variants']['ground'])
                variant['elements'][0]['item'] = visual
                furniture[id_]['variants']['fishrack/' + state] = variant
