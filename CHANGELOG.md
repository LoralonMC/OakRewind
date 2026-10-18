# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.4] - 2026-10-18

### Fixed

- An explosion that caught a piston mid-push left permanent invisible blocks behind. A block
  being pushed is a `MOVING_PISTON` whose block entity holds the block in motion, and a
  `BlockState` snapshot does not carry that entity, so the rebuild restored a bare
  `MOVING_PISTON` that never completed. It looked like a hole but could not be built into,
  and survived chunk unloads and restarts. Blocks mid-push are now left out of the blast and
  the rebuild, so the push finishes on the next tick as if nothing happened. Applies to
  withers too.

## [1.1.3] - 2026-09-14

### Fixed

- Containers no longer duplicate their contents. A creeper breaking a chest spilled the
  contents on the floor and then the chest came back with them still inside. Removing a
  container drops its contents as a side effect of the block entity going away, and
  `setType`'s `applyPhysics` flag does not suppress that, it only skips neighbour updates.
  The live container is now emptied before the block is aired; the snapshot is an
  independent copy, so the contents still return with the block.

  The drop was ours, not the server's. `EntityExplodeEvent` fires before blocks are
  removed and the snapshot loop skips blocks that are already air, so if the server had
  destroyed the chest first there would have been nothing to rebuild at all.

  Only visible on Paper 26.2 and later, which is why this survived since 1.0.0.

  Half of a double chest is emptied with `getBlockInventory`, not `getInventory`: the
  latter returns the combined inventory and would empty the other half too, which may not
  even be in the explosion.

## [1.1.2] - 2026-07-29

### Fixed

- Blocks a wither smashes in melee are now rebuilt. A wither breaks blocks three ways and
  only two of them are explosions: its spawn blast and its skulls fire `EntityExplodeEvent`
  and were rebuilt, but the box it clears by hand is mob griefing and fires
  `EntityChangeBlockEvent` once per block, which nothing listened for. Those blocks stayed
  gone permanently while the skull craters around them healed, which read as a wither being
  usable to grief claims (reported via Discord bug ticket). The grief event is cancelled
  rather than observed, because it fires before the break and the break drops items —
  rebuilding afterwards would have handed out a free copy of every block the wither touched.
  Covered by the existing `WITHER` entry in `enabled-explosion-types`.
- Players caught in a rebuilding crater are no longer teleported to the surface. The
  upward-only ejection scan assumed open sky above; inside a cave the column above is
  solid rock, so anyone standing where a block restored was sent to ground level
  (reported via the first Discord bug ticket). Rebuilds now skip a block while a living
  entity occupies it and retry it last, so the wall no longer materializes inside anyone;
  if someone parks in the last open spot (~100 retries at the min delay, a few seconds),
  the block places and they are moved to the nearest open spot instead — same level and
  solid footing preferred, straight-up scan only as a last resort. Applies to mobs too.

## [1.1.0] - 2026-07-16

### Added

- Rewind armor stands, item frames, glow item frames and paintings caught in an explosion.
  They no longer break and drop their contents; instead they are hidden for the duration of
  the rebuild and reappear once the blocks are back. Configure under `restore-entities`.
  Hanging entities are also protected when the blast never touched them but the rebuild
  removes the block holding them up, and when another plugin already shields them from
  explosions, so they cannot pop off mid-rebuild. Compatible with invisible-item-frame
  plugins (e.g. InvisibleItemFramesLite) that replace the frame on break — the frame is
  kept and rewound instead of being replaced and dropped. Entities caught by several
  overlapping explosions stay protected until the last of those rebuilds completes.
- Add `config-version` to config.yml so future updates can migrate settings automatically

### Changed

- Migrate from Configurate to OakheartLib for config management and message handling
- Move messages from config.yml to separate messages.yml (auto-migrated on first load)
- Replace Brigadier boilerplate with CommandRegistrar from OakheartLib
- Sync config.yml comment updates onto existing configs on startup, leaving any comments
  you wrote yourself untouched

### Fixed

- Finishing rebuilds on shutdown or `/oakrewind reload` now places the remaining blocks
  silently. Previously every placement sound and particle fired in the same tick, which
  could mean thousands of packets at once after a large explosion.
- Players and mobs standing in a crater are now lifted on top of rebuilt blocks instead of
  being sealed inside them and suffocating.
- TNT blocks caught in a rebuilt explosion now prime and chain like vanilla. Previously they
  were rebuilt as inert blocks, silently breaking TNT chain reactions near any rebuilt explosion.
- Correct the `MINECART_TNT` explosion type to `TNT_MINECART`. The old name did not exist,
  so TNT minecart explosions were never rebuilt and logged an "Invalid explosion type"
  warning on startup. Existing configs are corrected automatically.
- The particle types suggested in config.yml (`EXPLOSION_NORMAL`, `SMOKE_NORMAL`,
  `VILLAGER_HAPPY`, `ENCHANTMENT_TABLE`) no longer exist in modern Minecraft; the comment
  now lists the current names (`POOF`, `SMOKE`, `HAPPY_VILLAGER`, `ENCHANT`).
- An empty `enabled-explosion-types` list is now respected (nothing is rebuilt, with a
  startup warning) instead of silently rebuilding creeper explosions anyway.

## [1.0.0] - 2025-02-21

### Added

- Initial release
