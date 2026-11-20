# Aspect Interpreter: how it assigns aspects

Aspect Interpreter is a NeoForge 1.21.1 add-on for Thaumaturge 1.1+. It gives aspects to any item or entity type that has none, and adds what Thaumaturge's recipe rule can't (function, craftsmanship, dimension) to existing assignments. Nothing is ever removed or reduced. Hand-written (`base_aspects` / `entity_aspects`) entries only ever receive dimension additions.

The reasoning behind each rule, drawn from Thaumaturge's own data, is in [`PHILOSOPHY.md`](PHILOSOPHY.md).

## Where it plugs in

| Target | Hook | What it changes |
|---|---|---|
| Hand-written items | Mixin on the return of `AspectIndexBuilder.collectBase` | Adds dimension aspects (alienis + flavour) to natives of non-Overworld dimensions before recipes resolve, so they flow on to crafted items. Optional. |
| Recipe-derived items | Mixin on the return of Thaumaturge's `CraftingAspectContributor.derive` | Adds dimension, function and craftsmanship after Thaumaturge has derived an item from a crafting recipe. Optional: it turns itself off if Thaumaturge changes. |
| Items | `IAspectRecipeContributor` registered through `RegisterAspectContributorsEvent` | Thaumaturge asks contributors in order and keeps the first non-empty answer. Event contributors run after its own crucible, infusion, crafting and cooking contributors, so this one is only asked about items with no base aspects and no recipe Thaumaturge could resolve. |
| Entities | Mixin on the return of `EntityAspects.of(EntityType)` | Every entity read (scanning, goggles, wand charging, nodes, liquid death, infusion enchantments) goes through this method. It returns a guess for unassigned types, or the real entry plus dimension additions for natives of other dimensions. |

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
| Dimension | alienis plus the flavours of the dimension the object comes from (see below) | 0.5 × share of the object's association with that dimension |
| Category (entities) | Learned from the pack's own `entity_aspects` per mob category (only aspects at least 40% of members share), falling back to monster → aversio/bestia; creature → bestia/terra; water → aqua/bestia; ambient → bestia/volatus/tenebrae; fire immunity adds a little ignis, for living creatures only; non-living entities (boats, minecarts, arrows, projectiles) are read as the item they are or most resemble | 0.7 (item match 1.5) |

**3. Blend.** Take the weighted average of the distributions. When close neighbours exist (similarity ≥ 0.5), keep about as many aspects as they carry. Otherwise keep aspects holding at least 8% of the mass, up to 4. Both settings were tuned to the original layer's balance; see `PHILOSOPHY.md` section 6.

**4. Size the amount.** Use the neighbours' typical total, else the word profiles' median, else 8 for items. For entities, use 25 scaled by body volume.

**5. Score confidence.** Confidence = total evidence weight ÷ 2, capped at 1. Below `minConfidence` (default 0.15), the ID is left unassigned.

**6. Check the aspects exist.** Aspects the running pack doesn't define (for example, an addon aspect seen in a data map of a mod that is not loaded) are dropped, and the rest are rescaled.

**7. Additions.** The guess then receives the additive layers below, in order: dimension, function, craftsmanship.

## Additive layers

These add to existing assignments and to guesses. Nothing is ever removed or reduced.

| Layer | Applies to | Adds |
|---|---|---|
| Dimension | everything, including hand-written entries and real creatures | alienis and the dimension's top flavour, for natives of non-Overworld dimensions |
| Function | recipe-derived and interpreted items | one missing function aspect named by the head of the name (container → vacuos, lamp → lux...), checked against hand-written items |
| Craftsmanship | recipe-derived and interpreted items | ordo, machina or fabrico for crafting steps beyond the first refinement |

Each addition is sized like a minor aspect in the original layer: the aspect's median minor share in the pack, capped at 20%. Function and craftsmanship stop once an item has 5 aspects.

## Function layer

Thaumaturge's authors write function into key items by hand (a chest is vacuos, a bookshelf cognitio), but recipes can't add it, so a derived barrel is only wood.

- **Head of the name only.** The function word must be the name's last word or two, after colour and size words. An `armor_stand` is a stand; `light_blue_wool` is wool.
- **Checked against hand-written items.** If 3 or more of the pack's hand-written items have that head and fewer than half carry the aspect, the pair is skipped.
- **Editable.** Word lists are in `lexicon.json` under `functions`; `functionLayer: false` turns the layer off.

