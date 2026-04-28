///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Renames every `overview.adoc` file under `content/` to `index.adoc`
//DESCRIPTION and rewrites any `xref:` references to `overview.adoc` → `index.adoc`.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang RenameOverviewToIndex.java [content-dir]            # dry run
//DESCRIPTION   jbang RenameOverviewToIndex.java [content-dir] --apply   # apply changes
//DESCRIPTION
//DESCRIPTION Default content-dir is `./content`.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class RenameOverviewToIndex {

    private static final Pattern XREF_OVERVIEW = Pattern.compile(
            "(xref:[^\\[]*/)overview(\\.adoc[^\\[]*)\\[");

    public static void main(String[] args) throws IOException {
        boolean apply = false;
        Path contentDir = Paths.get("content");
        for (String arg : args) {
            if ("--apply".equals(arg)) apply = true;
            else if (!arg.startsWith("--")) contentDir = Paths.get(arg);
        }
        final Path content = contentDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(content)) {
            System.err.println("Not a directory: " + content);
            System.exit(2);
        }

        System.out.println("content dir : " + content);
        System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
        System.out.println();

        // --- Phase 1: rewrite xrefs in all .adoc files ---
        List<Path> adocs = collect(content, p -> Files.isRegularFile(p) && p.toString().endsWith(".adoc"));
        int xrefChanged = 0;
        for (Path p : adocs) {
            String original = Files.readString(p, StandardCharsets.UTF_8);
            String rewritten = rewriteXrefs(original);
            if (!rewritten.equals(original)) {
                xrefChanged++;
                if (apply) {
                    Files.writeString(p, rewritten, StandardCharsets.UTF_8);
                    System.out.println("  xref-edit : " + content.relativize(p));
                } else {
                    System.out.println("  xref-edit : " + content.relativize(p));
                }
            }
        }
        System.out.println("Phase 1: xref rewrites -> " + xrefChanged + " files " + (apply ? "updated" : "would change"));
        System.out.println();

        // --- Phase 2: rewrite toc.yaml ---
        Path toc = content.resolve("toc.yaml");
        if (Files.isRegularFile(toc)) {
            String original = Files.readString(toc, StandardCharsets.UTF_8);
            String rewritten = original.replaceAll("(?<![\\w-])overview(?=\\.adoc|\\s*$)", "index");
            if (!rewritten.equals(original)) {
                if (apply) {
                    Files.writeString(toc, rewritten, StandardCharsets.UTF_8);
                }
                System.out.println("Phase 2: toc.yaml -> " + (apply ? "updated" : "would change"));
            } else {
                System.out.println("Phase 2: toc.yaml -> no changes needed");
            }
        } else {
            System.out.println("Phase 2: toc.yaml not found");
        }
        System.out.println();

        // --- Phase 3: rename overview.adoc files ---
        List<Path> overviews = collect(content,
                p -> Files.isRegularFile(p) && p.getFileName().toString().equals("overview.adoc"));
        int renamed = 0;
        for (Path p : overviews) {
            Path target = p.resolveSibling("index.adoc");
            renamed++;
            if (apply) {
                safeRename(p, target);
                System.out.println("  renamed   : " + content.relativize(p) + "  ->  index.adoc");
            } else {
                System.out.println("  rename    : " + content.relativize(p) + "  ->  index.adoc");
            }
        }
        System.out.println("Phase 3: file renames -> " + renamed + " overview.adoc files " + (apply ? "renamed" : "would rename"));
        System.out.println();

        if (!apply) {
            System.out.println("Dry run complete. Re-run with --apply to execute the changes.");
        } else {
            System.out.println("Done.");
        }
    }

    static String rewriteXrefs(String content) {
        Matcher m = XREF_OVERVIEW.matcher(content);
        if (!m.find()) return content;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "index" + m.group(2) + "["));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static void safeRename(Path from, Path to) throws IOException {
        if (from.equals(to)) return;
        try {
            Files.move(from, to);
        } catch (FileAlreadyExistsException e) {
            Files.delete(to);
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
