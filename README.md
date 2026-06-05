# Thor Deck

A tiny Fabric **client** mod that streams the player's inventory to a file so it can be displayed
on the **AYN Thor's bottom screen** by a [dual-screen fork of the Amethyst launcher](https://github.com/jlgrimes/Amethyst-Android).

## How it works

Every few client ticks, the mod writes the local player's inventory to
`<gameDir>/thor_deck/inventory.json` (atomically, only when it changes):

```json
{ "size": 43, "slots": [
    { "i": 0, "id": "minecraft:dirt", "n": "Dirt", "c": 2 }
] }
```

The launcher reads that file and renders the inventory on the secondary display. File-based IPC
keeps the mod fully decoupled from the launcher — no sockets, same device.

## Build

Requires JDK 21. Loom 1.16.3 needs Gradle 9.5+ (handled by the wrapper).

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew build
# -> build/libs/thor-deck-mod-*.jar
```

## Install

Drop the built jar **and** [Fabric API](https://modrinth.com/mod/fabric-api) into your `mods/`
folder on a **Fabric 1.21.11** instance.

> Targets **1.21.11**: newer versions (the 26.x line) currently ship no Mojang or Yarn mappings,
> so they can't be modded yet.

## Status

- [x] Stream inventory (slot / id / name / count) to file
- [ ] Real item icons (render each stack to an image)
- [ ] Tap-to-move (receive slot clicks back from the launcher)

## License

MIT
