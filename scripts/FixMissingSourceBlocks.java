///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Finds `----` code blocks that are missing a `[source,...]` attribute and adds one.
//DESCRIPTION
//DESCRIPTION A block like:
//DESCRIPTION
//DESCRIPTION   ----
//DESCRIPTION   some content
//DESCRIPTION   ----
//DESCRIPTION
//DESCRIPTION becomes:
//DESCRIPTION
//DESCRIPTION   [source,text]
//DESCRIPTION   ----
//DESCRIPTION   some content
//DESCRIPTION   ----
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FindMissingSourceBlocks.java [content-dir ...]                       # dry run
//DESCRIPTION   jbang FindMissingSourceBlocks.java [content-dir ...] --apply              # apply, default lang=text
//DESCRIPTION   jbang FindMissingSourceBlocks.java [content-dir ...] --apply --lang=shell
//DESCRIPTION
//DESCRIPTION If no content-dir is given, runs on content_enterprise, content_community,
//DESCRIPTION and content_shared.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class FixMissingSourceBlocks {

    // A listing delimiter is exactly 4+ dashes on a line by itself
    private static final java.util.regex.Pattern LISTING_DELIM =
            java.util.regex.Pattern.compile("^-{4,}\\s*$");

    // Any block attribute line: [source,...], [listing], [literal], [NOTE], etc.
    private static final java.util.regex.Pattern BLOCK_ATTR =
            java.util.regex.Pattern.compile("^\\[.*\\]\\s*$");

    public static void main(String[] args) throws IOException {
        boolean apply = false;
        String lang = "text";
        List<Path> contentDirs = new ArrayList<>();

        for (String arg : args) {
            if ("--apply".equals(arg)) {
                apply = true;
            } else if (arg.startsWith("--lang=")) {
                lang = arg.substring("--lang=".length());
            } else if (!arg.startsWith("--")) {
                contentDirs.add(Paths.get(arg));
            }
        }
        if (contentDirs.isEmpty()) {
            contentDirs.add(Paths.get("content_enterprise"));
            contentDirs.add(Paths.get("content_community"));
            contentDirs.add(Paths.get("content_shared"));
        }

        for (Path contentDir : contentDirs) {
            final Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            System.out.println("content dir : " + content);
            System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println("language    : " + lang);
            System.out.println();

            List<Path> adocs = collect(content);
            int totalFiles = 0, totalBlocks = 0;

            for (Path p : adocs) {
                String original = Files.readString(p, StandardCharsets.UTF_8);
                Result r = transform(original, lang);
                if (r.count > 0) {
                    totalFiles++;
                    totalBlocks += r.count;
                    if (apply) {
                        Files.writeString(p, r.text, StandardCharsets.UTF_8);
                    }
                    System.out.printf("  %3d  %s%n", r.count, content.relativize(p));
                    for (String loc : r.locations) {
                        System.out.println("       " + loc);
                    }
                }
            }

            System.out.printf("%nDone: %d blocks without [source] %s in %d files.%n",
                    totalBlocks, apply ? "fixed" : "found", totalFiles);
            if (!apply) System.out.println("Re-run with --apply to add [source," + lang + "] before each block.");
        }
    }

    static Result transform(String text, String lang) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length + 10);
        int count = 0;
        List<String> locations = new ArrayList<>();

        // Track whether we're inside a listing block so nested delimiters don't confuse us.
        // We only care about the outermost `----` pairs.
        boolean insideListing = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripTrailing();

            if (LISTING_DELIM.matcher(stripped).matches()) {
                if (!insideListing) {
                    // Opening delimiter — check the previous non-empty line for a block attribute
                    boolean hasAttr = false;
                    for (int j = out.size() - 1; j >= 0; j--) {
                        String prev = out.get(j).stripTrailing();
                        if (prev.isEmpty()) continue;
                        if (BLOCK_ATTR.matcher(prev).matches()) {
                            hasAttr = true;
                        }
                        break; // only inspect immediately preceding non-empty line
                    }
                    if (!hasAttr) {
                        count++;
                        locations.add("line " + (i + 1) + ": " + stripped);
                        out.add("[source," + lang + "]");
                        out.add(line);
                        insideListing = true;
                        continue;
                    }
                    insideListing = true;
                } else {
                    // Closing delimiter
                    insideListing = false;
                }
            }

            out.add(line);
        }

        String result = String.join("\n", out);
        return new Result(result, count, locations);
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

    record Result(String text, int count, List<String> locations) {}
}
