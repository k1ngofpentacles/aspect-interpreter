# Aspect Interpreter

An add-on for [Thaumaturge](https://www.curseforge.com/projects/1628024) on NeoForge 1.21.1. It gives **aspects to every item and entity that has none**, by interpreting each object's name, material, recipes and mob category against the patterns of the aspects your pack already assigns.

It never replaces or reduces an existing aspect. It does two things:

- **Fills gaps:** it answers when Thaumaturge would otherwise have nothing.
- **Adds what recipes can't:** Thaumaturge's recipe rule only conserves materials, so crafted items never gain aspects. Aspect Interpreter adds:
  - **Function:** a barrel holds things, so it gets vacuos.
  - **Craftsmanship:** stairs are shaped wood, so they get ordo.
  - **Dimension:** everything from the Nether, the End or another world gets a touch of alienis and that world's flavour. This is the only addition hand-written entries receive.

Every addition is sized to match how sparingly the original mods use each aspect.

## Requirements
- Minecraft 1.21.1
- NeoForge 21.1.x
- Thaumaturge 1.1.0 or newer

Optional: Thaumic Additions and Madman's Dreams. Their extra aspects are used when installed and skipped when not.

## What happens when you install it
1. **First launch:** Thaumaturge's aspect index is rebuilt once with interpreted aspects included. In a ~10,000-item pack this added about 0.3 s to the build. Thaumaturge then caches the result, so later launches load it from disk and cost nothing.
2. **Second launch (fresh installs only):** one more rebuild, now learning from the full index Thaumaturge wrote on the first launch.
3. **Each build:** every guess is listed in `config/aspect_interpreter/assignments.txt` with its confidence and its strongest evidence.

## How it decides
The model reads what your pack already does and applies the same pattern to the gaps. In packs measured during development, assignments follow material first and theme second: a crimson table is a plant, not a demon. Each guess blends four kinds of evidence:

- **Neighbours:** similarly named objects that already have aspects (`andesite_lower_roof` ≈ `andesite_roof`).
- **Word profiles:** the aspect mix the pack associates with each word in the name.
- **Meaning:** a table of what each aspect means, with each word's weight calibrated against your pack's own assignments.
- **Physical evidence:** recipes of any type, block material, food, tool or armour data, and mob category and size.
- **How it was made:** each crafting step beyond the first refinement adds craftsmanship of its kind (ordo for regular forms, machina for machine work, fabrico for assembly).
- **Where it comes from:** the dimension an object spawns, generates or drops in. Each dimension's character is learned from its already-assigned natives, so an End fish picks up a little alienis.

[`docs/ALGORITHM.md`](docs/ALGORITHM.md) has the full method and measured accuracy. [`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md) explains how Thaumaturge and its add-ons assign aspects, and how each rule here follows from that. On held-out items from the author's pack, it picked the right main aspect about 73% of the time.

## Configuration
The config file is `config/aspect_interpreter.json`:

| Option | Default | Meaning |
|---|---|---|
| `items` / `entities` | `true` | Turn either half off |
| `learnFromThaumaturgeCache` | `true` | Also learn from Thaumaturge's resolved index, excluding this mod's own guesses |
| `functionLayer` | `true` | Add a missing function aspect (container → vacuos, lamp → lux...) |
| `craftingComplexity` | `true` | Add craftsmanship to recipe-derived items |
| `dimensionContext` | `true` | Use the dimension an object comes from as evidence |
| `baselineDimensions` | `["minecraft:overworld"]` | Dimensions with no special character |
| `dimensionAspects` | `{}` | Pin a dimension's signature, e.g. `{"minecraft:the_end": {"thaumaturge:alienis": 1.0}}` |
| `minConfidence` | `0.15` | Guesses below this confidence are left unassigned |
| `writeReport` | `true` | Write `assignments.txt` |
| `excludedNamespaces`, `excludedIds`, `excludedWords` | technical IDs | Never interpret these |

To override a guess, give the object a normal `base_aspects` or `entity_aspects` data-map entry in a datapack. Real entries always win.

## Modpacks
You're welcome to include it in modpacks; it's MIT licensed.

## Building from source
Requires Java 21 and Gradle 8.14 or newer. The Gradle wrapper jar isn't committed; run `gradle wrapper` once to create it.

Thaumaturge is *All Rights Reserved*, so it isn't included here. Either:
- put the Thaumaturge jar in `libs/` (it's gitignored), or
- set `thaumaturge_file_id` in `gradle.properties` to its CurseForge file ID, which resolves it through cursemaven. CI needs this.

Then run `gradle build`. The jar is written to `build/libs/`.

## License
This project is MIT licensed (see [`LICENSE`](LICENSE)).

It contains no code, assets or data from Thaumaturge or any other mod. At runtime it reads the player's own installed data. Thaumaturge is the work of Leclowndu93150 and contributors. This project is not affiliated with or endorsed by them.
