///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Verifies link integrity after RenameContent.java has run.
//DESCRIPTION
//DESCRIPTION   * every `xref:` target in any `.adoc` file resolves to an existing file
//DESCRIPTION   * every `- file:` entry in `content/toc.yaml` resolves to an existing `.adoc`
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang VerifyContent.java [content-dir]
//DESCRIPTION
//DESCRIPTION Default content-dir is `./content`. Exit code is non-zero if anything is broken.

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

public class VerifyContent {

    private static final Pattern XREF = Pattern.compile("xref:([^\\[]+)\\[");
    private static final Pattern MODULE_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*:");
    private static final Pattern TOC_FILE = Pattern.compile("^(\\s*-?\\s*file:\\s*)(.*\\S)\\s*$", Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        Path contentDir = Paths.get(args.length > 0 ? args[0] : "content").toAbsolutePath().normalize();
        if (!Files.isDirectory(contentDir)) {
            System.err.println("Not a directory: " + contentDir);
            System.exit(2);
        }

        List<String> problems = new ArrayList<>();

        // --- xref check ---
        int xrefTotal = 0;
        try (Stream<Path> s = Files.walk(contentDir)) {
            List<Path> adocs = new ArrayList<>();
            s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".adoc")).forEach(adocs::add);
            for (Path p : adocs) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = XREF.matcher(text);
                while (m.find()) {
                    xrefTotal++;
                    String body = m.group(1);
                    Matcher pre = MODULE_PREFIX.matcher(body);
                    if (pre.find()) {
                        body = body.substring(pre.group().length());
                    }
                    int hash = body.indexOf('#');
                    if (hash >= 0) {
                        body = body.substring(0, hash);
                    }
                    if (body.isEmpty()) continue;            // pure anchor xref
                    if (!body.endsWith(".adoc")) continue;   // skip non-file xrefs
                    Path target = contentDir.resolve(body).normalize();
                    if (!Files.isRegularFile(target)) {
                        problems.add("  [xref ] " + contentDir.relativize(p) + "  ->  " + body);
                    }
                }
            }
        }

        // --- toc.yaml check ---
        int tocTotal = 0;
        Path toc = contentDir.resolve("toc.yaml");
        if (Files.isRegularFile(toc)) {
            String text = Files.readString(toc, StandardCharsets.UTF_8);
            Matcher m = TOC_FILE.matcher(text);
            while (m.find()) {
                tocTotal++;
                String value = m.group(2).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                Path target = contentDir.resolve(value + ".adoc").normalize();
                if (!Files.isRegularFile(target)) {
                    problems.add("  [toc  ] " + value + "  -> " + contentDir.relativize(target));
                }
            }
        }

        System.out.println("xref entries checked    : " + xrefTotal);
        System.out.println("toc.yaml entries checked: " + tocTotal);
        System.out.println("problems                : " + problems.size());
        if (!problems.isEmpty()) {
            System.out.println();
            for (String pr : problems) System.out.println(pr);
            System.exit(1);
        }
    }
}
