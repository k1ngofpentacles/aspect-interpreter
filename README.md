# Aspect Interpreter

An add-on for [Thaumaturge](https://www.curseforge.com/projects/1628024) on NeoForge 1.21.1. It gives **aspects to every item and entity that has none**, by interpreting each object's name, material, recipes and mob category against the patterns of the aspects your pack already assigns.

It never replaces or reduces an existing aspect. It does three things:

- **Fills gaps:** it answers when Thaumaturge would otherwise have nothing, including what the item is for (a barrel holds things, so it gets vacuos).
- **Enriches mobs:** mobs are the richest source of aspects and the hardest to process. Each mob gains aspects from where it lives (a stray gets gelum), the dimension it comes from (a ghast gets infernum and spiritus; End mobs get alienis), how it behaves (undead get mortuus) and what it drops. Fantasy creatures gain more than ordinary animals, and bosses and rare mobs carry larger totals.
- **Keeps families consistent:** crafted items lose defining aspects when no ingredient carries them (vanilla candles are lux, but crafted candles from other mods weren't). Crafted and interpreted items now share the 1–2 key aspects their hand-written relatives agree on, and bright light sources carry lux.
- **Credits craftsmanship:** crafted items from other mods, made at the crafting table, gain ordo or fabrico for each step beyond the first.

Hand-written aspects are canon. Vanilla, Thaumaturge and its add-ons, Create and its add-ons, and any mod that ships its own aspect files are learned from but never added to, except that their mobs are enriched. Every addition is sized to match how sparingly the original mods use each aspect.

## Requirements
- Minecraft 1.21.1
- NeoForge 21.1.x
- Thaumaturge 1.1.0 or newer

Optional: Thaumic Additions and Madman's Dreams. Their extra aspects are used when installed and skipped when not.

## What happens when you install it
1. **First launch (the only one that matters):** Thaumaturge's aspect index is rebuilt once with interpreted aspects included. The interpreter first runs Thaumaturge's own derivation with itself switched off and learns from that clean result, all inside the same build. In a ~10,000-item pack this added well under a second to the build. Thaumaturge then caches the result, so later launches load it from disk and cost nothing.
2. **Each build:** every guess is listed in `config/aspect_interpreter/assignments.txt` with its confidence and its strongest evidence.

## How it decides
The model reads what your pack already does and applies the same pattern to the gaps. In packs measured during development, assignments follow material first and theme second: a crimson table is a plant, not a demon. Each guess blends four kinds of evidence:

- **Neighbours:** similarly named objects that already have aspects (`andesite_lower_roof` ≈ `andesite_roof`).
- **Word profiles:** the aspect mix the pack associates with each word in the name.
- **Meaning:** a table of what each aspect means, with each word's weight calibrated against your pack's own assignments.
- **Physical evidence:** recipes of any type, block material, food, tool or armour data, and mob category and size.
- **Where a mob comes from:** its biomes, its dimension, its behaviour and its drops. The Nether is treated as an underworld rather than an alien world, so its natives get infernum, not alienis. Items that come from a mob of another dimension (an End fish, its bucket) share its flavour; blocks never do.

[`docs/ALGORITHM.md`](docs/ALGORITHM.md) has the full method and measured accuracy. [`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md) explains how Thaumaturge and its add-ons assign aspects, and how each rule here follows from that. On held-out items from the author's pack, it picked the right main aspect about 73% of the time.

## Configuration
The config file is `config/aspect_interpreter.json`:

| Option | Default | Meaning |
|---|---|---|
| `items` / `entities` | `true` | Turn either half off |
| `learnFromThaumaturgeCache` | `true` | Also learn from Thaumaturge's own derived values (computed with this mod switched off, so its guesses never feed back) |
| `mobEnrichment` | `true` | Enrich mobs from habitat, dimension, behaviour and drops |
| `canonNamespaces` | vanilla, Thaumaturge family, Create family | Mods that are only read, never added to (hand-writing mods are detected automatically) |
| `familyConsistency` | `true` | Give crafted and interpreted items their family's key aspects (candles → lux) |
| `functionLayer` | `true` | Add a missing function aspect to interpreted items |
| `craftingComplexity` | `true` | Add craftsmanship to crafting-table items of non-canon mods |
| `dimensionContext` | `true` | Use the dimension a mob comes from |
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

## In-game testing

The manual checks for this mod are the FTB Quests chapter **QA: Aspect Interpreter** (`qa_aspect_interpreter`, spec `tools/qa-quests/specs/aspect_interpreter.json` in the workspace); nothing is listed here.
