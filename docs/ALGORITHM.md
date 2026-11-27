# Aspect Interpreter: how it assigns aspects

Aspect Interpreter is a NeoForge 1.21.1 add-on for Thaumaturge 1.1+. It gives aspects to any item or entity type that has none, and enriches the mobs it guesses, which are the richest source of aspects and the place where habitat and dimension flavour belong. Anything with a hand-written aspect (an item's `base_aspects` entry, a mob's `entity_aspects` entry, from any mod and Thaumaturge itself) is never guessed, added to, enriched, clamped or reduced (`HandWritten` is the one test every layer asks).

Additions are balanced along three axes: how rare an aspect is, how scarce the resource is, and how much processing it takes. Crafted items gain the key aspects their hand-written family members share (family consistency). Crafted items of non-canon mods made at the crafting table also get craftsmanship. A guessed mob is held to the hand-written mobs of its threat tier (see Threat tiers). Thaumaturge's own index is never changed: everything above is an overlay.

**Clean slate (1.11.0).** All of it is computed from scratch at every launch and every data reload, from Thaumaturge's own index of that launch plus the data maps. Nothing is saved or read back between launches except the readable record `config/aspect_interpreter/assignments.json`, and Thaumaturge's index cache file is never touched. Same pack, same result: every map the model, tables and rankings use is sorted, and ties are broken by id.

The reasoning behind each rule, drawn from Thaumaturge's own data, is in [`PHILOSOPHY.md`](PHILOSOPHY.md).

## Where it plugs in

| Target | Hook | What it changes |
|---|---|---|
| Serving items | Mixin (required) on the return of `AspectIndex.of(Item)` and `AspectIndex.of(ItemStack)` | Every item lookup in Thaumaturge (tooltips, scanning, crucible, deconstruction table, JEI, wands) is `AspectIndexAccess.index().of(...)`. These two methods return the overlay's value for an item in it, and Thaumaturge's own value otherwise. A variant (the wand) or an essentia container's own contents still win. |
| Overlay build | Thaumaturge's own `AspectIndexBuilder.build`, run once more per launch with this mod on | See below. Recipe-derived items: optional mixin on the return of `CraftingAspectContributor.derive` adds family key aspects and craftsmanship; unresolved items: an `IAspectRecipeContributor` registered through `RegisterAspectContributorsEvent`, asked only about items Thaumaturge's own contributors could not resolve. Both act only during this build, never in Thaumaturge's own. |
| Clients | Payload `item_overlay` (and `entity_table`), server to client | A client has Thaumaturge's index from Thaumaturge and our overlay on top. Cleared when the client leaves a world. |
| Entities | Mixin on the return of `EntityAspects.of(EntityType)` | Every entity read (scanning, goggles, wand charging, nodes, liquid death, infusion enchantments) goes through this method. It returns the guess for an unassigned type; a hand-written (non-empty) result is returned as is. |

**The overlay.** Thaumaturge builds or loads its index with this mod off. When it is ready (checked at the end of each server tick until it is), the interpreter trains the model on it, computes canon, dimension context, families, crafting depth and the entity table, then runs `AspectIndexBuilder.build` a second time with the interpreter's contributor and crafting mixin on. Thaumaturge resolves recipes recursively, so a leaf we fill in carries into everything crafted from it. Every item whose value in that second index differs from the real index, and is not hand-written, is the overlay. That also covers the products of guessed items without re-deriving anything by hand.

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
| Neighbours | The 5 most similar assigned IDs, scored by weighted word overlap (weight = word rarity × word consistency, so materials outweigh shapes). A neighbour of another kind (food, gear, or anything else; blocks and plain items count as one kind) counts at half similarity, so `raw_bison` doesn't learn from `raw_iron`. They also supply the amount. A neighbour whose values Thaumaturge derived from recipes (not hand-written) votes and sizes at 0.15 of a hand-written one. | 1.5 × best similarity |
| Word profiles | The average aspect mix of all assigned IDs containing each word, discounted for rare or inconsistent words | 0.6 |
| Meaning | Lexicon matches, longest phrase first (`dark_oak` consumes `dark`). Each keyword is calibrated against how often its aspect actually appears on assigned items containing it. | 0.5, scaled up when no close neighbour exists |
| Recipes (items) | Any recipe type producing the item whose ingredients resolve (≥50%), at 75% of the inputs. | 2.0 × fraction resolved |
| Material (items) | Block sound type (wood, stone, metal, glass, wool...), light level, food, tool, attack damage, armour, fire resistance | up to 0.8 |
| Creature (spawn eggs) | The entity's real or interpreted aspects, plus victus | 1.2 |
| Dimension (items from mobs) | the signature of the dimension a mob comes from, for its drops and items named after it (see below) | 0.5 × share of the item's association with that dimension |
| Category (entities) | Learned from the pack's own `entity_aspects` per mob category (only aspects at least 40% of members share), falling back to monster → aversio/bestia; creature → bestia/terra; water → aqua/bestia; ambient → bestia/volatus/tenebrae; fire immunity adds a little ignis, for living creatures only; non-living entities (boats, minecarts, arrows, projectiles) are read as the item they are or most resemble | 0.7 (item match 1.5) |

