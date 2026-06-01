///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Converts the Antora nav.adoc in each content directory to a toc.yaml
//DESCRIPTION for the Azul publish flow.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang ConvertNavToToc.java [content-dir ...]              # one or more dirs
//DESCRIPTION   jbang ConvertNavToToc.java [content-dir] --title="My Title"  # override title
//DESCRIPTION
//DESCRIPTION If no content-dir is given, runs on content_enterprise and content_community.
//DESCRIPTION (content_shared is excluded by default as it has no nav.adoc.)
//DESCRIPTION
//DESCRIPTION For each directory, reads <dir>/nav.adoc and writes <dir>/toc.yaml.
//DESCRIPTION The toc title is auto-derived from the directory name unless --title= is set.
//DESCRIPTION
//DESCRIPTION Conversion rules:
//DESCRIPTION   .Title                  -> top-level section
//DESCRIPTION   * xref:Path[Label]      -> file leaf (depth 1 under current section)
//DESCRIPTION   ** Sub Section          -> section header (depth 2, no xref)
//DESCRIPTION   ** xref:Path[Label]     -> file leaf (depth 2)
//DESCRIPTION   include::partial$x[]   -> resolved inline from partials/ directory
//DESCRIPTION   title-only + url: /x   -> external URL entry
//DESCRIPTION
//DESCRIPTION Path transform: lowercase, spaces to hyphens, drop .adoc, overview -> index.
//DESCRIPTION Title is included in toc.yaml only when it differs from the file slug.

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ConvertNavToToc {

    static final Pattern XREF_PATTERN = Pattern.compile("xref:([^\\[]+)\\[([^\\]]*)\\]");
    static final Pattern PARTIAL_PATTERN = Pattern.compile("include::partial\\$([^\\[]+)\\[([^\\]]*)\\]");
    static final Pattern URL_PATTERN = Pattern.compile("^\\s+url:\\s*(\\S+)\\s*$");

    // --- Data model ----------------------------------------------------------

    static abstract class NavItem {
        String title; // may be null
        List<NavItem> children = new ArrayList<>();
    }

    static class SectionItem extends NavItem {
        SectionItem(String title) { this.title = title; }
    }

    static class FileItem extends NavItem {
        String file;
        FileItem(String title, String file) { this.title = title; this.file = file; }
    }

    static class UrlItem extends NavItem {
        String url;
        UrlItem(String title, String url) { this.title = title; this.url = url; }
    }

    // --- Entry point ---------------------------------------------------------

    public static void main(String[] args) throws IOException {
        List<Path> contentDirs = new ArrayList<>();
        String titleOverride = null;

        for (String arg : args) {
            if (arg.startsWith("--title=")) {
                titleOverride = arg.substring("--title=".length());
            } else if (!arg.startsWith("--")) {
                contentDirs.add(Path.of(arg));
            }
        }
        if (contentDirs.isEmpty()) {
            contentDirs.add(Path.of("content_enterprise"));
            contentDirs.add(Path.of("content_community"));
        }

        for (Path contentDir : contentDirs) {
            Path dir = contentDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                System.err.println("Not a directory: " + dir + " — skipping");
                continue;
            }
            Path navPath = dir.resolve("nav.adoc");
            if (!Files.isRegularFile(navPath)) {
                System.err.println("nav.adoc not found in: " + dir + " — skipping");
                continue;
            }

            String tocTitle = titleOverride != null ? titleOverride : deriveTitle(dir.getFileName().toString());
            Path outputPath = dir.resolve("toc.yaml");

            List<String> lines = resolveIncludes(navPath);
            List<NavItem> topItems = parse(lines);

            StringBuilder sb = new StringBuilder();
            sb.append("title: ").append(tocTitle).append("\n\n");
            sb.append("sections:\n");
            emitItems(topItems, sb, 1);

            Files.writeString(outputPath, sb.toString());
            System.out.println("Written: " + outputPath);
        }
    }

    /** Derives a human-readable title from a content directory name.
     *  Known directories have fixed titles; others are capitalised from the name.
     *  Examples:
     *    content_enterprise -> "Payara Enterprise Documentation"
     *    content_community  -> "Payara Community Documentation"
     */
    static String deriveTitle(String dirName) {
        switch (dirName) {
            case "content_enterprise": return "Payara Enterprise Documentation";
            case "content_community":  return "Payara Community Documentation";
            default:
                // Strip leading "content_" or "content-", capitalise each word
                String base = dirName.replaceFirst("^content[_-]", "");
                StringBuilder sb = new StringBuilder();
                for (String word : base.split("[_\\-]+")) {
                    if (!word.isEmpty()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(Character.toUpperCase(word.charAt(0)));
                        sb.append(word.substring(1));
                    }
                }
                return "Payara " + sb + " Documentation";
        }
    }

    // --- Partial resolution --------------------------------------------------

    static List<String> resolveIncludes(Path navPath) throws IOException {
        Path partialsDir = navPath.getParent().resolve("partials");
        return resolveLines(Files.readAllLines(navPath), partialsDir);
    }

    static List<String> resolveLines(List<String> lines, Path partialsDir) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            Matcher m = PARTIAL_PATTERN.matcher(line.trim());
            if (m.matches()) {
                String filename = m.group(1).trim();
                Path partial = partialsDir.resolve(filename);
                if (Files.exists(partial)) {
                    result.addAll(resolveLines(Files.readAllLines(partial), partialsDir));
                } else {
                    System.err.println("WARNING: partial not found: " + partial);
                    result.add("# TODO: include partial " + filename);
                }
            } else {
                result.add(line);
            }
        }
        return result;
    }

    // --- Parsing -------------------------------------------------------------

    /**
     * Parse the flat list of nav.adoc lines into a tree of NavItem.
     * Returns the list of top-level items.
     */
    static List<NavItem> parse(List<String> lines) {
        // parentStack indexing rule:
        //   parentStack[d] = the current active container for items at depth d.
        //   parentStack[0] = root (holds .Title sections).
        //   parentStack[1] = last .Title section (holds depth-1 items).
        //   parentStack[d+1] = last section header at depth d (holds depth d+1 items).
        //
        // When a SECTION HEADER at depth d is processed:
        //   - its parent is parentStack[d]
        //   - it becomes parentStack[d+1] (replacing any stale deeper entry)
        //
        // When a LEAF (file/url) at depth d is processed:
        //   - its parent is parentStack[d]
        //   - parentStack is NOT updated (sibling leaves share the same parent)

        SectionItem root = new SectionItem("ROOT");

        // Use an array-backed list so we can set(index, value) at any position
        List<NavItem> parentStack = new ArrayList<>();
        parentStack.add(root); // index 0 = root

        // A title-only list item (no xref) might be either:
        //   (a) a section header — if the next content line is another list item, or
        //   (b) a URL leaf      — if the very next line is "  url: /path"
        // We hold the section candidate as "pending" and decide on the next line.
        SectionItem pendingSection = null;
        NavItem pendingSectionParent = null;
        int pendingSectionDepth = -1;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("# TODO")) {
                continue;
            }

            // URL continuation: resolves the pending section as a URL leaf instead
            Matcher urlMatcher = URL_PATTERN.matcher(line);
            if (urlMatcher.matches()) {
                if (pendingSection != null && pendingSectionParent != null) {
                    // The "section" was actually a URL leaf — swap it out
                    pendingSectionParent.children.add(
                            new UrlItem(pendingSection.title, urlMatcher.group(1)));
                    pendingSection = null;
                    pendingSectionParent = null;
                    pendingSectionDepth = -1;
                }
                continue;
            }

            // Flush pending section: it was a genuine section (no url: followed it)
            if (pendingSection != null) {
                pendingSectionParent.children.add(pendingSection);
                setAt(parentStack, pendingSectionDepth + 1, pendingSection);
                pendingSection = null;
                pendingSectionParent = null;
                pendingSectionDepth = -1;
            }

            // .Title — top-level section (depth 0, no asterisks)
            if (trimmed.startsWith(".") && !trimmed.startsWith("..")) {
                String title = trimmed.substring(1).trim();
                SectionItem section = new SectionItem(title);
                root.children.add(section);
                setAt(parentStack, 1, section);
                continue;
            }

            if (!trimmed.startsWith("*")) {
                continue;
            }

            // Count asterisks to get depth
            int depth = 0;
            while (depth < trimmed.length() && trimmed.charAt(depth) == '*') depth++;
            String content = trimmed.substring(depth).trim();

            // Ensure parentStack[depth] exists
            while (parentStack.size() <= depth) {
                parentStack.add(parentStack.get(parentStack.size() - 1));
            }

            NavItem parent = parentStack.get(depth);

            Matcher xrefMatcher = XREF_PATTERN.matcher(content);
            if (xrefMatcher.find()) {
                // Leaf: file entry (leaves don't update parentStack)
                String xrefPath = xrefMatcher.group(1).trim();
                String label = xrefMatcher.group(2).trim();
                String filePath = toFilePath(xrefPath);
                String title = shouldIncludeTitle(label, filePath) ? label : null;
                parent.children.add(new FileItem(title, filePath));
            } else {
                // Title-only item: defer adding until we know it's not a URL leaf
                pendingSection = new SectionItem(content.trim());
                pendingSectionParent = parent;
                pendingSectionDepth = depth;
            }
        }

        // Flush any remaining pending section at end of file
        if (pendingSection != null) {
            pendingSectionParent.children.add(pendingSection);
            setAt(parentStack, pendingSectionDepth + 1, pendingSection);
        }

        return root.children;
    }

    /** Set parentStack[index] = value, growing the list with nulls if needed. */
    static void setAt(List<NavItem> stack, int index, NavItem value) {
        while (stack.size() <= index) stack.add(null);
        stack.set(index, value);
    }

    // --- Path conversion -----------------------------------------------------

    static String toFilePath(String xrefPath) {
        // Remove .adoc extension
        String path = xrefPath;
        if (path.endsWith(".adoc")) {
            path = path.substring(0, path.length() - 5);
        }

        // Split by / and transform each segment
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("/");
            String seg = slugify(parts[i]);
            // Rename 'overview' filename (last segment only) to 'index'
            if (i == parts.length - 1 && seg.equals("overview")) {
                seg = "index";
            }
            sb.append(seg);
        }
        return sb.toString();
    }

    /** Lowercase, replace spaces and special chars with hyphens, collapse multiple hyphens. */
    static String slugify(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * Returns true if the nav label should be written as an explicit title in toc.yaml.
     * We omit the title when it's redundant: the label slug matches the file's last segment,
     * or the label is "Overview" and the file ends with "index".
     */
    static boolean shouldIncludeTitle(String label, String filePath) {
        if (label == null || label.isEmpty()) return false;
        String fileSlug = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        String labelSlug = slugify(label);
        // Direct match
        if (labelSlug.equals(fileSlug)) return false;
        // "Overview" -> "index" rename is expected, don't add title
        if (labelSlug.equals("overview") && fileSlug.equals("index")) return false;
        return true;
    }

    // --- YAML emission -------------------------------------------------------

    static void emitItems(List<NavItem> items, StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        for (NavItem item : items) {
            if (item instanceof SectionItem) {
                SectionItem s = (SectionItem) item;
                if (s.children.isEmpty()) {
                    // Empty section — skip or emit as comment
                    sb.append(pad).append("# empty section: ").append(s.title).append("\n");
                } else {
                    sb.append(pad).append("- title: ").append(yamlString(s.title)).append("\n");
                    sb.append(pad).append("  sections:\n");
                    emitItems(s.children, sb, indent + 2);
                }
            } else if (item instanceof FileItem) {
                FileItem f = (FileItem) item;
                if (f.title != null) {
                    sb.append(pad).append("- title: ").append(yamlString(f.title)).append("\n");
                    sb.append(pad).append("  file: ").append(f.file).append("\n");
                } else {
                    sb.append(pad).append("- file: ").append(f.file).append("\n");
                }
                // FileItems can have children in toc.yaml (file + sections)
                if (!f.children.isEmpty()) {
                    sb.append(pad).append("  sections:\n");
                    emitItems(f.children, sb, indent + 2);
                }
            } else if (item instanceof UrlItem) {
                UrlItem u = (UrlItem) item;
                sb.append(pad).append("- title: ").append(yamlString(u.title)).append("\n");
                sb.append(pad).append("  url: ").append(u.url).append("\n");
            }
        }
    }

    /** Wrap in quotes if the string contains YAML-special characters. */
    static String yamlString(String s) {
        if (s == null) return "\"\"";
        if (s.contains(":") || s.contains("#") || s.contains("'") || s.contains("\"")
                || s.startsWith("*") || s.startsWith("&") || s.startsWith("!")) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}
