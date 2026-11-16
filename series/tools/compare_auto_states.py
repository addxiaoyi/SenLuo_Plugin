"""Compare CE auto-state slots by appearance ID; this provides no client proof."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any
from zipfile import BadZipFile, ZipFile


DEFAULT_BLOCK = "minecraft:brown_mushroom_block"
SCOPE = "CE visual cache joined to generated variants by appearance; no server placement or client proof"
PROPERTY = re.compile(r"[a-z0-9_]+=[a-z0-9_-]+")
RESOURCE = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")


class ComparisonError(ValueError):
    pass


@dataclass(frozen=True)
class Appearance:
    state: str
    model: dict[str, Any]


@dataclass(frozen=True)
class Snapshot:
    appearances: dict[str, Appearance]
    cache_sha256: str
    pack_sha256: str


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, entry in pairs:
        if key in value:
            raise ComparisonError(f"duplicate JSON key: {key}")
        value[key] = entry
    return value


def json_object(content: bytes, owner: str) -> dict[str, Any]:
    try:
        value = json.loads(content, object_pairs_hook=unique_object)
    except (UnicodeDecodeError, ValueError) as error:
        raise ComparisonError(f"{owner}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ComparisonError(f"{owner}: expected a JSON object")
    return value


def resource_id(value: str) -> str:
    value = value if ":" in value else "minecraft:" + value
    if not RESOURCE.fullmatch(value) or ".." in value or value.split(":", 1)[1].startswith("/"):
        raise ComparisonError(f"invalid resource ID: {value}")
    return value


def selector(value: str) -> str:
    if not value:
        return ""
    properties = value.split(",")
    names = [entry.split("=", 1)[0] for entry in properties]
    if not all(PROPERTY.fullmatch(entry) for entry in properties) or len(names) != len(set(names)):
        raise ComparisonError(f"invalid or duplicate state properties: {value}")
    return ",".join(sorted(properties))


def block_state(value: str) -> tuple[str, str]:
    if "[" not in value:
        return resource_id(value), ""
    owner, properties = value.split("[", 1)
    if not properties.endswith("]"):
        raise ComparisonError(f"invalid block state: {value}")
    return resource_id(owner), selector(properties[:-1])


def model_variant(value: Any, owner: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) - {"model", "x", "y", "uvlock"}:
        raise ComparisonError(f"{owner}: unsupported variant; expected model/x/y/uvlock")
    if not isinstance(value.get("model"), str):
        raise ComparisonError(f"{owner}: variant has no model ID")
    model: dict[str, Any] = {"model": resource_id(value["model"])}
    for axis in ("x", "y"):
        angle = value.get(axis, 0)
        if type(angle) is not int or angle not in (0, 90, 180, 270):
            raise ComparisonError(f"{owner}: invalid {axis} rotation: {angle}")
        model[axis] = angle
    uvlock = value.get("uvlock", False)
    if not isinstance(uvlock, bool):
        raise ComparisonError(f"{owner}: invalid uvlock: {uvlock}")
    model["uvlock"] = uvlock
    return model


def snapshot(instance: Path, block: str) -> Snapshot:
    engine = instance / "plugins/CraftEngine"
    cache_path = engine / "cache/visual_block_states.json"
    pack_path = engine / "generated/resource_pack.zip"
    namespace, name = block.split(":", 1)
    asset = f"assets/{namespace}/blockstates/{name}.json"
    try:
        cache_bytes = cache_path.read_bytes()
        pack_bytes = pack_path.read_bytes()
        with ZipFile(pack_path) as archive:
            if archive.namelist().count(asset) != 1:
                raise ComparisonError(f"{pack_path}: missing or duplicate {asset}")
            definition = json_object(archive.read(asset), f"{pack_path}!/{asset}")
    except (OSError, BadZipFile) as error:
        raise ComparisonError(f"{instance}: cannot read CE cache/resource pack: {error}") from error
    variants = definition.get("variants")
    if not isinstance(variants, dict):
        raise ComparisonError(f"{asset}: missing variants object")
    normalized: dict[str, dict[str, Any]] = {}
    for raw_selector, variant in variants.items():
        key = selector(raw_selector)
        if key in normalized:
            raise ComparisonError(f"{asset}: duplicate normalized selector: {key}")
        normalized[key] = model_variant(variant, f"{asset}/{key}")
    appearances: dict[str, Appearance] = {}
    for appearance, state in json_object(cache_bytes, str(cache_path)).items():
        if not appearance or not isinstance(state, str):
            raise ComparisonError(f"{cache_path}: invalid appearance/state pair: {appearance}")
        owner, properties = block_state(state)
        if owner != block:
            continue
        if properties not in normalized:
            raise ComparisonError(f"{instance}: missing variant for {appearance}: {state}")
        canonical = owner + (f"[{properties}]" if properties else "")
        appearances[appearance] = Appearance(canonical, normalized[properties])
    if not appearances:
        raise ComparisonError(f"{cache_path}: no appearances mapped to {block}")
    return Snapshot(appearances, hashlib.sha256(cache_bytes).hexdigest(), hashlib.sha256(pack_bytes).hexdigest())


def compare(left: Path, right: Path, block: str = DEFAULT_BLOCK) -> dict[str, Any]:
    block = resource_id(block)
    first, second = snapshot(left, block), snapshot(right, block)
    names = first.appearances.keys()
    if names != second.appearances.keys():
        missing_left = sorted(second.appearances.keys() - names)
        missing_right = sorted(names - second.appearances.keys())
        raise ComparisonError(f"appearance sets differ: missing left={missing_left}; missing right={missing_right}")
    differences = [{"appearance": name, "left": first.appearances[name].model,
                    "right": second.appearances[name].model}
                   for name in sorted(names) if first.appearances[name].model != second.appearances[name].model]
    return {
        "equivalent": not differences,
        "block": block,
        "appearances": len(names),
        "changedSlots": [name for name in sorted(names)
                         if first.appearances[name].state != second.appearances[name].state],
        "differences": differences,
        "inputs": [{"instance": str(path), "cacheSha256": value.cache_sha256, "packSha256": value.pack_sha256}
                   for path, value in ((left, first), (right, second))],
        "scope": SCOPE,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path, help="First isolated server directory")
    parser.add_argument("right", type=Path, help="Second isolated server directory")
    parser.add_argument("--block", default=DEFAULT_BLOCK)
    args = parser.parse_args()
    try:
        report = compare(args.left, args.right, args.block)
    except ComparisonError as error:
        print(json.dumps({"error": str(error), "scope": SCOPE}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["equivalent"] else 1


if __name__ == "__main__":
    sys.exit(main())
