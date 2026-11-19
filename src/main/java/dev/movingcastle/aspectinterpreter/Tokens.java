package dev.movingcastle.aspectinterpreter;

import java.util.ArrayList;
import java.util.List;

/** Splits registry IDs into normalised words. Must stay identical to the offline model used for evaluation. */
final class Tokens {
    private Tokens() {}

    static String norm(String t) {
        t = t.toLowerCase(java.util.Locale.ROOT).replaceAll("\\d+$", "");
        if (t.length() > 4 && t.endsWith("ies")) t = t.substring(0, t.length() - 3) + "y";
        else if (t.length() > 4 && t.endsWith("ches")) t = t.substring(0, t.length() - 2);
        else if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static String namespace(String id) {
        int c = id.indexOf(':');
        return c < 0 ? "minecraft" : id.substring(0, c);
    }

    static List<String> of(String id) {
        int c = id.indexOf(':');
        String path = c < 0 ? id : id.substring(c + 1);
        List<String> out = new ArrayList<>();
        for (String part : path.split("[_/\\-.]")) {
            if (part.isEmpty()) continue;
            String n = norm(part);
            if (n.length() > 1) out.add(n);
        }
        return out;
    }
}
