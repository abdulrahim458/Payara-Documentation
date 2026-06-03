///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Checks and fixes AsciiDoc section header lines (lines starting with `=`):
//DESCRIPTION
//DESCRIPTION   1. Removes a trailing colon from the header text.
//DESCRIPTION   2. Converts the header text to Title Case:
//DESCRIPTION      - First word always capitalised.
//DESCRIPTION      - Common short words (articles, prepositions, conjunctions) kept lowercase
//DESCRIPTION        unless they are the first or last word.
//DESCRIPTION      - Words that are already ALL-CAPS (acronyms) are left unchanged.
//DESCRIPTION      - Words containing digits or dots (e.g. "7.0.0", "CDI") are left unchanged.
//DESCRIPTION      - Hyphenated words: each part is title-cased individually.
//DESCRIPTION
//DESCRIPTION Lines inside code / literal blocks (`----`, `....`, `++++`) are skipped.
//DESCRIPTION Lines inside `////` comment blocks are skipped.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang FixSectionHeaders.java [content-dir ...]           # dry run
//DESCRIPTION   jbang FixSectionHeaders.java [content-dir ...] --apply   # write fixes
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Normalises AsciiDoc section headers:
 * <ol>
 *   <li>Strips a trailing {@code :} from the header text.</li>
 *   <li>Converts the text to Title Case, leaving acronyms, version numbers,
 *       and short function words in their natural form.</li>
 * </ol>
 */
public class FixSectionHeaders {

    // AsciiDoc section title: one or more `=` followed by a space and the title text.
    private static final Pattern HEADER = Pattern.compile("^(={1,6})\\s+(.+)$");

    // 4+ delimiter chars used for AsciiDoc delimited blocks
    private static final Pattern BLOCK_DELIM = Pattern.compile(
            "^(-{4,}|\\.{4,}|\\+{4,}|={4,}|\\*{4,}|/{4,}|_{4,})\\s*$");

    // Words kept lowercase unless first or last (standard English title-case rules)
    private static final Set<String> LOWERCASE_WORDS = Set.of(
            "a", "an", "the",
            "and", "but", "or", "nor", "for", "yet", "so",
            "as", "at", "by", "in", "of", "on", "to", "up",
            "via", "per", "vs", "v",
            "from", "into", "like", "near", "over", "past",
            "than", "that", "then", "till", "upon", "with",
            "without", "within", "between", "among", "along",
            "about", "above", "after", "before", "below", "down",
            "off", "out", "through", "under", "until", "onto"
    );

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

