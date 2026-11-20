# Changelog

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
