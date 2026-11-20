# Changelog

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
