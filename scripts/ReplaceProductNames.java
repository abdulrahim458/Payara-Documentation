///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Replaces Payara product names with Azul-branded equivalents in .adoc files.
//DESCRIPTION
//DESCRIPTION Enterprise content (content_enterprise):
//DESCRIPTION   Payara Platform Enterprise -> Azul Payara
//DESCRIPTION   Payara Micro               -> Azul Payara Micro
//DESCRIPTION   Payara Server              -> Azul Payara Server
//DESCRIPTION
//DESCRIPTION Community content (content_community):
//DESCRIPTION   Payara Platform Community  -> Azul Payara Community
//DESCRIPTION   Payara Micro               -> Azul Payara Micro Community
//DESCRIPTION   Payara Server              -> Azul Payara Server Community
//DESCRIPTION
//DESCRIPTION Replacements are applied in the order listed above (most specific first)
//DESCRIPTION to avoid partial matches (e.g. "Payara Micro" is handled before "Payara Server"
//DESCRIPTION so that "Payara Micro Server" becomes "Azul Payara Micro Server" not
//DESCRIPTION "Azul Payara Micro Azul Payara Server").
//DESCRIPTION
//DESCRIPTION content_shared is intentionally excluded: it is included in both builds,
//DESCRIPTION so its content cannot be branded for one edition only.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang ReplaceProductNames.java           # dry run (enterprise + community)
//DESCRIPTION   jbang ReplaceProductNames.java --apply   # write changes
//DESCRIPTION   jbang ReplaceProductNames.java content_enterprise --apply  # one dir only

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Replaces Payara product names with their Azul-branded equivalents across
 * {@code .adoc} source files.
 *
 * <p>Each content directory has its own replacement map. Replacements are applied
 * sequentially in declaration order — most specific (longest) phrases first — so
 * that compound names like "Payara Micro Server" resolve correctly without a
 * second replacement pass turning intermediate results into double-branded strings.
 *
 * <p>{@code content_shared} is intentionally skipped: shared pages are included in
 * both enterprise and community builds, so they cannot carry edition-specific branding.
 */
public class ReplaceProductNames {

    public static void main(String[] args) throws IOException {
        boolean apply = false;
        List<Path> contentDirs = new ArrayList<>();

        for (String arg : args) {
            if ("--apply".equals(arg)) apply = true;
            else if (!arg.startsWith("--")) contentDirs.add(Paths.get(arg));
        }
        if (contentDirs.isEmpty()) {
            contentDirs.add(Paths.get("content_enterprise"));
            contentDirs.add(Paths.get("content_community"));
            contentDirs.add(Paths.get("content_shared"));
        }

        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("Payara Platform Enterprise", "Azul Payara");
        replacements.put("Payara Platform Community",  "Azul Payara Community");

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();

            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            System.out.println("content dir  : " + content);
            System.out.println("mode         : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println("replacements :");
            replacements.forEach((from, to) ->
                    System.out.printf("  %-40s -> %s%n", "\"" + from + "\"", "\"" + to + "\""));
            System.out.println();

            int changed = 0;
            int unchanged = 0;

            for (Path p : collect(content)) {
                String original = Files.readString(p, StandardCharsets.UTF_8);
                String rewritten = applyReplacements(original, replacements);
                if (!rewritten.equals(original)) {
                    changed++;
                    if (apply) {
                        Files.writeString(p, rewritten, StandardCharsets.UTF_8);
                        System.out.println("  [updated] " + content.relativize(p));
                    } else {
                        System.out.println("  [would update] " + content.relativize(p));
                        printDiff(original, rewritten, content.relativize(p).toString());
                    }
                } else {
                    unchanged++;
                }
            }

            System.out.printf("%nSummary: %d files %s, %d unchanged.%n%n",
                    changed, apply ? "updated" : "would be updated", unchanged);
        }
    }

    /** Apply all replacements sequentially to the given text. */
    static String applyReplacements(String text, Map<String, String> replacements) {
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    /**
     * Print a compact diff showing only the lines that changed (with ± prefix),
     * limited to the first 20 changed lines to keep dry-run output readable.
     */
    static void printDiff(String original, String rewritten, String label) {
        String[] oldLines = original.split("\n", -1);
        String[] newLines = rewritten.split("\n", -1);
        int shown = 0;
        int limit = 20;
        for (int i = 0; i < Math.min(oldLines.length, newLines.length); i++) {
            if (!oldLines[i].equals(newLines[i])) {
                if (shown == 0) System.out.printf("    %s:%n", label);
                System.out.printf("    -%s%n    +%s%n", oldLines[i], newLines[i]);
                if (++shown >= limit) {
                    System.out.printf("    ... (%d more changed lines not shown)%n",
                            countDiffs(oldLines, newLines) - limit);
                    break;
                }
            }
        }
    }

    static int countDiffs(String[] a, String[] b) {
        int count = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) count++;
        }
        return count;
    }

    static List<Path> collect(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> Files.isRegularFile(p)
                       && p.toString().endsWith(".adoc")
                       && !isReleaseNotes(root, p))
             .sorted()
             .forEach(out::add);
        }
        return out;
    }

    /** Returns true if the path is inside a release-notes directory. */
    static boolean isReleaseNotes(Path root, Path p) {
        Path rel = root.relativize(p);
        for (Path part : rel) {
            String seg = part.toString().toLowerCase();
            if (seg.equals("release-notes") || seg.equals("release_notes")) return true;
        }
        return false;
    }
}