            int totalFiles = 0, totalHeaders = 0;
            for (Path p : collect(content)) {
                String original;
                try {
                    original = Files.readString(p, StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException e) {
                    original = Files.readString(p, java.nio.charset.Charset.forName("ISO-8859-1"));
                }

                Result r = fix(original);
                if (r.count > 0) {
                    totalFiles++;
                    totalHeaders += r.count;
                    System.out.printf("  [%s] %s (%d header(s))%n",
                            apply ? "fixed" : "would fix", content.relativize(p), r.count);
                    for (String diff : r.diffs) System.out.println("    " + diff);
                    if (apply) Files.writeString(p, r.text, StandardCharsets.UTF_8);
                }
            }
            System.out.printf("%nDone: %d header(s) %s in %d file(s).%n%n",
                    totalHeaders, apply ? "fixed" : "would be fixed", totalFiles);
            if (!apply && totalHeaders > 0)
                System.out.println("Re-run with --apply to write changes.\n");
        }
    }

    static Result fix(String text) {
        String[] lines = text.split("\n", -1);
        List<String> diffs = new ArrayList<>();
        boolean changed = false;
        String blockDelim = null;
        boolean inComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();

            // Track //// comment blocks
            if (stripped.equals("////")) {
                inComment = !inComment;
                continue;
            }
            if (inComment) continue;

            // Track delimited blocks
            Matcher dm = BLOCK_DELIM.matcher(stripped);
            if (dm.matches()) {
                String delim = dm.group(1);
                if (blockDelim == null) blockDelim = delim;
                else if (blockDelim.startsWith(delim.substring(0, 1))) blockDelim = null;
                continue;
            }
            if (blockDelim != null) continue;

            // Match section headers
            Matcher m = HEADER.matcher(line);
            if (!m.matches()) continue;

            String prefix = m.group(1); // e.g. "=="
            String title  = m.group(2); // e.g. "some title:"

            String fixed = fixTitle(title);
            if (!fixed.equals(title)) {
                String newLine = prefix + " " + fixed;
                diffs.add("- " + line);
                diffs.add("+ " + newLine);
                lines[i] = newLine;
                changed = true;
            }
        }

        return new Result(diffs.size() / 2, changed ? String.join("\n", lines) : text, diffs);
    }

    /**
     * Apply both fixes to a header title string:
     * 1. Strip trailing colon.
     * 2. Title-case the words.
     */
    static String fixTitle(String title) {
        // 1. Strip trailing colon (and any trailing whitespace before it)
        String t = title.stripTrailing();
        if (t.endsWith(":")) {
            t = t.substring(0, t.length() - 1).stripTrailing();
        }

        // 2. Title-case
        t = titleCase(t);

        return t;
    }

    /**
     * Convert a string to Title Case.
     *
     * <p>Tokens are split on whitespace. Each token is title-cased unless it is:
     * <ul>
     *   <li>A short function word in the middle of the title.</li>
     *   <li>Already all-uppercase (acronym, e.g. "JVM", "HTTP").</li>
     *   <li>Contains a digit or a dot (version number, e.g. "7.0.0").</li>
     *   <li>A back-tick-quoted inline literal — left completely unchanged.</li>
     * </ul>
     */
    static String titleCase(String title) {
        // Split preserving the separating whitespace
        String[] tokens = title.split("(?<=\\S)(?=\\s)|(?<=\\s)(?=\\S)", -1);
        StringBuilder sb = new StringBuilder();
        int wordIndex = 0;  // index among non-whitespace tokens
        int totalWords = countWords(tokens);

        for (String token : tokens) {
            if (token.isBlank()) {
                sb.append(token);
                continue;
            }
            boolean isFirst = wordIndex == 0;
            boolean isLast  = wordIndex == totalWords - 1;
            sb.append(titleCaseToken(token, isFirst, isLast));
            wordIndex++;
        }
        return sb.toString();
    }

    static String titleCaseToken(String token, boolean isFirst, boolean isLast) {
        // Back-tick literals — leave untouched
        if (token.startsWith("`") && token.endsWith("`")) return token;

        // Inline attribute reference {attr} — leave untouched
        if (token.startsWith("{") && token.endsWith("}")) return token;

        // Hyphenated compound word — title-case each part
        if (token.contains("-") && !token.startsWith("-") && !token.endsWith("-")) {
            String[] parts = token.split("-", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append("-");
                // Each part of a hyphenated word is capitalised (APA / Chicago style)
                sb.append(titleCaseWord(parts[i], true, true));
            }
            return sb.toString();
        }

        // Strip trailing punctuation for lookup (e.g. "APIs," → "apis")
        String word = token.replaceAll("[^a-zA-Z0-9_{}\\-`]", "");

        return titleCaseWord(token, isFirst, isLast);
    }

    static String titleCaseWord(String word, boolean isFirst, boolean isLast) {
        if (word.isEmpty()) return word;

        // Contains digit or dot → probably a version number or mixed token; leave as-is
        if (word.matches(".*[\\d.].*")) return word;

        // All uppercase (and ≥2 chars) → acronym; leave as-is
        String alpha = word.replaceAll("[^a-zA-Z]", "");
        if (alpha.length() >= 2 && alpha.equals(alpha.toUpperCase(Locale.ROOT))) return word;

        // Lowercase lookup key (strip punctuation for matching)
        String key = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");

        // Function word in the middle → keep lowercase
        if (!isFirst && !isLast && LOWERCASE_WORDS.contains(key)) {
            return word.toLowerCase(Locale.ROOT);
        }

        // Capitalise first letter, lowercase the rest (preserving non-alpha chars)
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return word.substring(0, i)
                        + Character.toUpperCase(word.charAt(i))
                        + word.substring(i + 1).toLowerCase(Locale.ROOT);
            }
        }
        return word;
    }

    static int countWords(String[] tokens) {
        int count = 0;
        for (String t : tokens) if (!t.isBlank()) count++;
        return count;
    }

    record Result(int count, String text, List<String> diffs) {}

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