**3. Blend.** Take the weighted average of the distributions. When close neighbours exist (similarity ≥ 0.5), keep about as many aspects as they carry. Otherwise keep aspects holding at least 8% of the mass, up to 4. Both settings were tuned to the original layer's balance; see `PHILOSOPHY.md` section 6.

**4. Size the amount.** Use the similarity-weighted median of the neighbours' totals, else the word profiles' median, else 8 for items. A name-based total is capped at the largest hand-written item total in the running pack (100 with Thaumaturge 1.1.0). Recipe totals follow Thaumaturge's own rule and are not capped. For entities, use 25 scaled by body volume, then multiplied by the mob's rarity (see Mob enrichment).

**5. Score confidence.** Confidence = total evidence weight ÷ 2, capped at 1. Below `minConfidence` (default 0.15), the ID is left unassigned.

**6. Check the aspects exist.** Aspects the running pack doesn't define (for example, an addon aspect seen in a data map of a mod that is not loaded) are dropped, and the rest are rescaled.

**7. Additions.** An item guess then receives the item layers below. An entity guess goes through mob enrichment and then its threat tier's ceilings.

## Additive layers

Nothing is ever removed or reduced.

| Layer | Applies to | Adds |
|---|---|---|
| Mob enrichment | guessed mobs only (never hand-written ones) | habitat, dimension, behaviour and drop aspects (see below) |
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

Mobs should be the most aspect-rich source, and the hardest to process. So variety, and dimension flavour, belong on them. Every mob the interpreter guesses (since 1.11.0 never one with a hand-written entry) is enriched from four sources:

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

**Rarity.** Bosses ×2, structure-only spawns ×1.5, rare spawns (spawn weight ≤ 5 or 2 biomes or fewer) ×1.3, others ×1. It scales the guessed mob's whole total.

**Amounts and balance.** Each addition is the aspect's minor share × the mob's total, scaled by how well supported it is. The additions together are capped at 30–45% of that (by fantasy score), or 40% for bosses. The weakest are dropped first; dimension guarantees are kept. The record lists every guessed mob's fantasy score, rarity and each addition with its source.

## Threat tiers (1.11.0)

A guessed mob never carries more than the hand-written mobs of the same threat, so the interpreter cannot make a mob richer than its kind. The tier comes from stats alone, by fixed thresholds read off the hand-written mobs' stats of the test pack (142 mobs):

| Tier | Rule (first match, from the top) |
|---|---|
| Boss | `c:bosses` tag, or max health 150 or more |
| Mini-boss | attack damage 12 or more, or hostile (monster category) with max health 60 or more |
| Strong hostile | hostile with max health 30 or more, or attack damage 5 or more |
| Weak hostile | any other hostile mob |
| Passive / ambient | everything else (animals, water and ambient creatures, and every non-living entity: boats, orbs, projectiles) |

Ceilings are computed at every launch from the running pack's hand-written mobs, never saved: **total** at most the largest hand-written total in the tier, or the next largest when the largest is more than twice it (an outlier; the same rule for each aspect below); **each aspect** at most the tier's largest hand-written amount of that aspect (outlier rule applied), and for an aspect no hand-written mob of the tier carries, at most the tier's largest single-aspect amount; a tier with no hand-written mobs takes the next lower tier's ceilings. The clamp runs after enrichment: each aspect is cut to its ceiling, then all amounts are scaled down together (each at least 1) until the total fits, and the weakest aspects are dropped only if that is not enough. Ties break by aspect id.

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

The Overworld is the baseline (configurable) and carries no meaning of its own. A biome the baseline generates, or one tagged `minecraft:is_overworld` or `c:is_overworld`, is credited to the baseline in every dimension that uses it, and its features are not counted for that dimension. Thaumaturge Tinker's Bedrock dimension, for example, is one flat `minecraft:beach`, and it does not make beach mobs alien. Every other dimension is alien, except **underworlds** (the Nether: this world's below, not an outer world). Signatures and pools are in `lexicon.json` under `dimensions`. For dimensions it doesn't list, flavours are learned from already-assigned natives: aspects at least 3× over-represented and at least 5% of the mix, with families of variants counted once. `dimensionAspects` in the config can set any dimension's flavours by hand. The resulting signatures are logged at startup.

## Crafting complexity

Thaumaturge's recipe rule conserves material, so crafted items never gain aspects and usually lose some (oak stairs are herba 5, like planks). Its authors add workmanship by hand instead, on key items. This layer extends that intent, but only to items of non-canon mods that are made at the vanilla crafting table.

