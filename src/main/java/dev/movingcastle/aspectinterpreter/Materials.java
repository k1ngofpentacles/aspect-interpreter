package dev.movingcastle.aspectinterpreter;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * Material words that must earn their place. "crimson" means wood only when the item shows it is wood: a wooden
 * block sound, a wood tag, or an ingredient that carries one. Otherwise the word is only part of a name
 * (a crimson blade, a crimson cult banner) and the name model ignores it.
 * Plain Java on purpose: the evidence is gathered by the caller, so this logic is unit-testable.
 */
final class Materials {
    private Materials() {}

    record Material(String name, Set<String> words, Set<String> sounds, List<String> tags) {}

    /**
     * The material words of the id that have no corroboration. {@code evidence} is asked (lazily, at most once per
     * material) whether the item is of that material.
     */
    static Set<String> uncorroborated(Lexicon lex, List<String> tokens, java.util.function.Predicate<Material> evidence) {
        if (lex.materials.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (Material m : lex.materials.values()) {
            boolean has = false;
            for (String t : tokens) if (m.words().contains(t)) { has = true; break; }
            if (!has) continue;
            if (evidence.test(m)) continue;
            for (String t : tokens) if (m.words().contains(t)) out.add(t);
        }
        return out;
    }

    /** True if the tag id matches one of the patterns ("ns:name", or "*" as a wildcard inside the path). */
    static boolean tagMatches(List<String> patterns, String tagId) {
        for (String p : patterns) {
            if (p.indexOf('*') < 0) { if (p.equals(tagId)) return true; continue; }
            String regex = java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q");
            if (tagId.matches(regex)) return true;
        }
        return false;
    }
}
