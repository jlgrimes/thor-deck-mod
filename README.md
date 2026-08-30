# Thor Deck

Client-only Fabric mod that streams the local player's inventory to files for the
AYN Thor bottom-screen deck (Amethyst launcher). No sockets — files are the bus.

**License:** MIT

## Status

- [x] Combined `state.json` bus (monotonic seq) matching ControlDeckPresentation split-file fallback
- [x] Inventory JSON dump (`inventory.json`)
- [x] Item icons (`icons/<stem>.png`) — crisp pixel-art PNGs
- [x] Tap-to-move (`command.json`) — slot swap when `seq` increases
- [x] Armor + offhand in the JSON stream (slots 36–40)
- [x] Hotbar `selected` field (0–8)
- [x] CPU minimap (`map.png` + `map.json`) — 64×64 sample, PNG encode on worker
- [x] HUD (`hud.json` + nested in `state.json`) — health, hunger, pos, biome, time, effects
- [x] Chat ring (`chat.json` + nested in `state.json`) — last 40 lines

## Target

| | |
|---|---|
| Minecraft | 1.21.11 |
| Mappings | official Mojang |
| Loader | Fabric Loader 0.19.3+ |
| API | Fabric API 0.141.4+1.21.11 |
| Java | 21 |
| Environment | `client` |

Build with the existing `build.gradle` / `gradle.properties`. Do not change them
unless you must.

## File contract (`<gameDir>/thor_deck/`)

ControlDeckPresentation on `feat/dualscreen-deck-v2` prefers `state.json` (monotonic
`seq`) and falls back to the split files. The mod writes both. Game dir is
`<gameDir>/thor_deck/` (Pojav: `.minecraft/thor_deck`); the launcher also probes
`thor_deck/` and `deck/`.

| File | Writer | Reader | Notes |
|---|---|---|---|
| `state.json` | mod | launcher | Primary bus. `{seq, map, inventory, chat, hud}`. Atomic write. |
| `inventory.json` | mod | launcher | Split-file fallback. Atomic write. Written only when contents change. |
| `icons/<stem>.png` | mod | launcher | Launcher looks up `slot.icon + ".png"` with `inScaled=false`. |
| `command.json` | launcher | mod | `{"seq":N,"from":fromSlot,"to":toSlot,"button":0}`. Act only when `seq` increases. |
| `map.png` | mod | launcher | 128×128 ARGB PNG. 1 pixel = 1 block. Player is the center pixel (64, 64). |
| `map.json` | mod | launcher | `{seq, x, y, z, yaw, dim, w, h, scale, biome}`. `scale` is 1. |
| `hud.json` | mod | launcher | Health, hunger, air, xp, armor, pos, biome, time, weather, effects. |
| `chat.json` | mod | launcher | `{seq, lines:[{from, text, kind}]}` last 40. `kind` is `chat`/`system`/`action`. |

### `state.json`

```json
{"seq":12,"map":{"seq":4,"x":1.20,"y":64.00,"z":-8.40,"yaw":90.00,"dim":"minecraft:overworld","w":128,"h":128,"scale":1,"biome":"plains"},"inventory":{"size":41,"selected":0,"slots":[{"i":0,"id":"minecraft:dirt","n":"Dirt","c":2,"icon":"dirt"}]},"chat":{"seq":3,"lines":[{"from":"Steve","text":"hello","kind":"chat"}]},"hud":{"seq":12,"hp":20.00,"maxHp":20.00,"hunger":20,"x":1.20,"y":64.00,"z":-8.40,"yaw":90.00}}
```

HUD fields in `state.json` / `hud.json`: `hp`, `hunger`, `x`/`y`/`z`, `yaw` (plus
maxHp, saturation, air, xp, level, armor, pitch, biome, dim, time, dayTime, weather, effects).

### `inventory.json`

```json
{"size":41,"selected":0,"slots":[
  {"i":0,"id":"minecraft:dirt","n":"Dirt","c":2,"icon":"dirt"}
]}
```

