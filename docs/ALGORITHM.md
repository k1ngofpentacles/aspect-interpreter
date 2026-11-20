# Aspect Interpreter: how it assigns aspects

Aspect Interpreter is a NeoForge 1.21.1 add-on for Thaumaturge 1.1+. It gives aspects to any item or entity type that has none, and enriches mobs, which are the richest source of aspects and the place where habitat and dimension flavour belong. Nothing is ever removed or reduced.

Additions are balanced along three axes: how rare an aspect is, how scarce the resource is, and how much processing it takes. Hand-written item entries are never added to. Hand-written mob entries receive mob enrichment as additions only. Crafted items gain the key aspects their hand-written family members share (family consistency). Crafted items of non-canon mods made at the crafting table also get craftsmanship. Nothing else in the original index changes.

The reasoning behind each rule, drawn from Thaumaturge's own data, is in [`PHILOSOPHY.md`](PHILOSOPHY.md).

## Where it plugs in

| Target | Hook | What it changes |
|---|---|---|
| Recipe-derived items | Mixin on the return of Thaumaturge's `CraftingAspectContributor.derive` | Adds family key aspects to any crafted item, and craftsmanship to items of non-canon mods made at the crafting table. Optional: it turns itself off if Thaumaturge changes. |
| Items | `IAspectRecipeContributor` registered through `RegisterAspectContributorsEvent` | Thaumaturge asks contributors in order and keeps the first non-empty answer. Event contributors run after its own crucible, infusion, crafting and cooking contributors, so this one is only asked about items with no base aspects and no recipe Thaumaturge could resolve. |
| Entities | Mixin on the return of `EntityAspects.of(EntityType)` | Every entity read (scanning, goggles, wand charging, nodes, liquid death, infusion enchantments) goes through this method. It returns a guess for unassigned types, or the real entry plus mob enrichment. |

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
| Dimension (items from mobs) | the signature of the dimension a mob comes from, for its drops and items named after it (see below) | 0.5 × share of the item's association with that dimension |
| Category (entities) | Learned from the pack's own `entity_aspects` per mob category (only aspects at least 40% of members share), falling back to monster → aversio/bestia; creature → bestia/terra; water → aqua/bestia; ambient → bestia/volatus/tenebrae; fire immunity adds a little ignis, for living creatures only; non-living entities (boats, minecarts, arrows, projectiles) are read as the item they are or most resemble | 0.7 (item match 1.5) |

**3. Blend.** Take the weighted average of the distributions. When close neighbours exist (similarity ≥ 0.5), keep about as many aspects as they carry. Otherwise keep aspects holding at least 8% of the mass, up to 4. Both settings were tuned to the original layer's balance; see `PHILOSOPHY.md` section 6.

**4. Size the amount.** Use the neighbours' typical total, else the word profiles' median, else 8 for items. For entities, use 25 scaled by body volume, then multiplied by the mob's rarity (see Mob enrichment).

**5. Score confidence.** Confidence = total evidence weight ÷ 2, capped at 1. Below `minConfidence` (default 0.15), the ID is left unassigned.

**6. Check the aspects exist.** Aspects the running pack doesn't define (for example, an addon aspect seen in a data map of a mod that is not loaded) are dropped, and the rest are rescaled.

**7. Additions.** An item guess then receives the item layers below. An entity guess goes through mob enrichment.

## Additive layers

Nothing is ever removed or reduced.

| Layer | Applies to | Adds |
|---|---|---|
| Mob enrichment | every mob, hand-written or guessed | habitat, dimension, behaviour and drop aspects (see below) |
| Family consistency | crafted and interpreted items of any mod (never hand-written ones) | up to 2 key aspects that the item's hand-written relatives share; lux for bright light sources (see below) |
| Craftsmanship | items of non-canon mods made at the crafting table, derived or guessed | ordo or fabrico for crafting steps beyond the first refinement |
| Function | interpreted items only | one missing function aspect named by the head of the name (container → vacuos, lamp → lux...), checked against hand-written items |
| Dimension | interpreted items that come from a mob of another dimension (its drops, bucket, egg) | the dimension's guaranteed aspects |

Item additions are sized like a minor aspect in the original layer: the aspect's median minor share in the pack, capped at 20%. They stop once an item has 5 aspects. Blocks never receive dimension aspects.

## Family consistency

Thaumaturge's recipe rule loses an object's defining aspect when no ingredient carries it. Vanilla candles are hand-written lux 5, but Forestry's big and jumbo candles and Thaumaturge's own coloured candles are crafted from wax, string and dye, so they came out with no lux. This layer makes similar items agree on 1–2 key aspects.

