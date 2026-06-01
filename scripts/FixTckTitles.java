///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Rewrites the page title (first `= ...` line) of TCK result files under
//DESCRIPTION `jakarta-ee-certification/` and `eclipse-microprofile-certification/`
//DESCRIPTION based on the file name suffix.
//DESCRIPTION
//DESCRIPTION jakarta-ee-certification (most specific first):
//DESCRIPTION   *web-profile-core-tck-results* -> = Web Profile Core TCK Results
//DESCRIPTION   *web-tck-results*              -> = Web TCK Results
//DESCRIPTION   *platform-tck-results*         -> = Platform TCK Results
//DESCRIPTION   *core-tck-results*             -> = Core TCK Results
//DESCRIPTION
//DESCRIPTION eclipse-microprofile-certification:
//DESCRIPTION   *server-full-tck-results*      -> = Server Full TCK Results
//DESCRIPTION   *server-web-tck-results*       -> = Server Web TCK Results
//DESCRIPTION   *server-tck-results*           -> = Server TCK Results
//DESCRIPTION
//DESCRIPTION Files that do not match any pattern are left untouched.
//DESCRIPTION Files whose title already matches are skipped (idempotent).
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixTckTitles.java [content-dir ...]           # dry run
//DESCRIPTION   jbang FixTckTitles.java [content-dir ...] --apply   # write changes
//DESCRIPTION
//DESCRIPTION If no content-dir is given, runs on content_enterprise and content_community.

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
 * Rewrites the AsciiDoc level-1 title of TCK results pages to a consistent
 * human-readable form, based on the file name suffix.
 *
 * <p>Patterns are matched in most-specific-first order within each section
 * to avoid shorter patterns swallowing longer ones (e.g. {@code core-tck-results}
 * must not match before {@code web-profile-core-tck-results}).
 */
public class FixTckTitles {

    /** Certification dirs and their ordered suffix→title maps. */
    static final Map<String, Map<String, String>> CERT_DIRS = new LinkedHashMap<>();
    static {
        Map<String, String> jakarta = new LinkedHashMap<>();
        jakarta.put("web-profile-core-tck-results", "= Web Profile Core TCK Results");
        jakarta.put("web-tck-results",              "= Web TCK Results");
        jakarta.put("platform-tck-results",         "= Platform TCK Results");
        jakarta.put("core-tck-results",             "= Core TCK Results");
        CERT_DIRS.put("jakarta-ee-certification", jakarta);

        Map<String, String> microprofile = new LinkedHashMap<>();
        microprofile.put("server-full-tck-results", "= Server Full TCK Results");
        microprofile.put("server-web-tck-results",  "= Server Web TCK Results");
        microprofile.put("server-tck-results",      "= Server TCK Results");
        CERT_DIRS.put("eclipse-microprofile-certification", microprofile);
    }

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
        }

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            System.out.println("content dir : " + content);
            System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println();

            for (Map.Entry<String, Map<String, String>> certEntry : CERT_DIRS.entrySet()) {
                String certDirName = certEntry.getKey();
                Map<String, String> patterns = certEntry.getValue();
                Path certDir = content.resolve(certDirName);

                if (!Files.isDirectory(certDir)) {
                    System.out.println("  [skip] " + certDirName + " not found");
                    continue;
                }

                System.out.println("  " + certDirName + ":");
                int changed = 0, skipped = 0, unmatched = 0;

                for (Path p : collect(certDir)) {
                    String stem = stem(p);
                    String newTitle = matchTitle(stem, patterns);
                    if (newTitle == null) {
                        unmatched++;
                        System.out.println("    [no match] " + content.relativize(p));
                        continue;
                    }

                    String original = Files.readString(p, StandardCharsets.UTF_8);
                    String rewritten = rewriteTitle(original, newTitle);

                    if (rewritten.equals(original)) {
                        skipped++;
                    } else {
                        changed++;
                        String oldTitle = firstLine(original);
                        System.out.printf("    [%s] %s%n      - %s%n      + %s%n",
                                apply ? "fixed" : "would fix",
                                content.relativize(p), oldTitle, newTitle);
                        if (apply) {
                            Files.writeString(p, rewritten, StandardCharsets.UTF_8);
                        }
                    }
                }

                System.out.printf("  Summary: %d %s, %d already correct, %d unmatched.%n%n",
                        changed, apply ? "fixed" : "would fix", skipped, unmatched);
            }
        }
    }

    /** Match the file stem against patterns (most specific first). */
    static String matchTitle(String stem, Map<String, String> patterns) {
        for (Map.Entry<String, String> entry : patterns.entrySet()) {
            if (stem.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Replace the first level-1 title line ({@code = ...}) with {@code newTitle}.
     * If no level-1 title is found, prepend it.
     */
    static String rewriteTitle(String text, String newTitle) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("= ") && (line.length() < 3 || line.charAt(2) != '=')) {
                if (line.equals(newTitle)) return text; // already correct
                lines[i] = newTitle;
                return String.join("\n", lines);
            }
        }
        return newTitle + "\n" + text;
    }

    static String stem(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".adoc") ? name.substring(0, name.length() - 5) : name;
    }

    static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl >= 0 ? text.substring(0, nl) : text;
    }

    static List<Path> collect(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".adoc"))
             .sorted()
             .forEach(out::add);
        }
        return out;
    }
}
