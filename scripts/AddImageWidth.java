///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Finds AsciiDoc image macros missing a `width=` attribute and adds `width=600px`.
//DESCRIPTION
//DESCRIPTION   image:foo.png[]               →  image:foo.png[alt="foo",width=600px]
//DESCRIPTION   image:foo.png[Payara Logo]    →  image:foo.png[alt="Payara Logo",width=600px]
//DESCRIPTION   image::foo.png[]              →  image::foo.png[alt="foo",width=600px]
//DESCRIPTION   image:foo.png[alt="x"]        →  image:foo.png[alt="x",width=600px]
//DESCRIPTION
//DESCRIPTION Skips images that already have a `width` attribute or are inside code blocks.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang AddImageWidth.java [content-dir]            # dry run
//DESCRIPTION   jbang AddImageWidth.java [content-dir] --apply   # apply changes
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

public class AddImageWidth {

    // Matches inline (image:) and block (image::) image macros.
    // Group 1: macro prefix (image: or image::)
    // Group 2: image path/target
    // Group 3: attribute list content (may be empty)
    private static final Pattern IMAGE = Pattern.compile(
            "(image::?)([^\\[\\s]+)\\[([^\\]]*)\\]");

    // Block delimiters: 4+ of the same char
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

        List<Path> adocs = collect(content);
        int totalFiles = 0, totalImages = 0;

        for (Path p : adocs) {
            String original = Files.readString(p, StandardCharsets.UTF_8);
            Result r = transform(original);
            if (r.count > 0) {
                totalFiles++;
                totalImages += r.count;
                if (apply) {
                    Files.writeString(p, r.text, StandardCharsets.UTF_8);
                }
                System.out.printf("  %3d  %s%n", r.count, content.relativize(p));
            }
        }

        System.out.printf("%nDone: %d images %s in %d files.%n",
                totalImages, apply ? "updated" : "would update", totalFiles);
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

            // Track block delimiters to skip code/literal blocks
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

            if (blockDelim != null) {
                sb.append(line);
                if (i < lines.length - 1) sb.append('\n');
                continue;
            }

            // Process image macros on this line
            Matcher m = IMAGE.matcher(line);
            StringBuffer lineOut = new StringBuffer();
            while (m.find()) {
                String prefix = m.group(1);
                String path   = m.group(2);
                String attrs  = m.group(3);

                boolean hasWidth    = attrs.contains("width");
                boolean hasNamedAlt = attrs.contains("alt=");

                // Derive alt text from filename (strip directory and extension)
                String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                String altName = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;

                // Detect positional alt: first comma-separated token with no `=`
                String positionalAlt = null;
                String rest = attrs;
                if (!hasNamedAlt && !attrs.isEmpty()) {
                    int comma = attrs.indexOf(',');
                    String first = comma >= 0 ? attrs.substring(0, comma).trim() : attrs.trim();
                    if (!first.contains("=")) {
                        positionalAlt = first;
                        rest = comma >= 0 ? attrs.substring(comma + 1).trim() : "";
                    }
                }

                boolean needsAlt   = !hasNamedAlt && (positionalAlt != null || attrs.isEmpty());
                boolean needsWidth = !hasWidth;

                // Nothing to do
                if (!needsAlt && !needsWidth) {
                    m.appendReplacement(lineOut, Matcher.quoteReplacement(m.group(0)));
                    continue;
                }

                // Build new attribute string
                String newAttrs;
                if (needsAlt) {
                    String altValue = positionalAlt != null ? positionalAlt : altName;
                    StringBuilder nb = new StringBuilder("alt=\"").append(altValue).append("\"");
                    if (!rest.isEmpty()) nb.append(",").append(rest);
                    if (needsWidth)     nb.append(",width=600px");
                    newAttrs = nb.toString();
                } else {
                    // alt already named — just append width
                    newAttrs = attrs + ",width=600px";
                }
                String replacement = prefix + path + "[" + newAttrs + "]";
                m.appendReplacement(lineOut, Matcher.quoteReplacement(replacement));
                count++;
            }
            m.appendTail(lineOut);

            sb.append(lineOut);
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
