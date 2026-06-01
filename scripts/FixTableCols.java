///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Finds AsciiDoc tables where the cols= attribute declares more columns
//DESCRIPTION than the table rows actually contain, and optionally fixes them.
//DESCRIPTION
//DESCRIPTION Asciidoctor tries to fill missing cells when cols > actual, which produces
//DESCRIPTION "dropping cells from incomplete row" errors and can cause memory spikes
//DESCRIPTION that kill the build process.
//DESCRIPTION
//DESCRIPTION Detection: the declared column count (from cols="...") is compared to the
//DESCRIPTION actual column count derived from the widest row found in the table body.
//DESCRIPTION Fix: the cols= value is trimmed to keep only as many width tokens as there
//DESCRIPTION are actual columns (preserving any proportional widths already specified).
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixTableCols.java [content-dir ...]           # dry run, one or more dirs
//DESCRIPTION   jbang FixTableCols.java [content-dir ...] --apply   # write fixes
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects and fixes AsciiDoc tables whose {@code cols=} attribute declares more
 * columns than the table body actually contains.
 *
 * <p>Asciidoctor fills missing cells when declared columns exceed actual cells,
 * emitting "dropping cells from incomplete row" errors. For large tables this
 * causes significant memory bloat that can kill the build process.
 *
 * <p>The fix trims the {@code cols=} token list to match the actual column count,
 * preserving any proportional widths already specified for those columns.
 */
public class FixTableCols {

    // Matches the block attribute line that contains a cols= spec, e.g.:
    //   [cols="3,2,2,3"]  or  [header, cols="1,2,3"]  or  [%header,cols="~,~,~"]
    private static final Pattern COLS_ATTR = Pattern.compile(
            "(?i)\\bcols=[\"']?([^\"'\\]]+)[\"']?");

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

        int totalProblems = 0;

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }
            System.out.println("content dir : " + content);
            System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println();

            int dirProblems = 0;
            for (Path p : collect(content)) {
                String original = Files.readString(p, StandardCharsets.UTF_8);
                Result result = process(original, content.relativize(p).toString());
                if (result.problems > 0) {
                    dirProblems += result.problems;
                    if (apply && result.fixed != null) {
                        Files.writeString(p, result.fixed, StandardCharsets.UTF_8);
                        System.out.printf("  [fixed] %s%n", content.relativize(p));
                    }
                }
            }

            System.out.printf("Problems found: %d%n%n", dirProblems);
            totalProblems += dirProblems;
        }

        if (!apply && totalProblems > 0) {
            System.out.println("Dry run complete. Re-run with --apply to fix.");
        }
        if (totalProblems > 0) {
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------

    static Result process(String text, String filename) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int problems = 0;
        boolean changed = false;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // Look for a block attribute line containing cols=
            Matcher colsMatcher = COLS_ATTR.matcher(line);
            if (colsMatcher.find() && i + 1 < lines.length && lines[i + 1].trim().equals("|===")) {
                String colsValue = colsMatcher.group(1).trim();
                int declaredCols = parseColCount(colsValue);

                // Collect table body lines (from |=== open to |=== close)
                int tableStart = i + 1;
                int tableEnd = tableStart + 1;
                while (tableEnd < lines.length && !lines[tableEnd].trim().equals("|===")) {
                    tableEnd++;
                }

                int actualCols = detectActualCols(lines, tableStart + 1, tableEnd);

                if (declaredCols > 0 && actualCols > 0 && declaredCols != actualCols) {
                    problems++;
                    String fixedCols = trimColsSpec(colsValue, actualCols);
                    System.out.printf("  [%s] cols declared=%d actual=%d  cols=\"%s\" -> cols=\"%s\"%n",
                            filename, declaredCols, actualCols, colsValue, fixedCols);

                    // Rewrite the attribute line
                    String fixedLine = line.substring(0, colsMatcher.start(1)) + fixedCols
                            + line.substring(colsMatcher.end(1));
                    out.append(fixedLine).append("\n");
                    changed = true;

                    // Emit the rest of the table unchanged
                    for (int j = tableStart; j <= tableEnd && j < lines.length; j++) {
                        out.append(lines[j]);
                        if (j < lines.length - 1) out.append("\n");
                    }
                    i = tableEnd + 1;
                    continue;
                }
            }

            out.append(line);
            if (i < lines.length - 1) out.append("\n");
            i++;
        }

        return new Result(problems, changed ? out.toString() : null);
    }

    /**
     * Count the number of columns declared in a cols= value.
     * Handles comma-separated lists ("3,2,2,3" → 4) and repeat notation ("4*" → 4, "2*~" → 2).
     */
    static int parseColCount(String colsValue) {
        // Repeat notation: e.g. "4*" or "3*~"
        if (colsValue.matches("\\d+\\*.*")) {
            return Integer.parseInt(colsValue.split("\\*")[0]);
        }
        // Comma-separated: count tokens
        String[] tokens = colsValue.split(",");
        return tokens.length;
    }

    /**
     * Find the maximum number of cells in any row within the table body.
     * Cells are counted by the number of {@code |} characters that start a cell,
     * i.e. {@code |} not preceded by a backslash. Multi-line cells (where a cell
     * content wraps to the next line without a leading {@code |}) are handled by
     * only counting lines that contain at least one cell-starting {@code |}.
     */
    static int detectActualCols(String[] lines, int from, int to) {
        int maxCols = 0;

        // Strategy: a "row" in psych terms is a group of consecutive non-blank lines
        // each starting with | (or containing |). We collect all | counts per logical row.
        // Simpler: find the line with the most cell-starting | characters.
        for (int i = from; i < to && i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            int cells = countCellsOnLine(line);
            if (cells > maxCols) maxCols = cells;
        }
        return maxCols;
    }

    /**
     * Count cell-starting {@code |} characters on a line.
     * A {@code |} starts a cell if it is not preceded by a backslash.
     * Column-span prefixes like {@code 2+|} are counted as one cell start.
     */
    static int countCellsOnLine(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '|') {
                // Skip escaped pipe
                if (i > 0 && line.charAt(i - 1) == '\\') continue;
                count++;
            }
        }
        return count;
    }

    /**
     * Trim a cols= value to keep only {@code actualCols} tokens.
     * For repeat notation ("4*") the count is reduced directly.
     * For comma-separated specs the trailing tokens are dropped.
     */
    static String trimColsSpec(String colsValue, int actualCols) {
        if (colsValue.matches("\\d+\\*.*")) {
            // e.g. "4*~" → "2*~"
            String rest = colsValue.substring(colsValue.indexOf('*'));
            return actualCols + rest;
        }
        String[] tokens = colsValue.split(",");
        if (actualCols >= tokens.length) return colsValue; // nothing to trim
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actualCols; i++) {
            if (i > 0) sb.append(",");
            sb.append(tokens[i].trim());
        }
        return sb.toString();
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

    record Result(int problems, String fixed) {}
}
