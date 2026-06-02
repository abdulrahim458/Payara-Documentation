///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Replaces `# empty section: include*: partial$xxx.adoc[Title]` comments
//DESCRIPTION in toc.yaml with the navigation entries derived from the corresponding
//DESCRIPTION partial file in `partials/xxx.adoc`.
//DESCRIPTION
//DESCRIPTION ConvertNavToToc emits these comments when it encounters an
//DESCRIPTION `include::partial$xxx.adoc[]` directive in nav.adoc that it cannot
//DESCRIPTION resolve inline. This script finishes that job.
//DESCRIPTION
//DESCRIPTION Conversion rules (nav.adoc -> toc.yaml):
//DESCRIPTION   .Title          -> top-level section title (from comment, not re-parsed)
//DESCRIPTION   * xref:p[L]    -> - file: p  (with title if label differs from slug)
//DESCRIPTION   * Plain text   -> - title: "Plain text" (section header, no file)
//DESCRIPTION   ** xref:p[L]   -> nested file entry
//DESCRIPTION   ** link:u[L]   -> - title: L / url: u
//DESCRIPTION   ** Plain text  -> nested section header
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang ExpandTocPartials.java [content-dir ...]           # dry run
//DESCRIPTION   jbang ExpandTocPartials.java [content-dir ...] --apply   # write fixes
//DESCRIPTION
//DESCRIPTION If no content-dir is given, runs on content_enterprise and content_community.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExpandTocPartials {

    // Matches: # empty section: include*: partial$foo.adoc[Title]
    private static final Pattern COMMENT = Pattern.compile(
            "^(\\s*)#\\s*empty section:\\s*include\\*?:?\\s*partial\\$([^\\[]+\\.adoc)\\[([^\\]]*)\\]\\s*$");

    private static final Pattern XREF = Pattern.compile("xref:([^\\[]+)\\[([^\\]]*)\\]");
    private static final Pattern LINK = Pattern.compile("link:([^\\[]+)\\[([^\\]]*)\\]");

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
        }

        for (Path contentDir : contentDirs) {
            Path content = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(content)) {
                System.err.println("Not a directory: " + content + " — skipping");
                continue;
            }

            Path tocPath = content.resolve("toc.yaml");
            if (!Files.isRegularFile(tocPath)) {
                System.out.println(content.getFileName() + ": no toc.yaml — skipping");
                continue;
            }

            System.out.println("content dir : " + content);
            System.out.println("mode        : " + (apply ? "APPLY" : "DRY RUN"));
            System.out.println();

            String toc = Files.readString(tocPath, StandardCharsets.UTF_8);
            String[] lines = toc.split("\n", -1);
            StringBuilder out = new StringBuilder();
            int replaced = 0;

            for (String line : lines) {
                Matcher m = COMMENT.matcher(line);
                if (!m.matches()) {
                    out.append(line).append("\n");
                    continue;
                }

                String baseIndent = m.group(1); // indentation of the comment
                String partialFile = m.group(2); // e.g. release-notes.adoc
                String title = m.group(3);       // e.g. Release Notes

                Path partial = content.resolve("partials").resolve(partialFile);
                if (!Files.isRegularFile(partial)) {
                    System.out.println("  [skip] partial not found: " + partialFile);
                    out.append(line).append("\n");
                    continue;
                }

                System.out.println("  [" + (apply ? "expand" : "would expand") + "] partial$" + partialFile);
                List<String> navLines = Files.readAllLines(partial, StandardCharsets.UTF_8);
                String expanded = convertNavToToc(navLines, title, baseIndent);
                out.append(expanded);
                replaced++;
            }

            // Remove trailing extra newline added in loop
            String result = out.toString();
            if (result.endsWith("\n\n") && !toc.endsWith("\n\n")) {
                result = result.substring(0, result.length() - 1);
            }

            if (replaced == 0) {
                System.out.println("  No comments to expand.");
            } else if (apply) {
                Files.writeString(tocPath, result, StandardCharsets.UTF_8);
                System.out.println("  " + replaced + " partial(s) expanded and written.");
            } else {
                System.out.println("  " + replaced + " partial(s) would be expanded. Re-run with --apply.");
            }
            System.out.println();
        }
    }

    /**
     * Convert nav.adoc partial content to toc.yaml entries.
     * The top-level section title comes from the comment (not re-parsed from .Title).
     */
    static String convertNavToToc(List<String> navLines, String title, String baseIndent) {
        // Parse nav lines into a two-level structure:
        // depth-1 items (* ...) and depth-2 items (** ...)
        // We skip the .Title line since we have the title from the comment.

        List<Object> items = new ArrayList<>(); // String = section header, String[] = {file, label} or {url, label, "url"}

        for (String raw : navLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(".")) continue; // skip title line

            int depth = 0;
            if (line.startsWith("** ")) { depth = 2; line = line.substring(3).trim(); }
            else if (line.startsWith("* ")) { depth = 1; line = line.substring(2).trim(); }
            else continue;

            items.add(new NavItem(depth, line));
        }

        StringBuilder sb = new StringBuilder();
        String pad1 = baseIndent;          // for "- title: / - file:" at depth 1 (same as comment)
        String pad2 = baseIndent + "  ";   // inside sections:
        String pad3 = baseIndent + "    "; // depth-2 items
        String pad4 = baseIndent + "      "; // inside depth-2 sections:

        sb.append(pad1).append("- title: ").append(yamlString(title)).append("\n");
        sb.append(pad1).append("  sections:\n");

        // Group depth-2 items under their preceding depth-1 section headers
        NavItem currentSection = null;
        List<NavItem> sectionChildren = new ArrayList<>();

        for (Object obj : items) {
            NavItem item = (NavItem) obj;
            if (item.depth == 1) {
                // Flush previous section
                if (currentSection != null) {
                    emitItem(currentSection, sectionChildren, sb, pad2, pad3, pad4);
                    sectionChildren = new ArrayList<>();
                }
                currentSection = item;
            } else if (item.depth == 2) {
                sectionChildren.add(item);
            }
        }
        if (currentSection != null) {
            emitItem(currentSection, sectionChildren, sb, pad2, pad3, pad4);
        }

        return sb.toString();
    }

    static void emitItem(NavItem item, List<NavItem> children, StringBuilder sb,
                         String pad, String childPad, String childSectionPad) {
        String line = item.line;
        Matcher xref = XREF.matcher(line);
        Matcher link = LINK.matcher(line);

        if (xref.matches()) {
            String file = stripAdoc(xref.group(1));
            String label = xref.group(2).trim();
            if (children.isEmpty()) {
                if (needsTitle(label, file)) {
                    sb.append(pad).append("- title: ").append(yamlString(label)).append("\n");
                    sb.append(pad).append("  file: ").append(file).append("\n");
                } else {
                    sb.append(pad).append("- file: ").append(file).append("\n");
                }
            } else {
                // File + sub-sections
                sb.append(pad).append("- file: ").append(file).append("\n");
                sb.append(pad).append("  sections:\n");
                for (NavItem child : children) emitLeaf(child, sb, childPad, childSectionPad);
            }
        } else if (link.matches()) {
            // External URL at depth 1 (unusual but handle it)
            sb.append(pad).append("- title: ").append(yamlString(link.group(2).trim())).append("\n");
            sb.append(pad).append("  url: ").append(link.group(1).trim()).append("\n");
        } else {
            // Plain section header
            sb.append(pad).append("- title: ").append(yamlString(line)).append("\n");
            if (!children.isEmpty()) {
                sb.append(pad).append("  sections:\n");
                for (NavItem child : children) emitLeaf(child, sb, childPad, childSectionPad);
            }
        }
    }

    static void emitLeaf(NavItem item, StringBuilder sb, String pad, String sectionPad) {
        String line = item.line;
        Matcher xref = XREF.matcher(line);
        Matcher link = LINK.matcher(line);

        if (xref.matches()) {
            String file = stripAdoc(xref.group(1));
            String label = xref.group(2).trim();
            if (needsTitle(label, file)) {
                sb.append(pad).append("- title: ").append(yamlString(label)).append("\n");
                sb.append(pad).append("  file: ").append(file).append("\n");
            } else {
                sb.append(pad).append("- file: ").append(file).append("\n");
            }
        } else if (link.matches()) {
            sb.append(pad).append("- title: ").append(yamlString(link.group(2).trim())).append("\n");
            sb.append(pad).append("  url: ").append(link.group(1).trim()).append("\n");
        } else {
            // Plain text — section header at depth 2 (no children expected)
            sb.append(pad).append("- title: ").append(yamlString(line)).append("\n");
        }
    }

    /** Strip .adoc extension and normalise the path for use as a toc.yaml file entry. */
    static String stripAdoc(String path) {
        if (path.endsWith(".adoc")) path = path.substring(0, path.length() - 5);
        return path;
    }

    /** Returns true if the nav label should be written as explicit title in toc.yaml. */
    static boolean needsTitle(String label, String file) {
        if (label.isEmpty()) return false;
        String slug = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
        String fromSlug = slug.replace('-', ' ').replace('_', ' ');
        return !label.equalsIgnoreCase(fromSlug);
    }

    static String yamlString(String s) {
        if (s.contains(":") || s.contains("\"") || s.contains("'") || s.contains("{")) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    static class NavItem {
        final int depth;
        final String line;
        NavItem(int depth, String line) { this.depth = depth; this.line = line; }
    }
}
