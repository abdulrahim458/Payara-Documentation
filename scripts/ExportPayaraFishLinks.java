///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Scans all .adoc files for links to payara.fish domains and exports
//DESCRIPTION unique URLs to a CSV file with an empty second column for replacement URLs.
//DESCRIPTION
//DESCRIPTION Output CSV format:
//DESCRIPTION   original_url,replacement_url
//DESCRIPTION
//DESCRIPTION URLs are sorted and deduplicated. The replacement_url column is left
//DESCRIPTION empty for manual filling.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang ExportPayaraFishLinks.java                          # scans default dirs, writes payara-fish-links.csv
//DESCRIPTION   jbang ExportPayaraFishLinks.java --out=my-links.csv       # custom output file
//DESCRIPTION   jbang ExportPayaraFishLinks.java [content-dir ...]        # custom content dirs
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ExportPayaraFishLinks {

    // Matches any http(s) URL containing payara.fish
    // Stops at whitespace, [, ], ", ', `, <, >, ,
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s\\[\\]\"'`<>,;)]+payara\\.fish[^\\s\\[\\]\"'`<>,;)]*");

    public static void main(String[] args) throws IOException {
        String outFile = "payara-fish-links.csv";
        List<Path> contentDirs = new ArrayList<>();

        for (String arg : args) {
            if (arg.startsWith("--out=")) outFile = arg.substring("--out=".length());
            else if (!arg.startsWith("--")) contentDirs.add(Paths.get(arg));
        }
        if (contentDirs.isEmpty()) {
            contentDirs.add(Paths.get("content_enterprise"));
            contentDirs.add(Paths.get("content_community"));
            contentDirs.add(Paths.get("content_shared"));
        }

        TreeSet<String> urls = new TreeSet<>();

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }
            System.out.println("Scanning: " + content);

            for (Path p : collect(content)) {
                String text;
                try {
                    text = Files.readString(p, StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException e) {
                    text = Files.readString(p, java.nio.charset.Charset.forName("ISO-8859-1"));
                }
                Matcher m = URL.matcher(text);
                while (m.find()) {
                    urls.add(m.group());
                }
            }
        }

        // Write CSV
        Path out = Paths.get(outFile);
        StringBuilder csv = new StringBuilder("original_url,replacement_url\n");
        for (String url : urls) {
            csv.append(csvEscape(url)).append(",\n");
        }
        Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);

        System.out.println("\nFound " + urls.size() + " unique payara.fish URLs.");
        System.out.println("Written to: " + out.toAbsolutePath());
        System.out.println("Fill in the replacement_url column, then run ReplacePayaraFishLinks.java.");
    }

    static String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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
