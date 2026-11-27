# Changelog

## 1.11.1
- Fixed: a guessed mob naming an aspect from a mod that is not installed (for example Madman's Dreams' somnium) could be served above its threat tier's ceilings, because the missing aspect was dropped and the rest rescaled upward after the clamp. The missing aspect is now dropped before enrichment and the clamp. No change in packs where every aspect exists.

## 1.11.0
- **Upgrading from 1.10.0 or older: delete `config/thaumaturge/aspect_index.json` once** (with the game closed). Older versions wrote their guesses into Thaumaturge's own index cache, and this version never touches that file, so Thaumaturge only rebuilds it without them if you remove it. A new install needs nothing.
- **Thaumaturge's index is purely Thaumaturge's.** The interpreter no longer takes part in Thaumaturge's index build, and never moves, edits or forces a rebuild of its cache file. Our values are a separate overlay, computed from scratch at every launch (and every data reload), served on top of Thaumaturge's index at the one place every item lookup passes (`AspectIndex.of`, via one new required mixin, `AspectIndexMixin`), and sent to clients in a new payload so tooltips, scanning and crafting costs agree. The overlay holds our guesses for items Thaumaturge leaves empty, our additions, and the items crafted from those (found by running Thaumaturge's own derivation once more with the interpreter on and keeping every item whose value then differs). Entities keep the `EntityAspects.of` mixin.
- **Nothing is read back between launches.** The version/config signature stamp, the cache-moving logic, the clean pass, `clean_training.json` and `interpreted_items.txt` are gone. The model trains each launch on Thaumaturge's own index plus the data maps. The only file we write is the readable record `config/aspect_interpreter/assignments.json` (it replaces `assignments.txt`).
- **Hand-written is never touched** (reverses 1.6.0's mob rule). An item with a `base_aspects` entry or a mob with an `entity_aspects` entry, from any mod and Thaumaturge itself, gets no guess, no family, craftsmanship, function or dimension addition and no mob enrichment. Mobs and items without hand-written data are filled and enriched as before. `HandWritten` is the single test every layer asks.
- **Guessed mobs never outrank hand-written ones.** Each mob gets a threat tier from stats (passive, weak hostile, strong hostile, mini-boss, boss: max health, attack damage, hostility, boss status), computed per launch; a guessed mob is held to its tier's hand-written ceilings, both its total and each aspect (a ceiling is the tier's highest hand-written value, or the next highest when the highest is more than twice it, so one outlier such as `material_peeler` no longer sets the passive total at 200; an aspect no hand-written mob of the tier carries is held to the tier's largest single amount; a tier with no hand-written mobs takes the next lower tier's). In the test pack this brings `primal_orb` (100), `thaumium_boat` (134) and `arcane_bore` (207) to 42, 57 and 98.
- **Hand-written neighbours weigh more than recipe-derived ones.** Items Thaumaturge derived from recipes count 0.15 against 1.0 for hand-written items, as a neighbour (its aspect vote) and in the size median. Learning from Thaumaturge's derived values had made guesses less accurate than 1.9.0 (in a hold-out test over 720 hand-written items: precision .69, top aspect .68, p90 total ratio 2.86); with the weight they are about level with 1.9.0 (.73, .72, 2.18).
- **Same pack, same result, every launch.** Fixed order dependence: Holder objects (identity hashes) and `Map.of` iteration order fed sums and tie-breaks; the model, tables and ranking now run on sorted maps with explicit tie-breaks. `tools/aspect-interpreter-determinism.py` launches four times (fresh, two forced Thaumaturge rebuilds, one cached launch) and compares the record, the overlay, the served values, the mob table and Thaumaturge's index byte for byte.
- Cost: one extra Thaumaturge derivation per launch, about half a second in the test pack, including the model, mob tables and the record.
- New tests: threat tiers and clamping, insertion-order independence of the model, and a hold-out fidelity table (`HoldOutEvaluationTest`, prints when its scratch files exist).

## 1.10.0
- **One launch is enough.** The interpreter now learns from a clean copy of Thaumaturge's own aspect values, worked out inside the same index build with the interpreter switched off. A fresh install or an update no longer needs a second launch, and there is no one-time reset.
- **No stale learning, for every recipe type.** 1.9.0 kept guesses out of learning by following recipe ingredients, which missed anything made another way (Thaumaturge 1.1.1's smithing recipes, such as netherite gear). The clean copy covers whatever Thaumaturge itself reads, including recipe types added later.
- **Mob and dimension tables are redone** after the build from the clean model, so the first launch is already fully clean. If Thaumaturge's cache is valid but the saved clean values are missing or from another index, the cache is moved aside and rebuilt once.
- **Cost:** about 0.3 seconds more on the one index build (Thaumaturge's own calculation run once more, plus redoing the mob tables); later launches are unchanged.
- `learnFromThaumaturgeCache` keeps its name and now means "learn from Thaumaturge's derived values". When Thaumaturge's cache is valid and no build runs, the clean values are reloaded from `config/aspect_interpreter/clean_training.json`. `interpreted_items.txt` is no longer written and can be deleted.

## 1.9.0
- **Fixed runaway aspect amounts.** The interpreter learned from Thaumaturge's index cache, excluding the items it guessed itself but not the items Thaumaturge then crafted from those guesses. Each launch it learned its own guesses second-hand and guessed a little richer, so minor items crept up to hundreds of each aspect (in the author's pack, items totalling 1,000+ went from 19 to 262 between two launches, and 232 sat at Thaumaturge's 500 cap). Every item derived through recipes from a guess or an addition is now kept out of learning too.
- **One-time reset.** A cache from an older version may hold second-hand guesses, so the first launch of 1.9.0 learns from data maps only. The next launch rebuilds once more from the clean index, as on a fresh install.
- **Guess sizes are bounded.** A guess takes the weighted median of its neighbours' totals, not the mean, so one outlying neighbour can't set it. Name-based guesses never exceed the largest hand-written total in the pack (100 with Thaumaturge 1.1.0). Recipe-based totals follow Thaumaturge's own rule and aren't capped.
- **Neighbours of another kind count half.** Food, gear and everything else are kept apart when finding similar names, so `raw_bison` no longer takes metallum from `raw_iron`. Blocks and plain items stay one kind, so a material still pairs with its block.
- **Borrowed biomes belong to the overworld.** A dimension made of overworld biomes (Thaumaturge Tinker's Bedrock dimension is one flat beach) no longer turns their mobs into alien natives. A biome the baseline dimension generates, or one tagged `is_overworld`, is credited to the baseline wherever it appears. In the author's pack this had given 91 beach-mob items alienis and praemunio (lobster pasta, centipede legs).
- The mod's reported version now comes from the jar. 1.8.0 still reported itself as 1.7.0, so its install did not trigger a rebuild.
- Unit tests for each fix (`InflationGuardTest`).

## 1.8.0
- **Material words must earn their place.** A word such as "crimson" or "warped" (or an overworld wood name) counts as a material in an item's name only if the item corroborates it: a wooden block sound, an item tag (logs, planks, saplings, leaves, `wooden_*`, `*_stems`...) or a recipe ingredient carrying one. Otherwise the name model ignores the word, so `crimson_blade`, crimson helms and robes and `alexsmobs:warped_*` food are no longer read as crimson wood (herba). Items with evidence behave as before. Known miss: banners have the wood sound, so `banner_crimson_cult` still counts as wood.
- New `materials` section in `lexicon.json` (wood only for now): words, sounds, tags. New `Materials` class; `Model.predict` takes the ignored words.
- First unit tests (JUnit, plain Java): the crimson cases and real-wood non-regression.

## 1.7.0
- **Family consistency.** Similar items now share their key aspects. The recipe rule loses a defining aspect when no ingredient carries it: vanilla candles are hand-written lux, but Forestry's big and jumbo candles and Thaumaturge's own coloured candles are crafted from wax and dye and came out with no lux. Crafted and interpreted items of any mod now take up to 2 key aspects from their hand-written relatives. Hand-written items are never changed.
  - **Families** are head nouns, found per mod first, then across the pack. A word counts as a head only if it mostly ends names, so `big_candle_red` and `jumbo_candle_refractory` are candles, a `copper_candle_holder` is a holder, and a potato cannon is not a potato.
  - **Keys** are aspects carried by 60%+ of the hand-written members, ignoring what material or modifier words explain (chorus fruit's alienis comes from "chorus", not "fruit").
  - **Same kind only.** An item learns only from members of the same kind (block of the same material, food, gear, other item) or ones sharing an item tag. A music disc doesn't learn from chicken meat.
  - **Amounts** are sized to the key's share in the family, at most the item's largest aspect, and at most half its total altogether.
- **Light sources carry lux.** A block that shines at 10+ as placed, or when lit by hand (candles, candle holders, redstone lamps; not furnaces), takes lux if the pack's hand-written light sources mostly carry it. Faint glows such as brewing stands and magma blocks don't count, matching the hand-written data.
- **New config option:** `familyConsistency`.
- **Preview** on the author's index, before the same-kind guard: about 280 items gain a family key, including 70 candles (lux), 44 pressure plates (machina, sensus), 43 buttons (machina) and 43 trapdoors (motus).

## 1.6.0
A rebalance around three axes: aspect rarity, resource scarcity and processing effort. Mobs become the richest, hardest-to-process source; the original index is otherwise left alone.

- **Mob enrichment.** Every mob, hand-written or guessed, gains aspects from four sources:
  - **Habitat:** its spawn biomes, read through biome tags (snowy → gelum, forest → herba, ocean → aqua) and blended with Thaumaturge's own biome aspects.
  - **Dimension:** see below.
  - **Behaviour:** undead, arthropod, illager, aquatic, fire-immune and hostile traits.
  - **Drops:** the aspects of its loot.
- **Fantasy score.** Habitat and dimension weigh more on fantasy mobs. The score combines real-animal names, fantasy words, fantasy aspects, other dimensions, undeath and boss status.
- **Counts scale with the mob.** Ordinary mobs reach 3–4 aspects, fantasy and other-dimension mobs 4–5, bosses 6–7.
- **Rarity.** Rarer mobs carry more: bosses ×2, structure-only spawns ×1.5, rare spawns ×1.3. Hand-written mobs keep their written amounts; only their additions grow.
- **Dimension flavour is for mobs.** It goes on native mobs, and on interpreted items that come from them (cosmic cod, its bucket), never on blocks or hand-written items.
- **The Nether is an underworld.** No alienis there. Natives get infernum, plus ignis, exanimis, mortuus or spiritus only when those suit the mob: a ghast takes spiritus, a zombified piglin mortuus, a strider neither.
- **Canon detection.** Any mod that ships its own Thaumaturge aspect files is now recognised automatically. Its aspects are learned from but never added to, like Thaumaturge's own.
- **Craftsmanship narrowed.** It now applies only to items of non-canon mods made at the vanilla crafting table. Machine-recipe machina is gone.
- **Function layer narrowed.** It now applies only to items the interpreter fills in.
- **Removed:** dimension additions to hand-written and recipe-derived items (the `collectBase` mixin is gone).
- **New config options:** `mobEnrichment`, `canonNamespaces`.

## 1.5.0
- **Function layer.** Recipe-derived and interpreted items get a missing function aspect named by the head of their name: barrel → vacuos, lantern → lux, mace → aversio, gearbox → machina. Each pair is checked against the pack's hand-written items.
- **Every non-Overworld dimension is alien.** Natives get alienis plus a flavour: infernum/ignis for the Nether, vacuos/tenebrae for the End, tenebrae/praecantatio for the Otherside. This now applies to hand-written items and real creature entries too, as additions only, and flows through recipes.
- **Additions only.** Existing assignments are never replaced or reduced. Hand-written entries only receive dimension additions.
- **Balance tuning.** Guesses now match the original layer's scarcity:
  - The keep threshold is lowered from 12% to 8%.
  - Guesses keep about as many aspects as their close neighbours, so single-aspect guesses rise from 33% to 42% (original: 41%).
  - Additions are sized by each aspect's median minor share, capped at 20%.
  - Function and craftsmanship stop at 5 aspects.
- **Report:** `assignments.txt` lists every addition with its layer. The log counts them by layer.
- **New config option:** `functionLayer`.

## 1.4.0
- **Crafting complexity.** Recipe-derived items, including Thaumaturge's own derivations, now gain craftsmanship for each step beyond the first refinement: ordo for regular forms, machina for machine processing, fabrico for assembly. Oak stairs go from herba 5 to herba 5 + ordo 1. Hand-written entries are never changed.
- **Defining dimension aspects.** The Nether's signature includes infernum once Thaumic Additions is installed, and Nether natives are guaranteed a point of it. The End's defining aspect is alienis.
- **Learned creature defaults.** Mob-category defaults are now learned from the pack's own entity data. The fallback for land creatures is bestia + terra, matching Thaumaturge (a cow is bestia + terra), instead of victus.
- **Report:** `assignments.txt` lists every craftsmanship addition. The log counts them.
- **New config option:** `craftingComplexity`.
- **New doc:** `docs/PHILOSOPHY.md`, an analysis of how Thaumaturge and its add-ons assign aspects.

## 1.3.0
- **Dimension context.** Objects now take on the character of the dimension they come from. It's read from what each dimension actually spawns, generates and drops, and from items named after those creatures. Cosmic cod and its bucket and spawn egg now carry alienis.
- **Learned dimension signatures.** Each dimension's signature is learned from the aspects over-represented among its already-assigned natives, blended with a small lexicon prior. The End comes out alienis then tenebrae, the Nether ignis.
- **Natives keep their dimension.** An object native to a non-baseline dimension always gets at least 1 point of its dimension's leading aspect.
- **Multiplayer.** The server now sends its entity guess table to clients, so they no longer compute their own and always match the server.
- **New config options:** `dimensionContext`, `baselineDimensions`, `dimensionAspects`.

## 1.2.0
- **No third-party data bundled.** The interpreter now learns only from the player's own installation: live data maps, plus Thaumaturge's resolved index cache with this mod's own guesses excluded.
- **Self-correcting fresh installs.** A fresh install learns from data maps on its first launch, then rebuilds once more on the second launch to learn from the full index.
- **Config change:** `usePrior` is replaced by `learnFromThaumaturgeCache`.
- **Interpreted-item list:** the items this mod filled in are saved to `config/aspect_interpreter/interpreted_items.txt`.

## 1.1.0
- **Thaumaturge's cache:** moved aside once whenever this mod's version or config changes, so interpreted aspects actually reach the index.
- **Render-thread safety:** entity lookups on the render thread are now a plain table read. Guesses are precomputed at startup, or in the background on a remote-server client.
- **Cheaper index build:** removed backwards recipe reading and capped recipe evaluation.
- **Report timing:** the build is timed in the log, and the report is written after the index build.
- **Fewer bad guesses:** fire immunity only adds ignis to living creatures. Boats, minecarts and projectiles are read as the item they resemble.

## 1.0.0
- First version.
