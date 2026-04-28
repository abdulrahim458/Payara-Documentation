///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Converts AsciiDoc description list (dlist) entries to bold-term paragraphs.
//DESCRIPTION
//DESCRIPTION   term:: description   →   *term*: description
//DESCRIPTION   term::               →   *term*:
//DESCRIPTION
//DESCRIPTION Skips content inside code/comment/literal blocks (delimited by 4+ repeated chars).
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixDlist.java [content-dir]            # dry run, prints plan
//DESCRIPTION   jbang FixDlist.java [content-dir] --apply   # actually performs the changes
//DESCRIPTION
//DESCRIPTION Default content-dir is `./content`.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FixDlist {

    // Matches an AsciiDoc dlist line:
    //   optional indent | term (no bare `:`) | `::` not preceded by `/` or `:` | optional description
    // The negative lookbehind prevents matching `http://` or `:::`.
    private static final Pattern DLIST = Pattern.compile(
            "^(\\s*)((?:`[^`]+`|[^:])+?)(?<![/:])(::)\\s*(.*)$");

    // 4+ of the same delimiter char used for AsciiDoc blocks
    private static final Pattern BLOCK_DELIM = Pattern.compile(
            "^([\\-\\.=\\*_/\\+]{4,})\\s*$");

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

        int totalFiles = 0, totalEntries = 0;
        List<Path> adocs = collect(content);
        for (Path p : adocs) {
            String original = Files.readString(p, StandardCharsets.UTF_8);
            Result r = transform(original);
            if (r.count > 0) {
                totalFiles++;
                totalEntries += r.count;
                if (apply) {
                    Files.writeString(p, r.text, StandardCharsets.UTF_8);
                    System.out.printf("  %3d  %s%n", r.count, content.relativize(p));
                } else {
                    System.out.printf("  %3d  %s%n", r.count, content.relativize(p));
                }
            }
        }

        System.out.printf("%nDone: %d dlist entries %s in %d files.%n",
                totalEntries, apply ? "converted" : "would convert", totalFiles);
        if (!apply) System.out.println("Re-run with --apply to execute the changes.");
    }

    static Result transform(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String blockDelim = null;
        int count = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripTrailing();

            // Track block delimiters (listing, literal, comment, passthrough, etc.)
            Matcher bd = BLOCK_DELIM.matcher(stripped);
            if (bd.matches()) {
                String delim = bd.group(1);
                if (blockDelim == null) {
                    blockDelim = delim;
                } else if (delim.equals(blockDelim)) {
                    blockDelim = null;
                }
                sb.append(line);
                if (i < lines.length - 1) sb.append('\n');
                continue;
            }

            // Inside a block — pass through unchanged
            if (blockDelim != null) {
                sb.append(line);
                if (i < lines.length - 1) sb.append('\n');
                continue;
            }

            // Skip single-line AsciiDoc comments
            if (stripped.startsWith("//") && !stripped.startsWith("///")) {
                sb.append(line);
                if (i < lines.length - 1) sb.append('\n');
                continue;
            }

            Matcher m = DLIST.matcher(stripped);
            if (m.matches()) {
                String indent = m.group(1);
                String term = m.group(2).strip();
                String desc = m.group(4).strip();
                String newLine = desc.isEmpty()
                        ? indent + "*" + term + "*:"
                        : indent + "*" + term + "*: " + desc;
                sb.append(newLine);
                if (i < lines.length - 1) sb.append('\n');
                count++;
            } else {
                sb.append(line);
                if (i < lines.length - 1) sb.append('\n');
            }
        }

        return new Result(sb.toString(), count);
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

    record Result(String text, int count) {}
}