**Depth.** An item's depth is the number of steps from raw material. Hand-assigned and recipe-less items are 0; otherwise depth is 1 plus the deepest input of the item's shallowest recipe. The first step is a refinement (log → planks, ore → ingot) and adds nothing.

**Kind.** Each further step adds **ordo** if the result's name is a regular shape (stairs, slab, wall, brick...), otherwise **fabrico**.

**Amount.** About 6% of the item's material per step, at least 1, capped at 30%.

The shape words and percentages are editable in `lexicon.json` under `craftsmanship`. Set `craftingComplexity: false` to turn the layer off.

## Where it learns from

Nothing from other mods is bundled with the mod. It learns entirely from the player's own installation:

- **Live data maps:** the `base_aspects` and `entity_aspects` entries of every installed mod and datapack.
- **Thaumaturge's own index of this launch (1.11.0):** Thaumaturge builds or loads its index with this mod switched off (our contributor and crafting mixin act only in our own overlay build), so the index is exactly what Thaumaturge derives alone, for every recipe type it reads. The model trains on it plus the data maps, from scratch at every launch and reload. Nothing is saved: 1.10.0's clean pass, `clean_training.json` and the fingerprint matching are gone. If an old cache file still holds an earlier version's guesses (1.10.0 and older wrote them into it), those would be learned as if Thaumaturge's own; the changelog asks players to delete `config/thaumaturge/aspect_index.json` once on upgrade, and the pack test fails if the live index differs from a fresh Thaumaturge-only build.

The model is recalibrated at every reload, so it adapts to each pack's own conventions. Set `learnFromThaumaturgeCache: false` to use data maps only.

## Measured accuracy

The test, run during development on the author's 10,595-item pack, hides 20% of its assigned items and predicts them from names alone, without recipe or material evidence:

- **In-pack:** the top aspect matches 72.6% of the time, with 98.7% coverage. Overlap with the true aspect set is 0.71, and 42% of guesses carry a single aspect (truth: 41%).
- **Unfamiliar pack** (trained on vanilla only, predicting modded items): about 58% top-aspect match.
- **Items covered only by the meaning lexicon:** about 18% top-aspect match, against about 2% by chance. These guesses carry low confidence scores.
- **Hold-out on hand-written items (1.11.0, `HoldOutEvaluationTest`):** five folds, each hiding a random 10% of the test pack's 1,448 hand-written items (fixed seeds), predicted from names alone. Trained on the 1.9.0 learning set (Thaumaturge's cache minus the interpreter's items, 2,096 items): aspect-set precision 0.73, recall 0.77, top aspect 0.72, predicted/true total median 1.00 and p90 2.11, 96% predicted. Trained on the 1.10.0 clean set (3,797 items, the same set 1.11.0 now learns from): precision 0.69, recall 0.76, top aspect 0.68, median 1.00, p90 2.86, 98% predicted. The larger clean set holds many more derived items, which pull names toward their ingredients' aspects; it is the honest set (no guesses learned second-hand), not the more accurate one.

## Config: `config/aspect_interpreter.json`

`items`, `entities`, `learnFromThaumaturgeCache`, `mobEnrichment`, `familyConsistency`, `canonNamespaces`, `functionLayer`, `craftingComplexity`, `dimensionContext`, `baselineDimensions`, `dimensionAspects`, `minConfidence`, `writeReport`, `excludedNamespaces`, `excludedIds`, `excludedWords`.

After each computation, every value we serve (items and mobs), with its confidence, evidence and the layer behind each addition, is written to `config/aspect_interpreter/assignments.json` (sorted, no times: the same pack gives the same bytes). To override a guess, give the object a real `base_aspects` or `entity_aspects` entry; real entries always win.

## Runtime cost

All of the work happens once per launch (and once per data reload), after Thaumaturge's own index is ready, on the server thread, in one end-of-tick step. Thaumaturge's own index and cache file are as without this mod. In the test pack (about 5,200 items, 3,800 valued) the whole step takes about half a second: the model, canon, dimension context, families, the threat tiers and the mob table, one more Thaumaturge derivation with this mod on, the diff, and the record.

- **Learning:** about 50-150 ms.
- **Canon and dimension context:** they read each pack's aspect files, each dimension's biomes and features, and every mob's loot table once; the time is logged.
- **Item guesses:** about 0.2 ms each. Each item's guess is reused across Thaumaturge's two resolution passes.
- **Recipe evaluation:** capped at 4 recipes per item and 2 options per ingredient.
- **Entities:** every unassigned entity type is guessed once on the server per launch and reload. The finished table and the item overlay are sent to each player when they join (a few kilobytes and some tens of kilobytes), so clients never compute guesses and always agree with the server. The per-frame lookup from Thaumaturge's goggles overlay is a pure table read; item lookups add one hash lookup.
- **Logging:** one line reports the overlay size, additions per layer and milliseconds.
