///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Converts "bold term + indented description" blocks to AsciiDoc bullet list items.
//DESCRIPTION
//DESCRIPTION Input pattern (renders poorly):
//DESCRIPTION   *Term*:
//DESCRIPTION     The description text, possibly spanning
//DESCRIPTION     multiple indented lines.
//DESCRIPTION
//DESCRIPTION Output:
//DESCRIPTION   * *Term*: The description text, possibly spanning multiple indented lines.
//DESCRIPTION
//DESCRIPTION Rules:
//DESCRIPTION   - A trigger line contains only a bold term followed by a colon: `*...*:`
//DESCRIPTION     optionally preceded by whitespace.
//DESCRIPTION   - The description is formed by joining all immediately following lines that
//DESCRIPTION     start with at least one space (indented), trimmed and space-joined.
//DESCRIPTION   - If no indented lines follow, the term line is converted to a bullet on its own.
//DESCRIPTION   - Lines inside AsciiDoc literal/source/comment blocks are skipped.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixBoldTermLists.java [content-dir ...]           # dry run
//DESCRIPTION   jbang FixBoldTermLists.java [content-dir ...] --apply   # write fixes
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts orphaned bold-term lines ({@code *Term*:}) followed by indented
 * description text into proper AsciiDoc bullet list items.
 */
public class FixBoldTermLists {

    // Matches a line that is ONLY a bold term + colon, e.g. `*JVM*:` or `  *Some Term*:`
    // Allows optional leading whitespace, requires the entire non-whitespace content to be *...*:
    private static final Pattern BOLD_TERM = Pattern.compile(
            "^(\\s*)\\*((?:[^*])+)\\*:\\s*$");

    // 4+ delimiter chars used for AsciiDoc blocks (----  ====  ....  etc.)
    private static final Pattern BLOCK_DELIM = Pattern.compile(
            "^([\\-\\.=\\*_/\\+]{4,})\\s*$");

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

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            System.out.println("content dir : " + content);
            System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println();

            int totalFiles = 0, totalItems = 0;
            for (Path p : collect(content)) {
                String original;
                try {
                    original = Files.readString(p, StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException e) {
                    original = Files.readString(p, java.nio.charset.Charset.forName("ISO-8859-1"));
                }
                Result r = transform(original);
                if (r.count > 0) {
                    totalFiles++;
                    totalItems += r.count;
                    System.out.printf("  [%s] %s (%d item(s))%n",
                            apply ? "fixed" : "would fix", content.relativize(p), r.count);
                    if (apply) Files.writeString(p, r.text, StandardCharsets.UTF_8);
                }
            }

            System.out.printf("%nDone: %d item(s) %s in %d file(s).%n%n",
                    totalItems, apply ? "fixed" : "would be fixed", totalFiles);
            if (!apply && totalItems > 0)
                System.out.println("Re-run with --apply to write changes.\n");
        }
    }

    static Result transform(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int count = 0;
        String blockDelim = null;    // non-null when inside a delimited block
        boolean inComment = false;   // inside //// block

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String stripped = line.stripLeading();

            // Track //// comment blocks
            if (stripped.equals("////")) {
                inComment = !inComment;
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                i++;
                continue;
            }

            // Track delimited blocks (----, ====, ...., etc.)
            if (!inComment) {
                var dm = BLOCK_DELIM.matcher(stripped);
                if (dm.matches()) {
                    String delim = dm.group(1);
                    if (blockDelim == null) {
                        blockDelim = delim;
                    } else if (blockDelim.equals(delim)) {
                        blockDelim = null;
                    }
                }
            }

            // Only transform outside blocks and comments
            if (!inComment && blockDelim == null) {
                var m = BOLD_TERM.matcher(line);
                if (m.matches()) {
                    String indent = m.group(1);
                    String term = m.group(2);

                    // Collect immediately following indented lines as the description
                    List<String> descLines = new ArrayList<>();
                    int j = i + 1;
                    while (j < lines.length && lines[j].length() > 0 && Character.isWhitespace(lines[j].charAt(0))) {
                        descLines.add(lines[j].trim());
                        j++;
                    }

                    String bullet;
                    if (descLines.isEmpty()) {
                        bullet = indent + "* *" + term + "*:";
                    } else {
                        bullet = indent + "* *" + term + "*: " + String.join(" ", descLines);
                    }

                    out.append(bullet);
                    if (i < lines.length - 1) out.append('\n');
                    count++;
                    i = j; // skip consumed description lines
                    continue;
                }
            }

            out.append(line);
            if (i < lines.length - 1) out.append('\n');
            i++;
        }

        return new Result(count, count > 0 ? out.toString() : text);
    }

    record Result(int count, String text) {}

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
