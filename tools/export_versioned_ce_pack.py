"""Derive a CE pack for a Minecraft target while preserving source assets and provenance."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile, ZIP_DEFLATED

import yaml

VERSIONS = tuple(f"1.21.{i}" for i in range(1, 12)) + ("26.1", "26.1.1", "26.1.2", "26.2", "26.3")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_tuple(version: str) -> tuple[int, ...]:
    if version not in VERSIONS:
        raise ValueError(f"Unsupported Minecraft target: {version}")
    return tuple(int(part) for part in version.split('.'))


def adapt_configuration(config: dict, minecraft: str) -> tuple[dict, dict]:
    target = version_tuple(minecraft)
    result = copy.deepcopy(config)
    changes = {"ironChainAliases": 0, "legacyFoods": 0, "legacyDrinkAnimations": 0}

    def aliases(value):
        if isinstance(value, dict):
            return {key: aliases(child) for key, child in value.items()}
        if isinstance(value, list):
            return [aliases(child) for child in value]
        if value == "minecraft:iron_chain":
            changes["ironChainAliases"] += 1
            return "minecraft:chain"
        return value

    if target < (1, 21, 9):
        result = aliases(result)
    if target >= (1, 21, 2):
        return result, changes
    for key, item in result.get("items", {}).items():
        if key == 'kaleidoscopecookery:transmutation_lunch_bag' and item.get('material') == 'bundle':
            # Bundles require experimental feature flags in 1.21.1. Both click
            # directions are implemented by the plugin, so paper remains usable.
            item['material'] = 'paper'
            changes['legacyLunchBagMaterial'] = changes.get('legacyLunchBagMaterial', 0) + 1
        data = item.get("data", {})
        components = data.get("components", {})
        if key == 'kaleidoscopecookery:trashcan_helmet' and 'minecraft:equippable' in components:
            del components['minecraft:equippable']
            changes['legacyTrashCanOverlay'] = changes.get('legacyTrashCanOverlay', 0) + 1
        consume = components.get("minecraft:consumable")
        if consume is None:
            continue
        if "minecraft:food" in components:
            raise ValueError(f"{key}: ambiguous pre-existing raw food component")
        unsupported = set(consume) - {"consume_seconds", "animation", "sound", "has_consume_particles", "on_consume_effects"}
        if unsupported:
            raise ValueError(f"{key}: unsupported legacy consumable fields: {sorted(unsupported)}")
        if consume.get("has_consume_particles", True) is not True:
            raise ValueError(f"{key}: disabling food particles cannot be represented on 1.21.1")
        if consume.get("sound", "minecraft:entity.generic.eat") not in ("minecraft:entity.generic.eat", "minecraft:entity.generic.drink"):
            raise ValueError(f"{key}: custom consumption sound cannot be represented on 1.21.1")
        food = copy.deepcopy(data.get("food", {"nutrition": 0, "saturation": 0.0, "can_always_eat": True}))
        if set(food) - {"nutrition", "saturation", "can_always_eat"}:
            raise ValueError(f"{key}: unsupported legacy food fields")
        food["eat_seconds"] = consume.get("consume_seconds", 1.6)
        food["effects"] = []
        for effect in consume.get("on_consume_effects", []):
            if effect.get("type") != "minecraft:apply_effects" or set(effect) - {"type", "effects", "probability"}:
                raise ValueError(f"{key}: consume effect cannot be represented on 1.21.1: {effect}")
            # A single probability covering a group is not equivalent to independent rolls.
            effects = effect.get("effects", [])
            probability = effect.get("probability", 1.0)
            if len(effects) > 1 and probability not in (0, 1):
                raise ValueError(f"{key}: grouped probabilistic effects need a runtime adapter")
            food["effects"].extend({"effect": entry, "probability": probability} for entry in effects)
        animation = consume.get("animation", "eat")
        if animation not in ("eat", "drink"):
            raise ValueError(f"{key}: unsupported legacy animation: {animation}")
        if animation == "drink":
            changes["legacyDrinkAnimations"] += 1
        # FoodProcessor only exposes three fields; write the native 1.21.1 codec to
        # preserve duration/effects. Removing the processor avoids order-dependent overwrites.
        data.pop("food", None)
        del components["minecraft:consumable"]
        components["minecraft:food"] = food
        changes["legacyFoods"] += 1
    return result, changes


def adapt_model_textures(model: dict, minecraft: str, supplied_textures: set[str]) -> tuple[dict, int]:
    """Resolve vanilla chain texture renames; retain explicit pack overrides and geometry."""
    modern = version_tuple(minecraft) >= (1, 21, 9)
    old, new = ('chain', 'iron_chain') if modern else ('iron_chain', 'chain')
    aliases = {f'minecraft:{kind}/{old}': f'minecraft:{kind}/{new}' for kind in ('block', 'item')}
    result, changed = copy.deepcopy(model), 0
    for key, value in result.get('textures', {}).items():
        if not isinstance(value, str) or value.startswith('#'):
            continue
        id_ = value if ':' in value else 'minecraft:' + value
        if id_ in aliases and id_ not in supplied_textures:
            result['textures'][key] = aliases[id_]
            changed += 1
    return result, changed


def build(source: Path, output: Path, minecraft: str) -> dict:
    target = version_tuple(minecraft)
    if output.exists():
        raise ValueError(f"Refusing to overwrite {output}")
    with ZipFile(source) as archive:
        entries = archive.infolist()
        if len({entry.filename for entry in entries}) != len(entries):
            raise ValueError("Duplicate ZIP entries")
        for entry in entries:
            name = entry.orig_filename
            if (PurePosixPath(name).is_absolute() or '\\' in name or ':' in name
                    or any(part in ('', '.', '..') for part in name.rstrip('/').split('/'))):
                raise ValueError(f"Unsafe ZIP entry: {name}")
        if archive.testzip():
            raise ValueError("Source archive CRC failed")
        files = {entry.filename: archive.read(entry) for entry in entries if not entry.is_dir()}
    if "VERSION-MANIFEST.json" in files:
        raise ValueError("Use an original CE export, not an already adapted pack")
    manifest = json.loads(files["manifest.json"]) if "manifest.json" in files else None
    if manifest:
        for name, digest in manifest["files"].items():
            if name not in files or sha256(files[name]) != digest:
                raise ValueError(f"Source manifest mismatch: {name}")
    changes = []
    supplied_textures = set()
    for name in files:
        if '/resourcepack/assets/' in '/' + name and name.endswith('.png'):
            asset = ('/' + name).split('/resourcepack/assets/', 1)[1]
            parts = asset.split('/', 2)
            if len(parts) == 3 and parts[1] == 'textures':
                supplied_textures.add(parts[0] + ':' + parts[2][:-4])
    for name, raw in list(files.items()):
        if '/resourcepack/assets/' in '/' + name and '/models/' in name and name.endswith('.json'):
            model = json.loads(raw)
            adapted_model, count = adapt_model_textures(model, minecraft, supplied_textures)
            if count:
                files[name] = (json.dumps(adapted_model, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
                changes.append({'file': name, 'vanillaChainTextures': count, 'before': sha256(raw), 'after': sha256(files[name])})
        if '/configuration/' not in '/' + name or not name.endswith(('.yml', '.yaml')):
            continue
        if target >= (1, 21, 9):
            continue
        json_format = raw.lstrip().startswith(b'{')
        config = json.loads(raw) if json_format else yaml.load(raw, Loader=getattr(yaml, 'CSafeLoader', yaml.SafeLoader))
        adapted, counts = adapt_configuration(config, minecraft)
        if adapted != config:
            encoded = json.dumps(adapted, ensure_ascii=False, indent=2) + '\n' if json_format else yaml.dump(
                adapted, Dumper=getattr(yaml, 'CSafeDumper', yaml.SafeDumper), allow_unicode=True, sort_keys=False)
            files[name] = encoded.encode('utf-8')
            changes.append({"file": name, **counts, "before": sha256(raw), "after": sha256(files[name])})
    family = "1.21.1" if target < (1, 21, 2) else "1.21.2-1.21.8" if target < (1, 21, 9) else "1.21.9-26.3"
    report = {"minecraft": minecraft, "configurationFamily": family, "source": source.name,
              "sourceSha256": sha256(source.read_bytes()), "changes": changes,
              "runtime": "NOT_TESTED", "vanillaClient": "NOT_TESTED",
              "limitations": (["1.21.1 uses the food eating animation for custom paper drinks; custom drink animation/sound are unavailable."]
                              if any(change.get("legacyDrinkAnimations", 0) for change in changes) else [])}
    if manifest:
        manifest["target"]["minecraft"] = minecraft
        manifest["target"]["configurationFamily"] = family
        manifest["files"] = {name: sha256(files[name]) for name in manifest["files"]}
        files["manifest.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    report["files"] = {name: sha256(raw) for name, raw in sorted(files.items())}
    files["VERSION-MANIFEST.json"] = (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'x', ZIP_DEFLATED) as archive:
        for name, raw in sorted(files.items()):
            archive.writestr(name, raw)
    with ZipFile(output) as archive:
        if archive.testzip():
            raise ValueError("Output archive CRC failed")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--minecraft', required=True, choices=VERSIONS)
    args = parser.parse_args()
    report = build(args.source, args.output, args.minecraft)
    print(json.dumps({key: value for key, value in report.items() if key != 'files'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
