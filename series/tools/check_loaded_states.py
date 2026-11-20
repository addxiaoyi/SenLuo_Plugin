"""Check complete CE state registration against declared properties, not visual variants."""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
from pathlib import Path
import re
import sys
from typing import Any

import yaml


SCOPE = "CE declared properties compared with internal state cache; registration only, no placement or client proof"
NAMESPACE = "kaleidoscopecookery:"
ENUM_VALUES = {
    "axis": ("x", "y", "z"),
    "horizontal_direction": ("north", "east", "south", "west"),
    "4-direction": ("north", "east", "south", "west"),
    "anchor_type": ("floor", "wall", "ceiling"),
    "single_block_half": ("bottom", "top"),
    "double_block_half": ("upper", "lower"),
    "slab_type": ("top", "bottom", "double"),
}
PROPERTY_NAME = re.compile(r"[a-z0-9_]+")
PROPERTY_VALUE = re.compile(r"[a-z0-9_-]+")
BLOCK_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
INT_RANGE = re.compile(r"(-?\d+)~(-?\d+)")


class RegistrationError(ValueError):
    pass


class UniqueYamlLoader(yaml.SafeLoader):
    pass


def unique_yaml(loader: UniqueYamlLoader, node: yaml.MappingNode, deep: bool = False) -> dict:
    mapping: dict = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise RegistrationError(f"duplicate YAML key: {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueYamlLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_yaml)


def unique_json(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    mapping: dict[str, Any] = {}
    for key, value in pairs:
        if key in mapping:
            raise RegistrationError(f"duplicate JSON key: {key}")
        mapping[key] = value
    return mapping


def load_yaml(path: Path) -> dict[str, Any]:
    try:
        content = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueYamlLoader)
    except (OSError, yaml.YAMLError, UnicodeDecodeError) as error:
        raise RegistrationError(f"{path}: cannot read YAML: {error}") from error
    if not isinstance(content, dict):
        raise RegistrationError(f"{path}: expected a YAML object")
    return content


def values(name: str, spec: Any, block: str) -> tuple[str, ...]:
    if not PROPERTY_NAME.fullmatch(name) or not isinstance(spec, dict):
        raise RegistrationError(f"{block}: invalid property: {name}")
    kind = spec.get("type")
    if kind in ("boolean", "bool"):
        return ("false", "true")
    if kind == "int":
        raw_range = spec.get("range")
        if raw_range is not None:
            match = INT_RANGE.fullmatch(str(raw_range))
            if not match:
                raise RegistrationError(f"{block}/{name}: invalid integer range: {raw_range}")
            lower, upper = map(int, match.groups())
        else:
            lower, upper = spec.get("min"), spec.get("max")
            if type(lower) is not int or type(upper) is not int:
                raise RegistrationError(f"{block}/{name}: integer min/max are required")
        if lower > upper:
            raise RegistrationError(f"{block}/{name}: reversed integer range")
        return tuple(map(str, range(lower, upper + 1)))
    if kind not in ENUM_VALUES and kind != "string":
        raise RegistrationError(f"{block}/{name}: unknown property type: {kind}")
    selected = spec.get("values", ENUM_VALUES.get(kind))
    if not isinstance(selected, (list, tuple)) or not selected:
        raise RegistrationError(f"{block}/{name}: nonempty values are required")
    if not all(isinstance(value, str) and PROPERTY_VALUE.fullmatch(value) for value in selected):
        raise RegistrationError(f"{block}/{name}: invalid values: {selected}")
    if kind != "string" and any(value not in ENUM_VALUES[kind] for value in selected):
        raise RegistrationError(f"{block}/{name}: values outside {kind}: {selected}")
    return tuple(dict.fromkeys(selected))


def expected_states(blocks: Any) -> dict[str, set[str]]:
    if not isinstance(blocks, dict) or not blocks:
        raise RegistrationError("kitchen.yml: blocks object is missing or empty")
    expected: dict[str, set[str]] = {}
    for block, definition in blocks.items():
        if not isinstance(block, str) or not BLOCK_ID.fullmatch(block) or not block.startswith(NAMESPACE):
            raise RegistrationError(f"kitchen.yml: unexpected block ID: {block}")
        if not isinstance(definition, dict) or not isinstance(definition.get("states"), dict):
            raise RegistrationError(f"{block}: states object is required")
        properties = definition["states"].get("properties", {})
        if not isinstance(properties, dict):
            raise RegistrationError(f"{block}: properties must be an object")
        names = sorted(properties)
        options = [values(name, properties[name], block) for name in names]
        states: set[str] = set()
        for combination in itertools.product(*options):
            suffix = ",".join(f"{name}={value}" for name, value in zip(names, combination))
            states.add(block + (f"[{suffix}]" if suffix else ""))
        expected[block] = states
    return expected


def canonical_state(state: str) -> str:
    block, separator, suffix = state.partition("[")
    if not BLOCK_ID.fullmatch(block):
        raise RegistrationError(f"invalid state ID: {state}")
    if not separator:
        return block
    if not suffix.endswith("]"):
        raise RegistrationError(f"unclosed state properties: {state}")
    properties = suffix[:-1].split(",")
    pairs = [entry.split("=", 1) for entry in properties]
    if any(len(pair) != 2 or not PROPERTY_NAME.fullmatch(pair[0]) or not PROPERTY_VALUE.fullmatch(pair[1]) for pair in pairs):
        raise RegistrationError(f"invalid state properties: {state}")
    if len({pair[0] for pair in pairs}) != len(pairs):
        raise RegistrationError(f"duplicate state property: {state}")
    return block + "[" + ",".join(sorted(properties)) + "]"


def check(instance: Path) -> dict[str, Any]:
    engine = instance / "plugins/CraftEngine"
    kitchen_path = engine / "resources/cookery_kitchen/configuration/kitchen.yml"
    config_path = engine / "config.yml"
    cache_path = engine / "cache/custom_block_states.json"
    config = load_yaml(config_path)
    block_config = config.get("block")
    if not isinstance(block_config, dict) or type(block_config.get("serverside-blocks")) is not int:
        raise RegistrationError(f"{config_path}: explicit block.serverside-blocks is required")
    capacity = block_config["serverside-blocks"]
    if capacity < 1 or capacity > 100000:
        raise RegistrationError(f"{config_path}: invalid serverside-blocks capacity: {capacity}")
    expected = expected_states(load_yaml(kitchen_path).get("blocks"))
    try:
        cache_bytes = cache_path.read_bytes()
        cache = json.loads(cache_bytes, object_pairs_hook=unique_json)
    except (OSError, UnicodeDecodeError, ValueError) as error:
        raise RegistrationError(f"{cache_path}: cannot read state cache: {error}") from error
    if not isinstance(cache, dict):
        raise RegistrationError(f"{cache_path}: expected a JSON object")
    ids: dict[int, str] = {}
    normalized: set[str] = set()
    for state, internal_id in cache.items():
        if type(internal_id) is not int or internal_id < 0 or internal_id >= capacity:
            raise RegistrationError(f"{cache_path}: internal ID outside capacity {capacity}: {state}={internal_id}")
        if internal_id in ids:
            raise RegistrationError(f"{cache_path}: duplicate internal ID {internal_id}: {ids[internal_id]} and {state}")
        ids[internal_id] = state
        canonical = canonical_state(state)
        if canonical in normalized:
            raise RegistrationError(f"{cache_path}: duplicate normalized state: {canonical}")
        normalized.add(canonical)
    actual = {state for state in normalized if state.startswith(NAMESPACE)}
    all_expected = set().union(*expected.values())
    missing, extra = sorted(all_expected - actual), sorted(actual - all_expected)
    return {
        "complete": not missing and not extra,
        "instance": str(instance),
        "capacity": capacity,
        "cacheEntries": len(cache),
        "expectedBlocks": len(expected),
        "expected": len(all_expected),
        "loaded": len(actual),
        "missing": missing,
        "extra": extra,
        "blocks": [{"id": block, "expected": len(states), "loaded": len(states & actual),
                    "missing": len(states - actual)} for block, states in sorted(expected.items())],
        "cacheSha256": hashlib.sha256(cache_bytes).hexdigest(),
        "scope": SCOPE,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path)
    args = parser.parse_args()
    try:
        report = check(args.instance)
    except RegistrationError as error:
        print(json.dumps({"error": str(error), "scope": SCOPE}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    sys.exit(main())
