# Thor Deck

Client-only Fabric mod that streams the local player's inventory to files for the
AYN Thor bottom-screen deck (Amethyst launcher). No sockets — files are the bus.

**License:** MIT

## Status

- [x] Inventory JSON dump (`inventory.json`)
- [x] Item icons (`icons/<stem>.png`) — crisp pixel-art PNGs
- [x] Tap-to-move (`command.json`) — slot swap when `seq` increases
- [x] Armor + offhand in the JSON stream (slots 36–40)
- [x] Hotbar `selected` field (0–8)

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

| File | Writer | Reader | Notes |
|---|---|---|---|
| `inventory.json` | mod | launcher | Atomic write (`tmp` + move). Written only when contents change. |
| `icons/<stem>.png` | mod | launcher | Launcher looks up `slot.icon + ".png"` with `inScaled=false`. |
| `command.json` | launcher | mod | `{"seq":N,"from":fromSlot,"to":toSlot,"button":0}`. Act only when `seq` increases. |

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

## Install

Drop the built jar in the instance `mods/` folder alongside Fabric API. The
Amethyst dual-screen deck already points at `.minecraft/thor_deck/`.
