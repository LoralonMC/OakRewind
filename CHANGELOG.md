# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Rewind armor stands, item frames, glow item frames and paintings caught in an explosion.
  They no longer break and drop their contents; instead they are hidden for the duration of
  the rebuild and reappear once the blocks are back. Configure under `restore-entities`.
  Hanging entities are also protected when the blast never touched them but the rebuild
  removes the block holding them up, and when another plugin already shields them from
  explosions, so they cannot pop off mid-rebuild. Compatible with invisible-item-frame
  plugins (e.g. InvisibleItemFramesLite) that replace the frame on break — the frame is
  kept and rewound instead of being replaced and dropped.
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

## [1.0.0] - 2025-02-21

### Added

- Initial release
