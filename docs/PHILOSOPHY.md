# How Thaumaturge assigns aspects, and how Aspect Interpreter extends it

This is an analysis of the hand-written aspect data in Thaumaturge 1.1.0 and the add-ons in the author's pack, and of the code that derives everything else. Each rule Aspect Interpreter applies is traced back to a pattern found here.

**Sources:**
- Thaumaturge's `base_aspects` (544 entries, 88 of them tags) and `entity_aspects` (111 entries).
- The `base_aspects` data maps of Thaumic Additions CE, Madman's Dreams and Create: Thaumaturgy.
- Thaumaturge's recipe contributors, read from bytecode.
- 1,258 vanilla recipes, measured against a 6,920-item resolved index.

## 1. Two layers: authored and derived

Thaumaturge assigns aspects in two layers.

**Authored.** About 450 items are written by hand. They are raw materials (logs, ores, stone, mob drops) and *key* crafted items whose meaning recipes could not produce (crafting table, chest, piston, bookshelf).

**Derived.** Everything else comes from recipes, resolved recursively:

| Recipe | Rule |
|---|---|
| Crafting | Sum of ingredients × 0.75 ÷ output count. Each aspect is rounded down, and amounts from 0.75 to 1 become 1. The cheapest recipe wins. |
| Arcane crafting | As crafting, plus praecantatio = √(1 + vis ÷ 2) ÷ output count |
| Crucible, infusion | Ingredients plus the recipe's catalyst aspects, drained |
| Cooking | The input carried through |

**Crafting conserves; only magic adds.** An ordinary recipe can never introduce an aspect, and rounding removes small ones. In vanilla, 79% of crafted items carry nothing their ingredients didn't, and they lose 0.34 aspects on average. Variety *falls* with crafting depth:

| Crafting depth | Items | Aspects per item |
|---|---:|---:|
| 0 (raw or authored) | 728 | 2.59 |
| 1 | 225 | 2.34 |
| 2 | 311 | 2.30 |
| 3 | 99 | 2.00 |

Oak stairs are herba 5, exactly like planks. A barrel, derived, is herba 24 only; a chest, authored, is herba 40 + vacuos 15.

## 2. What the authors write by hand

Authored items follow a consistent grammar: **material + function**, with workmanship where an object is visibly worked.

| Aspect | Hand-written on | Reads as |
|---|---|---|
| **fabrico** | cooked foods, wool and string, carved pumpkin, bamboo mosaic, pottery sherds, anvil, brewing stand, crafting table, painting | *worked by hand* |
| **machina** | doors, gates, buttons, pressure plates, rails, clock, comparator, dispenser, dropper, hopper, piston; Create's shafts and cogs (Create: Thaumaturgy) | *has moving parts* |
| **ordo** | storage blocks, concrete, stone bricks, deepslate, dirt path, blue ice | *compressed, refined, regular* |
| **instrumentum** | bow, fishing rod, flint, flint and steel, research table | *tool, not weapon* |
| **humanus** | player head, piglin head, rotten flesh | *people*, never *made by people* |
| **vacuos, cognitio, lux...** | chest and bucket; bookshelf; light sources | *what the object does* |

They plant these on a few key items and let the recipe rule carry them downstream: everything made with a door inherits machina, and a lectern inherits cognitio from its bookshelf. Where no key ingredient carries a function, it is lost, as with the barrel.

### Conventions

| Convention | Measured in the authored data |
|---|---|
| Aspects per entry | 2–3 in 77% of entries, rarely 5 or more |
| Amounts | 5 is the unit; 10, 15 and 20 for major aspects; 1–3 for traces; 25–60 for large or valuable items |
| Add-on core aspects | Rare outside their own items: Thaumic Additions' infernum, draco and similar are seeded only on its own vis pods (one aspect, amount 5) |

The add-ons each follow the same grammar in their domain:
- **Create: Thaumaturgy:** machina on Create parts, ordo on the precision mechanism.
- **Madman's Dreams:** cognitio for the mind, humanus on the diary and mask.

## 3. Creatures

Entity entries follow **nature + habitat + drive**. They typically have 3 aspects, with a median total of 25.

| Part | Pattern | Examples |
|---|---|---|
| Nature | bestia (57 of 111), humanus (30), exanimis (22) | villager humanus 15; zombie exanimis 20 + humanus 10 |
| Habitat | terra for land animals (23), aqua (20), aer or volatus for fliers | cow bestia 15 + terra 15 |
| Drive or power | what the creature does or wants | piglin desiderium (greed); enderman desiderium (it takes blocks); warden sensus |
| Made beings | carry their making | iron golem: metallum + humanus + machina + praecantatio |
| Otherworldly | alienis, including Nether mobs in core Thaumaturge, which has no infernum | blaze alienis 5 + ignis 15 |

Victus (life) is almost never used for mobs.

## 4. How Aspect Interpreter applies this

