# Aspect Interpreter: how it assigns aspects

Aspect Interpreter is a NeoForge 1.21.1 add-on for Thaumaturge 1.1+. It gives aspects to any item or entity type that has none, and adds craftsmanship to recipe-derived items. It never changes hand-written (`base_aspects` / `entity_aspects`) entries.

The reasoning behind each rule, drawn from Thaumaturge's own data, is in [`PHILOSOPHY.md`](PHILOSOPHY.md).

## Where it plugs in

| Target | Hook | What it changes |
|---|---|---|
| Recipe-derived items | Mixin on the return of Thaumaturge's `CraftingAspectContributor.derive` | Adds craftsmanship after Thaumaturge has derived an item from a crafting recipe. Optional: it turns itself off if Thaumaturge changes. |
| Items | `IAspectRecipeContributor` registered through `RegisterAspectContributorsEvent` | Thaumaturge asks contributors in order and keeps the first non-empty answer. Event contributors run after its own crucible, infusion, crafting and cooking contributors, so this one is only asked about items with no base aspects and no recipe Thaumaturge could resolve. |
| Entities | Mixin on the return of `EntityAspects.of(EntityType)` | Every entity read (scanning, goggles, wand charging, nodes, liquid death, infusion enchantments) goes through this method. The mixin only acts when it returns empty. |

Thaumaturge resolves recipes recursively. So when the interpreter fills in a leaf item, Thaumaturge's own recipe logic can then finish every item crafted from it.

## What existing assignments show (observed in a 6,920-item resolved index)

1. **Material comes first, theme second.** A crimson table is herba (wood), not infernum. A blaze burner is mostly metallum with ignis as a minor aspect. Function and theme usually appear as smaller secondary aspects.
2. **Variants share aspects.** Colour, shape and size words (`white`, `slab`, `upper`, `roof`) carry almost no aspect information. Material words (`andesite`, `oak`, `brass`) carry almost all of it.
3. **Shape sets the amount.** A block holds more than a slab, and a nugget less than an ingot. This is learned from neighbours rather than hard-coded.
4. **Addon aspects are rare.** Infernum, draco, visum and similar aspects appear only on items their own addons define. The interpreter learns this and seldom uses them.

## The meaning of each aspect

The file `data/aspect_interpreter/lexicon.json` gives each of the 46 aspects a short description of what it covers and a family of name fragments that evoke it, about 2,300 in total. Examples:

- **metallum (Metal):** iron, brass, ingot, plating, chain
- **exanimis (Undead):** zombie, husk, drowned
- **somnium (Dream):** dream, bed, slumber

A separate list of neutral words (colours, shapes, sizes) is ignored for meaning.

## The algorithm, per unassigned ID

**1. Tokenise.** Split the ID path on `_ / - .`, singularise, and strip trailing digits. For example, `mcwroofs:andesite_lower_roof` becomes `andesite`, `lower`, `roof`.

**2. Gather evidence.** Each source produces an aspect distribution and a weight.

| Source | What it does | Weight |
|---|---|---|
| Neighbours | The 5 most similar assigned IDs, scored by weighted word overlap (weight = word rarity × word consistency, so materials outweigh shapes). They also supply the amount. | 1.5 × best similarity |
| Word profiles | The average aspect mix of all assigned IDs containing each word, discounted for rare or inconsistent words | 0.6 |
| Meaning | Lexicon matches, longest phrase first (`dark_oak` consumes `dark`). Each keyword is calibrated against how often its aspect actually appears on assigned items containing it. | 0.5, scaled up when no close neighbour exists |
| Recipes (items) | Any recipe type producing the item whose ingredients resolve (≥50%), at 75% of the inputs. | 2.0 × fraction resolved |
| Material (items) | Block sound type (wood, stone, metal, glass, wool...), light level, food, tool, attack damage, armour, fire resistance | up to 0.8 |
| Creature (spawn eggs) | The entity's real or interpreted aspects, plus victus | 1.2 |
| Dimension | The leading aspects of the dimension the object comes from (see below) | 0.5 × share of the object's association with that dimension |
| Category (entities) | Learned from the pack's own `entity_aspects` per mob category (only aspects at least 40% of members share), falling back to monster → aversio/bestia; creature → bestia/terra; water → aqua/bestia; ambient → bestia/volatus/tenebrae; fire immunity adds a little ignis, for living creatures only; non-living entities (boats, minecarts, arrows, projectiles) are read as the item they are or most resemble | 0.7 (item match 1.5) |

**2b. Native objects keep their dimension.** An object at least 60% associated with a non-baseline dimension always gets at least 1 point (or 10% of its total) of that dimension's guaranteed aspect, replacing its weakest aspect if it already has 4. The guaranteed aspect is the dimension's defining aspect if the pack has it, otherwise its learned lead.

**2c. Craftsmanship.** If the item has recipes, it gets craftsmanship as described under *Crafting complexity* below.

**3. Blend.** Take the weighted average of the distributions. Keep aspects holding at least 12% of the mass, up to 4.

**4. Size the amount.** Use the neighbours' typical total, else the word profiles' median, else 8 for items. For entities, use 25 scaled by body volume.

**5. Score confidence.** Confidence = total evidence weight ÷ 2, capped at 1. Below `minConfidence` (default 0.15), the ID is left unassigned.

**6. Check the aspects exist.** Aspects the running pack doesn't define (for example, an addon aspect seen in a data map of a mod that is not loaded) are dropped, and the rest are rescaled.

## Dimension context

