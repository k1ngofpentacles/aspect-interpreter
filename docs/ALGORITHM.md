# Aspect Interpreter: how it assigns aspects

Aspect Interpreter is a NeoForge 1.21.1 add-on for Thaumaturge 1.1+. It gives aspects to any item or entity type that has none. It never overrides an existing entry.

## Where it plugs in

| Target | Hook | Why it is a pure fallback |
|---|---|---|
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
| Category (entities) | Monster → aversio/bestia; creature → bestia/victus; water → aqua/bestia; ambient → bestia/volatus/tenebrae; fire immunity adds a little ignis, for living creatures only; non-living entities (boats, minecarts, arrows, projectiles) are read as the item they are or most resemble | 0.7 (item match 1.5) |

**3. Blend.** Take the weighted average of the distributions. Keep aspects holding at least 12% of the mass, up to 4.

**4. Size the amount.** Use the neighbours' typical total, else the word profiles' median, else 8 for items. For entities, use 25 scaled by body volume.

**5. Score confidence.** Confidence = total evidence weight ÷ 2, capped at 1. Below `minConfidence` (default 0.15), the ID is left unassigned.

**6. Check the aspects exist.** Aspects the running pack doesn't define (for example, an addon aspect seen in a data map of a mod that is not loaded) are dropped, and the rest are rescaled.

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

`items`, `entities`, `learnFromThaumaturgeCache`, `minConfidence`, `writeReport`, `excludedNamespaces`, `excludedIds`, `excludedWords`.

After each reload, every guess is written to `config/aspect_interpreter/assignments.txt` with its confidence and strongest evidence. To override a guess, give the object a real `base_aspects` or `entity_aspects` entry; real entries always win.

## Runtime cost

All of the work happens once, inside Thaumaturge's own index build, and Thaumaturge caches the result to `config/thaumaturge/aspect_index.json`. Later launches load that cache and cost nothing.

- **Learning:** about 50–150 ms each time data maps load.
- **Item guesses:** about 0.2 ms each. Each item's guess is reused across Thaumaturge's two resolution passes.
- **Recipe evaluation:** capped at 4 recipes per item and 2 options per ingredient.
- **Entities:** every entity type is pre-guessed once (on the server thread at startup, or on a background thread when connected to a remote server). The per-frame lookup from Thaumaturge's goggles overlay is a pure table read and never computes anything.
- **First install:** Thaumaturge's cache doesn't know about this mod. Whenever the mod's version or config changes, the old cache is moved aside to `aspect_index.before_interpreter.json`, so Thaumaturge rebuilds once with interpreted aspects included.
- **Logging:** the log reports how many items were filled and how many milliseconds the interpreter added to the build. The report is written after the build finishes.
