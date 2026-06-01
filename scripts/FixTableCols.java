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

    // Matches the cols= value inside a block attribute line, e.g.:
    //   [cols="3,2,2,3"]  or  [header, cols="1,2,3"]  or  [%header,cols="~,~,~"]
    // Three alternatives: double-quoted, single-quoted, or unquoted.
    private static final Pattern COLS_ATTR = Pattern.compile(
            "(?i)\\bcols=\"([^\"]+)\"|\\bcols='([^']+)'|\\bcols=(\\S+?)[,\\]]");

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
                // Extract value from whichever capture group matched (double-quoted, single-quoted, unquoted)
                String colsValue = colsMatcher.group(1) != null ? colsMatcher.group(1)
                        : colsMatcher.group(2) != null ? colsMatcher.group(2)
                        : colsMatcher.group(3);
                colsValue = colsValue.trim();
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
                    String fixedCols = fixColsSpec(colsValue, actualCols);
                    System.out.printf("  [%s] cols declared=%d actual=%d  cols=\"%s\" -> cols=\"%s\"%n",
                            filename, declaredCols, actualCols, colsValue, fixedCols);

                    // Rewrite the attribute line, replacing only the value portion
                    int valueStart = colsMatcher.group(1) != null ? colsMatcher.start(1)
                            : colsMatcher.group(2) != null ? colsMatcher.start(2)
                            : colsMatcher.start(3);
                    int valueEnd = colsMatcher.group(1) != null ? colsMatcher.end(1)
                            : colsMatcher.group(2) != null ? colsMatcher.end(2)
                            : colsMatcher.end(3);
                    String fixedLine = line.substring(0, valueStart) + fixedCols + line.substring(valueEnd);
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
     *
     * <p>AsciiDoc cols= semantics:
     * <ul>
     *   <li>{@code cols="1,3,1"} — 3 columns with proportional widths 1, 3, 1.
     *       The number of comma-separated tokens = number of columns.</li>
     *   <li>{@code cols="3*"} or {@code cols="3*~"} — repeat notation: 3 columns
     *       of equal (or specified) width. The digit before {@code *} = column count.</li>
     *   <li>{@code cols="2"} — a single bare integer means ONE column of proportional
     *       width 2. This is commonly misused as "2 columns" but is NOT correct.</li>
     * </ul>
     */
    static int parseColCount(String colsValue) {
        // Repeat notation: e.g. "4*" or "3*~"
        if (colsValue.matches("\\d+\\*.*")) {
            return Integer.parseInt(colsValue.split("\\*")[0]);
        }
        // Comma-separated width list: number of tokens = number of columns
        if (colsValue.contains(",")) {
            return colsValue.split(",").length;
        }
        // Single bare value (e.g. "2" or "~") — one column
        return 1;
    }

    /**
     * Detect the actual column count of a table body.
     *
     * <p>Two layouts are handled:
     * <ol>
     *   <li><b>Inline rows</b> — all cells of a row on one line:
     *       {@code |cell1 |cell2 |cell3}. The max cell count on any single line
     *       is the column count.</li>
     *   <li><b>One-cell-per-line rows</b> — each cell on its own line, rows
     *       separated by a blank line (or by the header separator blank line
     *       immediately after the opening {@code |===}). The column count is the
     *       number of consecutive cell lines in the first row group (header).</li>
     * </ol>
     */
    static int detectActualCols(String[] lines, int from, int to) {
        // Pass 1: check if any line has more than one cell (inline layout)
        int maxOnOneLine = 0;
        for (int i = from; i < to && i < lines.length; i++) {
            int cells = countCellsOnLine(lines[i]);
            if (cells > maxOnOneLine) maxOnOneLine = cells;
        }
        if (maxOnOneLine > 1) {
            return maxOnOneLine; // inline layout — max per line = column count
        }

        // Pass 2: one-cell-per-line layout.
        // Count consecutive cell lines in the first row group (before first blank line).
        // In a table with options="header", the header row is separated from the body
        // by a blank line; that first group is the header and its cell count = column count.
        int headerCells = 0;
        for (int i = from; i < to && i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) break; // end of first row group
            if (countCellsOnLine(lines[i]) > 0) headerCells++;
        }
        return headerCells;
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
     * Produce a corrected cols= value for {@code actualCols} columns.
     *
     * <ul>
     *   <li>Repeat notation ({@code "3*"}) — adjust the count: {@code "5*" → "3*"}.</li>
     *   <li>Comma-separated width list — trim trailing tokens if declared &gt; actual,
     *       or return unchanged if declared &lt; actual (caller handles mismatch report).</li>
     *   <li>Single bare value or anything else — the original cols= was wrong (declared 1
     *       column but table has {@code actualCols}). Generate equal-width list:
     *       {@code "2" → "1,1,1"} for actualCols=3.</li>
     * </ul>
     */
    static String fixColsSpec(String colsValue, int actualCols) {
        if (colsValue.matches("\\d+\\*.*")) {
            // Repeat notation: adjust the count, keep the width specifier
            String rest = colsValue.substring(colsValue.indexOf('*'));
            return actualCols + rest;
        }
        if (colsValue.contains(",")) {
            // Comma-separated: trim excess tokens or pad with "1" for missing columns
            String[] tokens = colsValue.split(",");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < actualCols; i++) {
                if (i > 0) sb.append(",");
                sb.append(i < tokens.length ? tokens[i].trim() : "1");
            }
            return sb.toString();
        }
        // Single value (e.g. "2", "~"): was wrong, generate equal-width list
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actualCols; i++) {
            if (i > 0) sb.append(",");
            sb.append("1");
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
