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
| Workmanship is hand-planted, so derived items lose it | **Crafting complexity (1.4.0).** Each crafting step beyond the first refinement adds craftsmanship of its kind: **ordo** for regular forms (stairs, slabs, walls, bricks; stonecutting), **machina** for machine processing (Create, Forestry and similar), **fabrico** for assembly. It is about 6% of the material per step, capped at 30%. Cooking and magic add none, since Thaumaturge already adds heat and praecantatio. Authored items are never touched, and the additions flow downstream through Thaumaturge's own rule. |
| Habitat element for creatures | Category defaults are **learned** from the pack's own `entity_aspects` (only aspects most members share), falling back to bestia + terra for land creatures and aqua + bestia for water. |
| Dimension as habitat | **Dimension context (1.3.0).** Signatures are learned from each dimension's assigned natives. **Defining aspects** (1.4.0) always join when the pack has them: infernum for the Nether once Thaumic Additions is installed, alienis for the End. Natives are guaranteed a point of it. |
| Addon aspects are rare | They are only used where an add-on defines them or a defining dimension aspect calls for them. |
| 2–3 aspects, unit 5 | At most 4 aspects, minor ones dropped below 12%. Amounts come from neighbours, so they follow the pack's own scale. |

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

## 5. Open gaps

**Missing function.** The barrel shows that a derived item can lose a function its authored cousin has: a barrel holds things like a chest, but gets no vacuos. A *function layer* could add the lexicon's function aspect to derived items whose names call for one strongly (container → vacuos, light → lux), at secondary weight. It isn't implemented yet. Function words are much noisier than shape words, so it needs its own calibration against the authored items first.