- `size` — always 41 (hotbar + main + armor + offhand)
- `selected` — current hotbar slot 0–8
- `i` — deck slot index (see map below)
- `id` — registry id (`namespace:path`)
- `n` — hover name
- `c` — stack count
- `icon` — filename stem in `icons/` (no `.png`)

Empty slots are omitted. Old fields are kept; `icon` and `selected` are additions.

### Slot map

| Index | Region |
|---|---|
| 0–8 | Hotbar |
| 9–35 | Main inventory (3×9) |
| 36 | Boots (`EquipmentSlot.FEET`) |
| 37 | Leggings (`EquipmentSlot.LEGS`) |
| 38 | Chestplate (`EquipmentSlot.CHEST`) |
| 39 | Helmet (`EquipmentSlot.HEAD`) |
| 40 | Offhand (`EquipmentSlot.OFFHAND`) |

Vanilla 1.21.5+ split armor/offhand into `EntityEquipment`. This mod still
exposes them at 36–40 so the launcher's existing indices keep working.

### Icon stems

Stable unique stem per item id: the path after the namespace, `/` replaced by
`_`. Non-`minecraft` namespaces are prefixed (`modid__path`). A custom anvil
name appends a hex hash so renamed stacks don't collide.

### `command.json`

The launcher writes a new object whenever the user drops an item onto another
cell. The mod polls every client tick (mtime + `seq`). Duplicate / stale `seq`
values are ignored. Missing file, partial JSON, and `player == null` are no-ops.

`button: 0` is a full-stack swap (left click). The move is applied on the client
tick thread via `Inventory.setItem` / container click (see NOTES.md).


The launcher should show **Map** as the default bottom-screen tab. Inventory,
HUD, and chat are additional tabs on the same file bus.

### `map.png` / `map.json`

CPU-sampled top-down surface. **Not** an FBO blit of the in-game map item
(that path is broken on Pojav GLES). Every 20 ticks (~1s), or when the player
moved ≥ 2 blocks or yaw changed ≥ 15°, but never more often than every 10 ticks,
and never while a PNG write is already in flight:

1. On the client thread, sample a 64×64 grid (every other column) using
   `Heightmap.Types.WORLD_SURFACE` plus a short water-depth peek — not a
   128×128×24 walk.
2. Nearest-neighbor upscale to 128×128 (player cell stays (64, 64)).
3. Shade land darker if it is lower than its north neighbor (vanilla map).
4. Encode PNG + write `map.png` / `map.json` / `state.json` on a daemon worker.

Paused game, null world, and null player skip the write. Exceptions are
swallowed so a map miss never crashes the game. Nether and End still sample
(their `MapColor`s just look different).

```json
{"seq":1,"x":1.20,"y":64.00,"z":-8.40,"yaw":90.00,"dim":"minecraft:overworld","w":128,"h":128,"scale":1,"biome":"plains"}
```

### `hud.json`

Written every 5 ticks from `LocalPlayer`. `absorption` is optional extra.
`time` is one of `day` / `sunset` / `night` / `sunrise`. `weather` is
`clear` / `rain` / `thunder`.

```json
{"seq":1,"hp":20.00,"maxHp":20.00,"absorption":0.00,"hunger":20,"saturation":5.00,"air":300,"xp":0.42,"level":12,"armor":0,"x":1.20,"y":64.00,"z":-8.40,"yaw":90.00,"pitch":12.00,"biome":"plains","dim":"minecraft:overworld","time":"day","dayTime":6000,"weather":"clear","effects":[{"id":"minecraft:speed","amp":0,"secs":12}]}
```

### `chat.json`

Fabric `ClientReceiveMessageEvents.CHAT` + `GAME`. Ring of 40. Write is
debounced 2 ticks so spam does not hammer the disk. `§` formatting codes are
stripped.

```json
{"seq":1,"lines":[{"from":"Steve","text":"hello","kind":"chat"}]}
```

## Install

Drop the built jar in the instance `mods/` folder alongside Fabric API. The
Amethyst dual-screen deck already points at `.minecraft/thor_deck/`.
