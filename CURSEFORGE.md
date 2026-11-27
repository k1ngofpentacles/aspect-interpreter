# CurseForge listing

## Project settings

* **Name:** Aspect Interpreter
* **Summary** (CurseForge allows \~150 characters): Automatically gives Thaumaturge aspects to every item and entity that has none, learned from the patterns your pack already uses.
* **Main category:** Magic. **Extra categories:** Addons, Utility \& QoL.
* **License:** MIT License
* **Source:** https://github.com/k1ngofpentacles/aspect-interpreter
* **Issues:** https://github.com/k1ngofpentacles/aspect-interpreter/issues
* **Allow third-party distribution (modpacks):** Yes

## File upload

* **File:** `aspect_interpreter-1.10.0-neoforge-1.21.1-1.10.0.jar` (from `build/libs/`)
* **Release type:** Beta. It has run in one large pack; Beta sets expectations while others test it.
* **Game version:** 1.21.1
* **Mod loader:** NeoForge
* **Java:** 21
* **Environment:** Client and Server. It is required on both sides.
* **Relations:**

  * Thaumaturge: *Required Dependency*
  * Thaumic Additions Community Edition: *Optional Dependency*
  * Madman's Dreams: *Optional Dependency*
* **Changelog:** paste the 1.10.0 section of `CHANGELOG.md`.

## Description 

**Every item and creature in your pack gets aspects, and they make sense.**

Thaumaturge assigns aspects from a hand-written list plus recipes. Anything outside that list is blank: modded blocks, foods, mobs, projectiles, decorations. Aspect Interpreter fills those gaps automatically.

**How it works**

It reads what your pack already does and applies the same pattern to the gaps:

* **Similar objects:** an andesite roof variant takes after other andesite blocks.
* **Words in the name:** each word's aspect mix is learned from your pack. Material words such as "crimson" or "oak" count only when the item shows it is that material (its block sound, tags or recipe), so a crimson sword is not read as crimson wood.
* **Meaning:** a table of what each aspect means, calibrated against your pack so misleading words lose influence.
* **Physical properties:** recipes of any type, block material, food, tools, armour, and mob category and size.

**What it won't do**

* **Override anything real.** Existing data-map entries and recipe-derived aspects always win.
* **Slow your game down.** It works once, inside Thaumaturge's own index build, which Thaumaturge then caches. In a 10,000-item pack it added about 0.3 seconds to that one build.

**Tuning**

Every guess is listed in `config/aspect\\\_interpreter/assignments.txt` with its confidence. A config file lets you set a confidence threshold, exclude mods, or turn the item or entity half off. To correct a guess, add a normal `base\\\_aspects` or `entity\\\_aspects` entry in a datapack.

**Compatibility**

Requires Thaumaturge 1.1+ on NeoForge 1.21.1. Thaumic Additions and Madman's Dreams aspects are used when installed.

Not affiliated with or endorsed by the Thaumaturge project.

## 

