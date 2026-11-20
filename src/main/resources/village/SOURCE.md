# Archived village kitchen sources

The five gzip NBT files in `source/` are unmodified resources from
KaleidoscopeMods/KaleidoscopeCookery, commit
`2f4e386ce23f49a385ddf003c67fc6415c55417a`:

`src/main/resources/data/kaleidoscope_cookery/structures/village/houses/<biome>_kitchen.nbt`

Upstream repository: https://github.com/KaleidoscopeMods/KaleidoscopeCookery

The packaged `village-loot.json` records each source path, exact SHA-256,
original DataVersion, palette, block-entity data and jigsaw metadata.
`VillageStructureSources` validates that already byte-pinned manifest and all
five archived files before returning defensive copies. The archive keeps its
original bytes; it has not been migrated or installed as a runtime template.

These resources are a source-archive prerequisite. They do not add kitchens to
natural village pools or resolve the online reload/generation barrier.
Upstream attribution and distribution review remain governed by
`docs/license-policy.json` and `series/docs/UPSTREAM-LICENSES.md` in this project.
