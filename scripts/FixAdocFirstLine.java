///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Normalizes AsciiDoc file starts so first line is a level-1 title.
//DESCRIPTION
//DESCRIPTION Fixes (at file start only):
//DESCRIPTION   1) remove empty first line(s)
//DESCRIPTION   2) remove first-line anchor(s) like [[...]]
//DESCRIPTION
//DESCRIPTION Then verifies the first remaining line starts with `= ` (single `=` heading).
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixAdocFirstLine.java [content-dir ...]           # dry run, one or more dirs
//DESCRIPTION   jbang FixAdocFirstLine.java [content-dir ...] --apply   # write changes
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

public class FixAdocFirstLine {

    private static final Pattern LEADING_ANCHOR = Pattern.compile("^\\s*\\[\\[[^\\]]+\\]\\]\\s*$");

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

        int totalInvalidFiles = 0;

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            System.out.println("content dir : " + content);
            System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println();

            int changedFiles = 0;
            int invalidFiles = 0;
            List<String> invalidDetails = new ArrayList<>();

            for (Path p : collect(content)) {
                String original = Files.readString(p, StandardCharsets.UTF_8);
                Result result = transform(original);

                if (result.changed) {
                    changedFiles++;
                    if (apply) {
                        Files.writeString(p, result.text, StandardCharsets.UTF_8);
                    }
                    System.out.printf("  [fix] %s%n", content.relativize(p));
                }

                if (!result.valid) {
                    invalidFiles++;
                    String first = result.firstLine == null ? "<empty>" : result.firstLine;
                    invalidDetails.add(String.format("  [bad] %s  first line: %s", content.relativize(p), first));
                }
            }

            System.out.printf("%nDone: %d files %s.%n",
                    changedFiles,
                    apply ? "updated" : "would be updated");

            if (invalidFiles > 0) {
                totalInvalidFiles += invalidFiles;
                System.out.println("Invalid first line after normalization: " + invalidFiles);
                for (String detail : invalidDetails) {
                    System.out.println(detail);
                }
            } else {
                System.out.println("All checked .adoc files start with a level-1 title (= ...).");
            }
            if (!apply) {
                System.out.println("Re-run with --apply to execute the changes.");
            }
            System.out.println();
        }

        if (totalInvalidFiles > 0) {
            System.exit(1);
        }
    }

    static Result transform(String text) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        boolean hadTrailingEol = text.endsWith("\n") || text.endsWith("\r\n");

        String[] lines = text.split("\\r?\\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().isEmpty() || LEADING_ANCHOR.matcher(line).matches()) {
                i++;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int idx = i; idx < lines.length; idx++) {
            sb.append(lines[idx]);
            if (idx < lines.length - 1) sb.append(eol);
        }
        String normalized = sb.toString();

        if (hadTrailingEol && !normalized.isEmpty() && !normalized.endsWith(eol)) {
            normalized += eol;
        }

        String firstLine = null;
        if (!normalized.isEmpty()) {
            int nl = normalized.indexOf('\n');
            if (nl >= 0) {
                firstLine = normalized.substring(0, nl).replace("\r", "");
            } else {
                firstLine = normalized.replace("\r", "");
            }
        }

        boolean valid = isLevelOneTitle(firstLine);
        boolean changed = !normalized.equals(text);
        return new Result(normalized, changed, valid, firstLine);
    }

    static boolean isLevelOneTitle(String line) {
        if (line == null) return false;
        if (!line.startsWith("= ")) return false;
        return line.length() < 3 || line.charAt(2) != '=';
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

    record Result(String text, boolean changed, boolean valid, String firstLine) {}
}