**Family.** An item's family is its head noun: the last word of its name that names a kind of thing, after colour, size, material and shaping words (`cut`, `polished`, `roasted`...; editable under `families` in `lexicon.json`). A word counts as a head noun only if, across the pack, it ends at least half the names it appears in. A trailing modifier is skipped (`jumbo_candle_refractory` is a candle). A rare head noun gives no family at all (`potato_cannon` is not a potato).

**Who defines the keys.** Hand-written members in the item's own mod first (at least 2). Otherwise hand-written members across the pack (at least 3, from two mods or including vanilla). Crafted members never define keys.

**Key aspects.** Up to 2 aspects carried by at least 60% of those members. An aspect a member's modifier words explain doesn't count (chorus fruit's alienis comes from "chorus", not from "fruit").

**Same kind.** Members only learn from anchors of the same kind, or anchors that share an item tag with them. Kinds are: a block of the same material (by block sound), food, gear (tools, weapons, armour), or any other item. A music disc doesn't learn from chicken meat, and a bee frame doesn't learn from an item frame.

**Light.** A real light source is a block that shines at 10+ as placed, or that is lit by hand (a `lit` state and no block entity) and shines that bright when lit. That includes candles, candle holders and redstone lamps, but not furnaces. If at least half the pack's hand-written light sources carry lux, every crafted or interpreted light source gets it too. Faint glows don't count (brewing stand, magma block, dragon egg), and in the hand-written data none of them carry lux.

**Amount.** Each key is sized to its median share among the anchors, at most the item's largest existing aspect. All family additions together are capped at half the item's original total.

## Canon

Hand-written aspects are canon: the interpreter learns from them but never adds to the items of a canon mod. Canon mods are:

- **Configured:** vanilla, Thaumaturge and its add-ons, Create and its add-ons (`canonNamespaces`; a trailing `*` matches a prefix).
- **Detected:** any mod that ships its own Thaumaturge aspect files. Every loaded pack's `base_aspects` and `entity_aspects` files are read at startup, so a newly installed add-on that hand-writes aspects is recognised without configuration. A mod that hand-writes at least 25% of another mod's items makes that mod canon too. The hand-writing packs found are logged.

## Mob enrichment

Mobs should be the most aspect-rich source, and the hardest to process. So variety, and dimension flavour, belong on them. Every mob, with a hand-written entry or a guess, is enriched from four sources:

| Source | Reads | Weight |
|---|---|---|
| Habitat | The biomes the mob spawns in, by spawn weight. Each biome's landscape (from `c:` biome tags: snowy → gelum, forest and jungle → herba, ocean and river → aqua, desert → ignis/terra...) is blended with Thaumaturge's own biome aspects at half weight. Thaumaturge's are a primal palette for aura nodes (cold biomes are ordo, plains aer), so they decide only where the landscape says nothing. | 0.5 + 0.5 × fantasy |
| Dimension | For mobs at least 60% native to another dimension: alienis (none for underworlds) and the lead flavour, always; pool flavours only if the mob's name, traits or drops support them | 0.5 + 0.5 × fantasy |
| Behaviour | Entity tags and properties: undead → exanimis/mortuus, arthropod → bestia, illager → humanus/aversio, raider → aversio, aquatic → aqua, fire-immune → ignis, hostile → aversio, boss → potentia/aversio | 0.6 |
| Drops | The mean aspect mix of its loot table's items | 0.4 |

**Fantasy score (0–1).** Starts at 0.3. A real-animal name (cow, wolf, cod...) lowers it by 0.5. It rises with a fantasy word in the name (cosmic, void, wraith, golem...) by 0.4, being native to another dimension by 0.4, undeath by 0.2, boss status by 0.2, and fantasy aspects in its mix (praecantatio, alienis, exanimis, vitium, infernum...) by up to 0.6. Habitat and dimension weigh more on fantasy mobs.

**Dimensions.**

| Dimension | Always | May add, when it suits the mob |
|---|---|---|
| Nether (an underworld: no alienis) | infernum (ignis without Thaumic Additions) | ignis, exanimis, mortuus, spiritus |
| End | alienis, vacuos | tenebrae |
| Otherside | alienis, tenebrae | praecantatio, sensus |
| Unlisted | alienis, learned lead flavour | the rest of its learned flavours |

So a ghast takes infernum and spiritus, a zombified piglin infernum and mortuus, and a strider only infernum.

**Counts.** Candidates scoring at least 0.2 are added up to a ceiling; weaker candidates (at least 0.08) only fill up to a floor. Ordinary mobs: 3–4 aspects. Fantasy and other-dimension mobs: 4–5. Bosses (the `c:bosses` tag, or 150+ health): 6–7.

**Rarity.** Bosses ×2, structure-only spawns ×1.5, rare spawns (spawn weight ≤ 5 or 2 biomes or fewer) ×1.3, others ×1. For hand-written mobs this scales only the additions; the original amounts stay exactly as written. For guessed mobs it scales the whole total.

**Amounts and balance.** Each addition is the aspect's minor share × the mob's total × rarity, scaled by how well supported it is. The additions together are capped at 30–45% of that (by fantasy score), or 40% for bosses. The weakest are dropped first; dimension guarantees are kept.

On vanilla's 87 hand-written mobs this takes the mean from 2.9 to 3.7 aspects and the mean total from 27 to 31. The report lists every mob's fantasy score, rarity and each addition with its source.

## Function layer

Thaumaturge's authors write function into key items by hand (a chest is vacuos, a bookshelf cognitio), but recipes can't add it, so a derived barrel is only wood.

- **Head of the name only.** The function word must be the name's last word or two, after colour and size words. An `armor_stand` is a stand; `light_blue_wool` is wool.
- **Checked against hand-written items.** If 3 or more of the pack's hand-written items have that head and fewer than half carry the aspect, the pair is skipped.
- **Editable.** Word lists are in `lexicon.json` under `functions`; `functionLayer: false` turns the layer off.

## Dimension context

At each server start and reload, the mod reads, for every dimension:

- **Creatures:** which ones its biomes spawn (and in which biomes, how often), and which ones its structures spawn (via spawn overrides).
- **Items from creatures:** what every mob drops (its loot table), and which items are named after a mob (`cosmic_cod_bucket`, `spawn_egg_cosmic_cod`).
- **Blocks:** which ones its terrain and features place. These are used only to learn what an unlisted dimension is like; blocks never receive dimension aspects.

The Overworld is the baseline (configurable) and carries no meaning of its own. Every other dimension is alien, except **underworlds** (the Nether: this world's below, not an outer world). Signatures and pools are in `lexicon.json` under `dimensions`. For dimensions it doesn't list, flavours are learned from already-assigned natives: aspects at least 3× over-represented and at least 5% of the mix, with families of variants counted once. `dimensionAspects` in the config can set any dimension's flavours by hand. The resulting signatures are logged at startup.

## Crafting complexity

Thaumaturge's recipe rule conserves material, so crafted items never gain aspects and usually lose some (oak stairs are herba 5, like planks). Its authors add workmanship by hand instead, on key items. This layer extends that intent, but only to items of non-canon mods that are made at the vanilla crafting table.

**Depth.** An item's depth is the number of steps from raw material. Hand-assigned and recipe-less items are 0; otherwise depth is 1 plus the deepest input of the item's shallowest recipe. The first step is a refinement (log → planks, ore → ingot) and adds nothing.

**Kind.** Each further step adds **ordo** if the result's name is a regular shape (stairs, slab, wall, brick...), otherwise **fabrico**.

**Amount.** About 6% of the item's material per step, at least 1, capped at 30%.

The shape words and percentages are editable in `lexicon.json` under `craftsmanship`. Set `craftingComplexity: false` to turn the layer off.

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

`items`, `entities`, `learnFromThaumaturgeCache`, `mobEnrichment`, `familyConsistency`, `canonNamespaces`, `functionLayer`, `craftingComplexity`, `dimensionContext`, `baselineDimensions`, `dimensionAspects`, `minConfidence`, `writeReport`, `excludedNamespaces`, `excludedIds`, `excludedWords`.

After each reload, every guess, and every addition with the layer that made it, is written to `config/aspect_interpreter/assignments.txt`. To override a guess, give the object a real `base_aspects` or `entity_aspects` entry; real entries always win.

## Runtime cost

All of the work happens once, inside Thaumaturge's own index build, and Thaumaturge caches the result to `config/thaumaturge/aspect_index.json`. Later launches load that cache and cost nothing.

- **Learning:** about 50–150 ms each time data maps load.
- **Canon and dimension context:** computed once per server start or reload, before the index build. They read each pack's aspect files, each dimension's biomes and features, and every mob's loot table once; the time is logged.
- **Mob enrichment:** part of the entity warm-up, once per start or reload.
- **Item guesses:** about 0.2 ms each. Each item's guess is reused across Thaumaturge's two resolution passes.
- **Recipe evaluation:** capped at 4 recipes per item and 2 options per ingredient.
- **Entities:** every entity type is pre-guessed once on the server at startup and after reloads. The finished table is sent to each player when they join (a few kilobytes), so clients never compute guesses and always agree with the server. The per-frame lookup from Thaumaturge's goggles overlay is a pure table read.
- **First install:** Thaumaturge's cache doesn't know about this mod. Whenever the mod's version or config changes, the old cache is moved aside to `aspect_index.before_interpreter.json`, so Thaumaturge rebuilds once with interpreted aspects included.
- **Logging:** the log reports how many items were filled and how many milliseconds the interpreter added to the build. The report is written after the build finishes.