## Dimension context

Some meaning comes from where a thing comes from: a cod from the End should be a little alien, whatever its name says. At each server start and reload, the mod reads, for every dimension:

- **Creatures:** which ones its biomes spawn, and which ones its structures spawn (via spawn overrides).
- **Blocks:** which ones its terrain and world features place, found by walking each feature's encoded configuration, including nested features.
- **Items:** which ones those creatures drop (their loot tables), and which ones are named after them (`cosmic_cod_bucket`, `spawn_egg_cosmic_cod`).

Each object gets a share per dimension. The Overworld is the baseline (configurable) and carries no meaning of its own.

**Every other dimension is alien.** Its signature is **alienis** plus up to two flavours:

| Dimension | Flavours |
|---|---|
| Nether | infernum (with Thaumic Additions), ignis |
| End | vacuos, tenebrae |
| Otherside (Deeper and Darker) | tenebrae, praecantatio |
| Unlisted | learned from the dimension's natives, else matched from its name |

An object at least 60% native to such a dimension gets alienis and the top available flavour if it lacks them. That includes hand-written items and real creatures.

**Learned flavours.** For dimensions the lexicon doesn't list, flavours are learned from already-assigned natives: aspects at least 3× over-represented and at least 5% of the mix, with families of variants counted once. `dimensionAspects` in the config can set any dimension's flavours by hand. The resulting signatures are logged at startup.

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
- **Thaumaturge's index cache:** Thaumaturge's own resolved index at `config/thaumaturge/aspect_index.json`, minus every item this mod filled in or added to, so it never learns from its own work.

The model is recalibrated at every reload, so it adapts to each pack's own conventions. A fresh install has no cache on its first launch and learns from data maps only. On the second launch it rebuilds once more, now with the full index to learn from. Set `learnFromThaumaturgeCache: false` to use data maps only.

## Measured accuracy

The test, run during development on the author's 10,595-item pack, hides 20% of its assigned items and predicts them from names alone, without recipe or material evidence:

- **In-pack:** the top aspect matches 72.6% of the time, with 98.7% coverage. Overlap with the true aspect set is 0.71, and 42% of guesses carry a single aspect (truth: 41%).
- **Unfamiliar pack** (trained on vanilla only, predicting modded items): about 58% top-aspect match.
- **Items covered only by the meaning lexicon:** about 18% top-aspect match, against about 2% by chance. These guesses carry low confidence scores.

## Config: `config/aspect_interpreter.json`

`items`, `entities`, `learnFromThaumaturgeCache`, `functionLayer`, `craftingComplexity`, `dimensionContext`, `baselineDimensions`, `dimensionAspects`, `minConfidence`, `writeReport`, `excludedNamespaces`, `excludedIds`, `excludedWords`.

After each reload, every guess, and every addition with the layer that made it, is written to `config/aspect_interpreter/assignments.txt`. To override a guess, give the object a real `base_aspects` or `entity_aspects` entry; real entries always win.

## Runtime cost

All of the work happens once, inside Thaumaturge's own index build, and Thaumaturge caches the result to `config/thaumaturge/aspect_index.json`. Later launches load that cache and cost nothing.

- **Learning:** about 50–150 ms each time data maps load.
- **Dimension context:** computed once per server start or reload, before the index build. It reads each dimension's biomes, features and creature loot tables once; the time it took is logged.
- **Item guesses:** about 0.2 ms each. Each item's guess is reused across Thaumaturge's two resolution passes.
- **Recipe evaluation:** capped at 4 recipes per item and 2 options per ingredient.
- **Entities:** every entity type is pre-guessed once on the server at startup and after reloads. The finished table is sent to each player when they join (a few kilobytes), so clients never compute guesses and always agree with the server. The per-frame lookup from Thaumaturge's goggles overlay is a pure table read.
- **First install:** Thaumaturge's cache doesn't know about this mod. Whenever the mod's version or config changes, the old cache is moved aside to `aspect_index.before_interpreter.json`, so Thaumaturge rebuilds once with interpreted aspects included.
- **Logging:** the log reports how many items were filled and how many milliseconds the interpreter added to the build. The report is written after the build finishes.