Some meaning comes from where a thing comes from: a cod from the End should be a little alien, whatever its name says. At each server start and reload, the mod reads, for every dimension:

- **Creatures:** which ones its biomes spawn, and which ones its structures spawn (via spawn overrides).
- **Blocks:** which ones its terrain and world features place, found by walking each feature's encoded configuration, including nested features.
- **Items:** which ones those creatures drop (their loot tables), and which ones are named after them (`cosmic_cod_bucket`, `spawn_egg_cosmic_cod`).

Each object gets a share per dimension. The Overworld is the baseline (configurable) and carries no meaning of its own.

**Dimension signatures are learned, not hard-coded.** For each dimension, the mod takes the already-assigned objects native to it and finds the aspects that are over-represented compared with the whole pack: at least 3× the usual rate and at least 5% of the mix. Families of variants count once, so sixteen coloured shulker boxes don't drown out everything else.

This is blended with a small prior in the lexicon. Prior aspects with weight 1.0 are *defining*: they always join the signature when the pack has them. That is how the Nether includes infernum once Thaumic Additions is installed. The prior matters most when a dimension has few assigned natives, and is matched by name for dimensions it doesn't list. In an offline test on the author's pack (using hand-picked End and Nether items), the End came out **alienis, then tenebrae**, and the Nether **ignis**. The in-game result is logged at startup. `dimensionAspects` in the config can pin a signature by hand.

## Crafting complexity

Thaumaturge's recipe rule conserves material, so crafted items never gain aspects and usually lose some (oak stairs are herba 5, like planks). Its authors add workmanship by hand instead, on key items. This layer extends that intent to every recipe-derived item, including Thaumaturge's own derivations.

**Depth.** An item's depth is the number of steps from raw material. Hand-assigned and recipe-less items are 0; otherwise depth is 1 plus the deepest input of the item's shallowest recipe. The first step is a refinement (log → planks, ore → ingot) and adds nothing.

**Kind.** Each further step adds craftsmanship of one kind, chosen by the item's simplest recipe:

| Kind | When | Aspect |
|---|---|---|
| Form | Stonecutting, or the result's name is a regular shape (stairs, slab, wall, brick...) | ordo |
| Machine | A machine recipe type (`create:*`, `forestry:*`...) | machina |
| Assembly | Anything else | fabrico |

Cooking and Thaumaturge's magical recipes add none.

**Amount.** About 6% of the item's material per step, at least 1, capped at 30%. Additions flow downstream through Thaumaturge's own ×0.75 rule, so variety builds up along recipe chains.

The shape words, machine recipe types and percentages are all editable in `lexicon.json` under `craftsmanship`. Set `craftingComplexity: false` in the config to turn the layer off.

## Where it learns from

Nothing from other mods is bundled with the mod. It learns entirely from the player's own installation:

- **Live data maps:** the `base_aspects` and `entity_aspects` entries of every installed mod and datapack.
- **Thaumaturge's index cache:** Thaumaturge's own resolved index at `config/thaumaturge/aspect_index.json`, minus every item this mod filled in itself, so it never learns from its own guesses.

The model is recalibrated at every reload, so it adapts to each pack's own conventions. A fresh install has no cache on its first launch and learns from data maps only. On the second launch it rebuilds once more, now with the full index to learn from. Set `learnFromThaumaturgeCache: false` to use data maps only.

## Measured accuracy

The test, run during development on the author's 10,595-item pack, hides 20% of its assigned items and predicts them from names alone, without recipe or material evidence:

- **In-pack:** the top aspect matches 72.6% of the time, with 98.7% coverage.
- **Unfamiliar pack** (trained on vanilla only, predicting modded items): about 58% top-aspect match.
- **Items covered only by the meaning lexicon:** about 18% top-aspect match, against about 2% by chance. These guesses carry low confidence scores.

## Config: `config/aspect_interpreter.json`

`items`, `entities`, `learnFromThaumaturgeCache`, `craftingComplexity`, `dimensionContext`, `baselineDimensions`, `dimensionAspects`, `minConfidence`, `writeReport`, `excludedNamespaces`, `excludedIds`, `excludedWords`.

After each reload, every guess is written to `config/aspect_interpreter/assignments.txt` with its confidence and strongest evidence. To override a guess, give the object a real `base_aspects` or `entity_aspects` entry; real entries always win.

## Runtime cost

All of the work happens once, inside Thaumaturge's own index build, and Thaumaturge caches the result to `config/thaumaturge/aspect_index.json`. Later launches load that cache and cost nothing.

- **Learning:** about 50–150 ms each time data maps load.
- **Dimension context:** computed once per server start or reload, before the index build. It reads each dimension's biomes, features and creature loot tables once; the time it took is logged.
- **Item guesses:** about 0.2 ms each. Each item's guess is reused across Thaumaturge's two resolution passes.
- **Recipe evaluation:** capped at 4 recipes per item and 2 options per ingredient.
- **Entities:** every entity type is pre-guessed once on the server at startup and after reloads. The finished table is sent to each player when they join (a few kilobytes), so clients never compute guesses and always agree with the server. The per-frame lookup from Thaumaturge's goggles overlay is a pure table read.
- **First install:** Thaumaturge's cache doesn't know about this mod. Whenever the mod's version or config changes, the old cache is moved aside to `aspect_index.before_interpreter.json`, so Thaumaturge rebuilds once with interpreted aspects included.
- **Logging:** the log reports how many items were filled and how many milliseconds the interpreter added to the build. The report is written after the build finishes.