| Finding | Rule in the mod |
|---|---|
| Material first, function second | Neighbours and word profiles carry material. The meaning lexicon and physical evidence add function, at lower weight. |
| Recipes conserve | Thaumaturge's own derivation is kept for material, and recipe types it doesn't read are evaluated the same way (× 0.75). |
| Workmanship is hand-planted, so derived items lose it | **Crafting complexity (1.4.0, narrowed in 1.6.0).** For items of non-canon mods made at the vanilla crafting table, each step beyond the first refinement adds **ordo** for regular forms (stairs, slabs, walls, bricks) or **fabrico** for assembly, about 6% of the material per step, capped at 30%. Canon mods' items, hand-written or derived, are never touched. |
| Habitat element for creatures | Category defaults are **learned** from the pack's own `entity_aspects` (only aspects most members share), falling back to bestia + terra for land creatures and aqua + bestia for water. |
| Function is hand-planted, so derived items lose it | **Function layer (1.5.0, narrowed in 1.6.0).** An interpreted item whose name's *head* (its last word or two) names a function gets that aspect if it lacks it: container → vacuos, lamp → lux, armour → praemunio, book → cognitio. Each pair is checked against the pack's hand-written items and dropped if the authors don't do it (Thaumaturge's bottles aren't vacuos). It adds one aspect at most, sized like a minor aspect. |
| Creatures are nature + habitat + drive | **Mob enrichment (1.6.0).** See section 8. Mobs are where variety and dimension flavour belong. |
| Dimension as habitat | **Dimension context (1.3.0, reworked in 1.6.0).** Every world but the Overworld is alien, except the Nether, an underworld: this world's below rather than an outer one. Dimension flavour goes on native mobs and on interpreted items that come from them, never on blocks or hand-written items. |
| Addon aspects are rare | They are only used where an add-on defines them or a mob's dimension calls for them. |
| Hand-written aspects are authorial | **Canon (1.6.0).** Vanilla, Thaumaturge and its add-ons, Create and its add-ons, and any mod detected shipping its own aspect files are learned from but never added to (their mobs excepted). |
| Families share a defining aspect, but recipes lose it | **Family consistency (1.7.0).** Crafted and interpreted items take the 1–2 key aspects their hand-written relatives agree on (crafted candles take vanilla's lux), and bright light sources carry lux. See section 9. |
| Scarcity | See section 6. |

### Prototype results on vanilla recipe chains

| Crafting depth | Aspects per item before | After |
|---|---:|---:|
| 2 | 2.30 | 3.25 |
| 3 | 2.00 | 2.80 |

| Item | Before | After |
|---|---|---|
| oak stairs | herba 5 | herba 5, ordo 1 |
| barrel | herba 24 | herba 24, fabrico 2 |
| lectern | herba 25, cognitio 18 | herba 25, cognitio 18, fabrico 6 |

Planks, ingots and authored items such as the chest and crafting table are unchanged.

## 5. The original layer's balance

Measured over the 6,920-item resolved index (hand-written and recipe-derived together):

| Measure | Value |
|---|---|
| Aspects per item | mean 2.63; **40% carry a single aspect** |
| Total amount | median 15 hand-written, 10 recipe-derived |
| Minor aspects | median **14%** of the item's total (middle half 5–25%). Function aspects 9–17%: vacuos 12%, lux 17%, cognitio 15%, alienis 9% |
| Scarcity | herba 55%, metallum 18%, terra 18%, bestia 12% … alienis 3.4%, tenebrae 3.3%, lux 2.9%, infernum 0.4% |

So most things are one or two materials, minor aspects are small, and the magical and otherworldly aspects are rare.

## 6. Tuning to that balance (1.5.0)

**Guesses were too stingy, not too generous.** On held-out items, version 1.4.0's guesses carried 1.92 aspects per item against 2.56 in the original layer, and gave rare aspects only half their true rate. They collapsed onto common materials like herba.

| Change | Effect on held-out items |
|---|---|
| Keep threshold lowered from 12% to 8% | 2.31 aspects per item; prevalence error 0.78 → 0.64; top-aspect accuracy unchanged (72.6%) |
| Aspect count matched to close neighbours (similarity ≥ 0.5) | Single-aspect guesses 33% → 42% (original layer: 41%); set overlap 0.684 → 0.711 |
| A scarcity prior (rare aspects need stronger evidence) | Tested and rejected: every setting lowered accuracy, and the model already under-predicts rare aspects |

Guess totals match the original at the median (ratio 1.00).

**Additions are sized like the original's minor aspects.** Each added aspect uses that aspect's median minor share in the pack, capped at 20%. Function and craftsmanship stop at 5 aspects, which only 1% of hand-written items reach. (Since 1.6.0, hand-written items receive no additions at all; only mobs do.)

**Whole-pack simulation** (original index + guesses for the 3,674 unassigned items + function and craftsmanship layers; the dimension layer needs a running world and isn't included):

| Measure | Original layer | Whole pack |
|---|---:|---:|
| Aspects per item | 2.63 | 2.71 |
| Rank order of aspect scarcity (Spearman) | — | 0.978 |
| lux | 2.9% | 4.6% (authors' own hand-written rate: 4.2%) |
| vacuos | 4.8% | 6.5% (hand-written rate: 6.1%) |

The scarcity order of aspects is essentially unchanged. Function aspects rise toward the rate the authors use by hand, not beyond it. terra and bestia are higher among guesses (40% and 29%) because of the unassigned population itself: 94% of Macaw's paths and 80% of the roofs are stone, and Butchery and Alex's Mobs are mostly animal.

## 7. Open questions

- **Mob enrichment in modded packs:** the vanilla preview below uses vanilla's spawn data; modded mobs depend on how their mods register spawns and tags. The report lists every mob's fantasy score, rarity and additions.
- **Single-aspect guesses:** guesses for items with no close neighbour still rarely have a single aspect (16% across the unassigned items).

## 8. Three axes of balance (1.6.0)

An aspect source is balanced along three axes: how rare its aspects are, how scarce the resource is, and how much processing it takes to extract them.

- **Mobs** are the richest source and the hardest to process. A mob carries the most varied mix (nature, habitat, drive), and taking aspects from a living creature is harder than breaking a block. So mobs are where variety belongs, and where dimension flavour belongs.
- **Blocks and items** stay as their authors wrote them. They are plentiful, and a single material is what most of them are. The original layer keeps 40% of items at one aspect, and that is preserved.
- **Resource scarcity scales mobs only.** A boss, a structure guardian or a rare spawn carries more than a common animal: ×2, ×1.5 and ×1.3 on what is added.

**Fantasy and ordinary creatures.** Thaumaturge's own mobs show the split. Its animals are nature + habitat (a cow is bestia + terra), while its fantasy creatures carry their otherness (a blaze is alienis + ignis, a phantom exanimis + spiritus). So habitat and dimension weigh more as a mob's fantasy score rises.

**The Nether is an underworld.** In the original data, Nether creatures carry fire and death, not alienness: ghasts are exanimis + ignis, zombified piglins exanimis + ignis + bestia, hoglins bestia + aversio + ignis. Only the blaze carries alienis. The End's natives do carry alienis (endermites, shulkers, the dragon). The rule follows the authors.

**Thaumaturge's biome aspects are a palette, not a habitat.** Its biome map gives aura nodes a primal mix (cold biomes ordo, plains aer, swamps perditio). Read as habitat it would make a polar bear orderly. So a landscape reading from biome tags leads, and the palette counts at half weight where the landscape is silent.

**Vanilla preview** (Thaumaturge's 87 vanilla mob entries, vanilla spawn and loot data):

| Mob | Hand-written | Added |
|---|---|---|
| ghast | exanimis 15, ignis 15 | infernum 4, spiritus 3, aversio 3 |
| zombified piglin | exanimis 15, ignis 15, bestia 10 | infernum 7, mortuus 7 |
| strider | bestia 10, ignis 10, motus 5 | infernum 4 |
| stray | exanimis 20, humanus 5, vinculum 5 | gelum 5, aversio 4 |
| cow | bestia 15, terra 15 | herba 3 |
| wither (boss) | exanimis 50, perditio 25, ignis 25 | mortuus 21, potentia 21, aversio 19 |
| allay, breeze, vex, shulker | (4 aspects each) | nothing: already rich, and no candidate is strong enough |

Mean aspects per mob 2.9 → 3.7; mean total 27 → 31.

## 9. Families (1.7.0)

Similar objects share their defining aspect in the hand-written data: every vanilla candle is lux 5, every pressure plate machina + sensus, every button machina. The recipe rule loses that aspect whenever no ingredient carries it. In the author's pack, 36 Forestry big and jumbo candles and 16 Thaumaturge candles were crafted from wax and dye and carried no lux. Their aspects were scattered across bestia, fabrico and each dye's colour aspects.

**Light is drawn by brightness.** Among vanilla's hand-written blocks that give off light, the bright ones (torches, glowstone, end rods, froglights, shroomlight) carry lux. The faint ones (brewing stand, dragon egg, sculk sensor, ender chest, magma block) never do. Overall 12 of 25 light-emitting blocks carry lux, and those 12 are nearly all the bright ones; among blocks at 10+, only special blocks like the beacon and conduit lack it. So only light 10+ counts.

**Preview** on the author's index. It doesn't include the same-kind guard or light sources, which need the running game. Thaumaturge's own candles use the wool sound, so the guard keeps them from learning from vanilla's candles. They shine at light 14, so they get lux as light sources instead.

| Family | Key | Items gaining it |
|---|---|---:|
| candle | lux | 70 |
| pressure plate | machina, sensus | 44 |
| button | machina | 43 |
| trapdoor | motus | 43 |
| wool | fabrico | 32 |
| door | vinculum, machina | 17 |
