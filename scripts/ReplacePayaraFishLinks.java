///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Replaces payara.fish URLs in all .adoc files using a CSV mapping file.
//DESCRIPTION
//DESCRIPTION The CSV must have two columns: original_url,replacement_url
//DESCRIPTION Rows with an empty replacement_url are skipped.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang ReplacePayaraFishLinks.java                          # dry run, default CSV and dirs
//DESCRIPTION   jbang ReplacePayaraFishLinks.java --apply                  # write changes
//DESCRIPTION   jbang ReplacePayaraFishLinks.java --csv=my-links.csv       # custom CSV file
//DESCRIPTION   jbang ReplacePayaraFishLinks.java [content-dir ...]        # custom content dirs
//DESCRIPTION
//DESCRIPTION If no content-dir is given, runs on content_enterprise, content_community,
//DESCRIPTION and content_shared.
//DESCRIPTION If no --csv is given, reads payara-fish-links.csv.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ReplacePayaraFishLinks {

    public static void main(String[] args) throws IOException {
        boolean apply = false;
        String csvFile = "payara-fish-links.csv";
        List<Path> contentDirs = new ArrayList<>();

        for (String arg : args) {
            if ("--apply".equals(arg)) apply = true;
            else if (arg.startsWith("--csv=")) csvFile = arg.substring("--csv=".length());
            else if (!arg.startsWith("--")) contentDirs.add(Paths.get(arg));
        }
        if (contentDirs.isEmpty()) {
            contentDirs.add(Paths.get("content_enterprise"));
            contentDirs.add(Paths.get("content_community"));
            contentDirs.add(Paths.get("content_shared"));
        }

        // Load CSV
        Path csv = Paths.get(csvFile);
        if (!Files.isRegularFile(csv)) {
            System.err.println("CSV not found: " + csv.toAbsolutePath());
            System.exit(1);
        }
        Map<String, String> replacements = loadCsv(csv);
        long mapped = replacements.values().stream().filter(v -> !v.isEmpty()).count();
        System.out.println("Loaded " + replacements.size() + " entries from " + csvFile
                + " (" + mapped + " with replacement URLs, " + (replacements.size() - mapped) + " skipped).");
        System.out.println("mode: " + (apply ? "APPLY" : "DRY RUN"));
        System.out.println();

        if (mapped == 0) {
            System.out.println("No replacement URLs filled in — nothing to do.");
            return;
        }

        int totalFiles = 0, totalReplacements = 0;

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            for (Path p : collect(content)) {
                String original;
                try {
                    original = Files.readString(p, StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException e) {
                    original = Files.readString(p, java.nio.charset.Charset.forName("ISO-8859-1"));
                }

                String rewritten = original;
                int fileReplacements = 0;
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    String from = entry.getKey();
                    String to = entry.getValue();
                    if (to.isEmpty()) continue;
                    if (rewritten.contains(from)) {
                        rewritten = rewritten.replace(from, to);
                        fileReplacements++;
                    }
                }

                if (fileReplacements > 0) {
                    totalFiles++;
                    totalReplacements += fileReplacements;
                    System.out.printf("  [%s] %s (%d replacement(s))%n",
                            apply ? "fixed" : "would fix",
                            content.relativize(p), fileReplacements);
                    if (apply) Files.writeString(p, rewritten, StandardCharsets.UTF_8);
                }
            }
        }

        System.out.printf("%nDone: %d replacement(s) in %d file(s) %s.%n",
                totalReplacements, totalFiles, apply ? "applied" : "(dry run — use --apply to write)");
    }

    /** Parse a two-column CSV (original_url,replacement_url), skipping the header row. */
    static Map<String, String> loadCsv(Path csv) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        boolean first = true;
        for (String line : lines) {
            if (first) { first = false; continue; } // skip header
            if (line.isBlank()) continue;
            String[] parts = splitCsvLine(line);
            if (parts.length < 1 || parts[0].isBlank()) continue;
            String from = parts[0].trim();
            String to = parts.length > 1 ? parts[1].trim() : "";
            map.put(from, to);
        }
        return map;
    }

    /** Split a single CSV line respecting double-quoted fields. */
    static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++; // escaped quote
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') { inQuotes = true; }
                else if (c == ',') { fields.add(sb.toString()); sb.setLength(0); }
                else { sb.append(c); }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
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
}
