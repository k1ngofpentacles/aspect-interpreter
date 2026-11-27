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

* **File:** `aspect_interpreter-1.11.1-neoforge-1.21.1-1.11.1.jar` (from `build/libs/`)
* **Release type:** Beta. It has run in one large pack; Beta sets expectations while others test it.
* **Game version:** 1.21.1
* **Mod loader:** NeoForge
* **Java:** 21
* **Environment:** Client and Server. It is required on both sides.
* **Relations:**

  * Thaumaturge: *Required Dependency*
  * Thaumic Additions Community Edition: *Optional Dependency*
  * Madman's Dreams: *Optional Dependency*
* **Changelog:** paste the 1.11.1 section of `CHANGELOG.md` (its first line is the upgrade note below).

## Description 

**Upgrading from 1.10.0 or older: delete `config/thaumaturge/aspect_index.json` once** (with the game closed). Older versions wrote their guesses into Thaumaturge's index cache; this version never touches that file, so Thaumaturge only rebuilds it without them if you remove it. A new install needs nothing.

**Every item and creature in your pack gets aspects, and they make sense.**

Thaumaturge assigns aspects from a hand-written list plus recipes. Anything outside that list is blank: modded blocks, foods, mobs, projectiles, decorations. Aspect Interpreter fills those gaps automatically.

**How it works**

It reads what your pack already does and applies the same pattern to the gaps:

* **Similar objects:** an andesite roof variant takes after other andesite blocks.
* **Words in the name:** each word's aspect mix is learned from your pack. Material words such as "crimson" or "oak" count only when the item shows it is that material (its block sound, tags or recipe), so a crimson sword is not read as crimson wood.
* **Meaning:** a table of what each aspect means, calibrated against your pack so misleading words lose influence.
* **Physical properties:** recipes of any type, block material, food, tools, armour, and mob category and size.

**What it won't do**

* **Touch anything hand-written.** An item or mob that has a hand-written aspect entry, from any mod, gets nothing from us: no guess, no addition, no enrichment.
* **Touch Thaumaturge's index.** Thaumaturge builds and caches its own index exactly as without us; our values are a separate overlay computed fresh at every launch and served on top of it.
* **Make a mob richer than its kind.** A mob we guess is held to the hand-written mobs of its threat tier (passive, weak hostile, strong hostile, mini-boss, boss), in total and per aspect.
* **Slow your game down.** About half a second once per launch in a 10,000-item pack. Same pack, same result, every launch.

**Tuning**

Every value we serve is listed in `config/aspect\\\_interpreter/assignments.json` with its confidence. A config file lets you set a confidence threshold, exclude mods, or turn the item or entity half off. To correct a guess, add a normal `base\\\_aspects` or `entity\\\_aspects` entry in a datapack.

**Compatibility**

Requires Thaumaturge 1.1+ on NeoForge 1.21.1. Thaumic Additions and Madman's Dreams aspects are used when installed.

Not affiliated with or endorsed by the Thaumaturge project.

## 

