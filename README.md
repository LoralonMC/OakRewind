# OakRewind

Rewind explosions with beautiful rebuilding animations.

Explosions happen normally — entities take damage, players get knocked back — but the
destroyed blocks flow back into place afterwards, block by block, with configurable
patterns, particles, and sounds. Nothing is lost and nothing can be duplicated.

## Features

- Rebuilds explosion damage from creepers, TNT, TNT minecarts, ghast fireballs, wither
  skulls, wither spawns, and end crystals — each type individually toggleable
- Four rebuild patterns: `BOTTOM_UP`, `TOP_DOWN`, `CENTER_OUT`, `RANDOM`
- Configurable rebuild speed with an accelerating delay curve, plus particle effects
- Rewinds decoration entities too: armor stands, item frames, glow item frames, and
  paintings survive the blast with all their items and data intact — they're hidden during
  the rebuild and reappear when the blocks are back (no drops, no duplication, same entity)
- Container contents, signs, spawners, and all other block data are restored exactly
- TNT caught in a blast still primes and chains like vanilla
- Players and mobs are lifted on top of rebuilding blocks, never sealed inside
- Respects explosions cancelled or trimmed by protection plugins (WorldGuard, Lands, …)
- Compatible with invisible-item-frame plugins

## Requirements

- Paper 26.1.2+
- Java 25+

## Installation

1. Drop `OakRewind.jar` into your server's `plugins` folder
2. Restart the server
3. Adjust `plugins/OakRewind/config.yml` to taste and run `/oakrewind reload`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/oakrewind` | Show usage | — |
| `/oakrewind reload` | Reload the configuration | `oakrewind.reload` |

Aliases: `/or`, `/rewind`

## Permissions

| Node | Description | Default |
|------|-------------|---------|
| `oakrewind.*` | All OakRewind permissions | op |
| `oakrewind.reload` | Reload the configuration | op |

## Configuration

Key settings in `config.yml`:

- `enable-rebuild` — master switch for rebuilding
- `enabled-explosion-types` — which explosion sources are rebuilt
- `restore-entities` — decoration-entity rewind toggle and protected types
- `rebuild.pattern`, `rebuild.initial-delay`, `rebuild.delay-falloff`,
  `rebuild.minimum-delay` — animation order and speed
- `rebuild.particles` — particle type and density

Player-facing messages live in `messages.yml` (MiniMessage format). See the comments in
both files for full details.
