///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Adds `^` to external AsciiDoc links to open in new tab.
//DESCRIPTION
//DESCRIPTION Converts:   https://example.com[Link Text]
//DESCRIPTION To:         https://example.com[Link Text^]
//DESCRIPTION
//DESCRIPTION Skips content inside code/comment/literal blocks (delimited by 4+ repeated chars).
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixExternalLinks.java [content-dir]            # dry run, prints plan
//DESCRIPTION   jbang FixExternalLinks.java [content-dir] --apply   # actually performs the changes
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

public class FixExternalLinks {

    // Matches external URLs in AsciiDoc link format without the ^ caret
    // Captures: (https://...)([link text])
    // The pattern captures the URL and link text, but excludes those already ending in ^]
    private static final Pattern EXTERNAL_LINK = Pattern.compile(
            "(https?://[^\\s\\[]+)\\[([^\\[\\]]+)\\](?!\\^)");

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

        int totalFiles = 0, totalLinks = 0;
        List<Path> adocs = collect(content);
        for (Path p : adocs) {
            String original = Files.readString(p, StandardCharsets.UTF_8);
            Result r = transform(original);
            if (r.count > 0) {
                totalFiles++;
                totalLinks += r.count;
                if (apply) {
                    Files.writeString(p, r.text, StandardCharsets.UTF_8);
                    System.out.printf("  %3d  %s%n", r.count, content.relativize(p));
                } else {
                    System.out.printf("  %3d  %s%n", r.count, content.relativize(p));
                }
            }
        }

        System.out.printf("%nDone: %d external links %s in %d files.%n",
                totalLinks, apply ? "updated" : "would update", totalFiles);
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

            // Process external links: add ^ before closing ]
            String processed = line;
            Matcher m = EXTERNAL_LINK.matcher(processed);
            StringBuffer lineBuffer = new StringBuffer();
            int lineCount = 0;

            while (m.find()) {
                String url = m.group(1);
                String linkText = m.group(2);
                String replacement = url + "[" + linkText + "^]";
                m.appendReplacement(lineBuffer, Matcher.quoteReplacement(replacement));
                lineCount++;
            }
            m.appendTail(lineBuffer);
            processed = lineBuffer.toString();
            count += lineCount;

            sb.append(processed);
            if (i < lines.length - 1) sb.append('\n');
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


