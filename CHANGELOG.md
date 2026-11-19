# Changelog

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
