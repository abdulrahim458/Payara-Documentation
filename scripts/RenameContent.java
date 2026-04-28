///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Normalises the Payara docs `content/` tree for the Azul GitLab workflow.
//DESCRIPTION
//DESCRIPTION   * lowercases directory and `.adoc` file names
//DESCRIPTION   * replaces spaces and parentheses with hyphens (collapses runs of `-`)
//DESCRIPTION   * keeps version dots intact (e.g. `6.3.0`)
//DESCRIPTION   * rewrites every `xref:` (incl. `xref:ROOT:` / `xref:docs:`) target
//DESCRIPTION   * rewrites every `file:` entry in `content/toc.yaml`
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang RenameContent.java [content-dir]            # dry run, prints plan
//DESCRIPTION   jbang RenameContent.java [content-dir] --apply    # actually performs the changes
//DESCRIPTION
//DESCRIPTION Default content-dir is `./content` (relative to the working directory).
//DESCRIPTION Idempotent: a second run on an already-normalised tree is a no-op.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class RenameContent {

    // Captures the inside of `xref:<inside>[`. The path uses spaces, so we stop at `[`.
    private static final Pattern XREF = Pattern.compile("xref:([^\\[]+)\\[");

    // AsciiDoc module prefix (e.g. `ROOT:`, `docs:`, `partials:`) at the start of an xref body.
    private static final Pattern MODULE_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*:");

    // toc.yaml entry: `      - file: Some Path/Page` (also matches `file:` without leading dash).
    private static final Pattern TOC_FILE = Pattern.compile("^(\\s*-?\\s*file:\\s*)(.*\\S)\\s*$", Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        boolean apply = false;
        Path contentDir = Paths.get("content");
        for (String arg : args) {
            if ("--apply".equals(arg)) {
                apply = true;
            } else if (!arg.startsWith("--")) {
                contentDir = Paths.get(arg);
            }
        }
        final Path content = contentDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(content)) {
            System.err.println("Not a directory: " + content);
            System.exit(2);
        }

        System.out.println("content dir : " + content);
        System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
        System.out.println();

        // --- Phase 1: rewrite xrefs in every .adoc file ---
        List<Path> adocs = collect(content,
                p -> p.toString().endsWith(".adoc") && Files.isRegularFile(p));
        int adocChanged = 0;
        for (Path p : adocs) {
            String original = Files.readString(p, StandardCharsets.UTF_8);
            String rewritten = rewriteXrefs(original);
            if (!rewritten.equals(original)) {
                adocChanged++;
                if (apply) {
                    Files.writeString(p, rewritten, StandardCharsets.UTF_8);
                } else {
                    System.out.println("  xref-edit  : " + content.relativize(p));
                }
            }
        }
        System.out.println("Phase 1: xref rewrite -> " + adocChanged + " of " + adocs.size() + " .adoc files " + (apply ? "updated" : "would change"));
        System.out.println();

        // --- Phase 2: rewrite toc.yaml ---
        Path toc = content.resolve("toc.yaml");
        if (Files.isRegularFile(toc)) {
            String original = Files.readString(toc, StandardCharsets.UTF_8);
            String rewritten = rewriteTocYaml(original);
            if (!rewritten.equals(original)) {
                if (apply) {
                    Files.writeString(toc, rewritten, StandardCharsets.UTF_8);
                }
                System.out.println("Phase 2: toc.yaml -> " + (apply ? "updated" : "would change"));
            } else {
                System.out.println("Phase 2: toc.yaml -> no changes needed");
            }
        } else {
            System.out.println("Phase 2: toc.yaml not found at " + toc);
        }
        System.out.println();

        // --- Phase 3: rename .adoc files (deepest first so parents stay valid) ---
        List<Path> filesToRename = collect(content,
                p -> Files.isRegularFile(p) && p.toString().endsWith(".adoc"));
        filesToRename.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed());
        int filesRenamed = 0;
        for (Path p : filesToRename) {
            String oldName = p.getFileName().toString();
            String newName = slugifyAdocFilename(oldName);
            if (!newName.equals(oldName)) {
                Path target = p.resolveSibling(newName);
                filesRenamed++;
                if (apply) {
                    safeRename(p, target);
                } else {
                    System.out.println("  rename file: " + content.relativize(p) + "  ->  " + newName);
                }
            }
        }
        System.out.println("Phase 3: file renames -> " + filesRenamed);
        System.out.println();

        // --- Phase 4: rename directories (deepest first) ---
        List<Path> dirsToRename = collect(content,
                p -> Files.isDirectory(p) && !p.equals(content));
        dirsToRename.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed());
        int dirsRenamed = 0;
        for (Path p : dirsToRename) {
            // Re-resolve, in case Phase 3 renamed something inside a parent that is still old-cased
            // (we walk the original list, but since we go deepest-first parents are still intact).
            String oldName = p.getFileName().toString();
            String newName = slugifySegment(oldName);
            if (!newName.equals(oldName)) {
                Path target = p.resolveSibling(newName);
                dirsRenamed++;
                if (apply) {
                    safeRename(p, target);
                } else {
                    System.out.println("  rename dir : " + content.relativize(p) + "  ->  " + newName);
                }
            }
        }
        System.out.println("Phase 4: dir renames  -> " + dirsRenamed);
        System.out.println();

        if (!apply) {
            System.out.println("Dry run complete. Re-run with --apply to execute the changes.");
        } else {
            System.out.println("Done.");
        }
    }

    // ---------- slug helpers ----------

    /** Slug a single path segment: lowercase, spaces and parens to `-`, collapse `-`, trim. */
    static String slugifySegment(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\s()]+", "-");
        s = s.replaceAll("-+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s;
    }

    /** Slug a `Foo Bar.adoc` filename, preserving the extension. */
    static String slugifyAdocFilename(String name) {
        if (!name.endsWith(".adoc")) {
            return name;
        }
        String base = name.substring(0, name.length() - ".adoc".length());
        return slugifySegment(base) + ".adoc";
    }

    /** Slug every `/`-separated segment of a path; preserve `.adoc` extension on the last segment if present. */
    static String slugifyPathSegments(String path) {
        if (path.isEmpty()) {
            return path;
        }
        String[] segs = path.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (s.isEmpty()) continue;
            if (s.endsWith(".adoc")) {
                segs[i] = slugifyAdocFilename(s);
            } else {
                segs[i] = slugifySegment(s);
            }
        }
        return String.join("/", segs);
    }

    // ---------- rewriters ----------

    static String rewriteXrefs(String content) {
        Matcher m = XREF.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            String prefix = "";
            String body = inner;
            Matcher pre = MODULE_PREFIX.matcher(body);
            if (pre.find()) {
                prefix = pre.group();
                body = body.substring(prefix.length());
            }
            String anchor = "";
            int hash = body.indexOf('#');
            if (hash >= 0) {
                anchor = body.substring(hash);
                body = body.substring(0, hash);
            }
            String newBody = slugifyPathSegments(body);
            String replacement = "xref:" + prefix + newBody + anchor + "[";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String rewriteTocYaml(String content) {
        Matcher m = TOC_FILE.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String prefix = m.group(1);
            String value = m.group(2);
            // Strip optional surrounding quotes; keep the same quote style on output.
            String quote = "";
            String inner = value;
            if (inner.length() >= 2
                    && ((inner.startsWith("\"") && inner.endsWith("\""))
                    || (inner.startsWith("'") && inner.endsWith("'")))) {
                quote = inner.substring(0, 1);
                inner = inner.substring(1, inner.length() - 1);
            }
            String slugged = slugifyPathSegments(inner);
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + quote + slugged + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------- filesystem helpers ----------

    /** Two-step rename for case-only changes (macOS/Windows are case-insensitive by default). */
    static void safeRename(Path from, Path to) throws IOException {
        if (from.equals(to)) {
            return;
        }
        boolean caseOnly = !from.equals(to)
                && from.getParent().equals(to.getParent())
                && from.getFileName().toString().equalsIgnoreCase(to.getFileName().toString())
                && !from.getFileName().toString().equals(to.getFileName().toString());
        if (caseOnly) {
            Path tmp = from.resolveSibling(from.getFileName().toString() + ".__renametmp__");
            Files.move(from, tmp);
            Files.move(tmp, to);
        } else {
            Files.move(from, to);
        }
    }

    static List<Path> collect(Path root, java.util.function.Predicate<Path> filter) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(filter).forEach(out::add);
        }
        return out;
    }
}
