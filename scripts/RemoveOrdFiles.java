///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Removes all files matching the `.ord*` pattern from the content directories.
//DESCRIPTION These are artefacts left by the Antora build process.
//DESCRIPTION
//DESCRIPTION Usage:
//DESCRIPTION   jbang RemoveOrdFiles.java [content-dir ...]           # dry run
//DESCRIPTION   jbang RemoveOrdFiles.java [content-dir ...] --apply   # delete files
//DESCRIPTION
//DESCRIPTION If no content-dir is given, runs on content_enterprise, content_community,
//DESCRIPTION and content_shared.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class RemoveOrdFiles {

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

            int count = 0;
            try (Stream<Path> s = Files.walk(content)) {
                List<Path> matches = s
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches("\\.ord.*"))
                        .sorted()
                        .toList();

                for (Path p : matches) {
                    count++;
                    System.out.println("  [" + (apply ? "deleted" : "would delete") + "] " + content.relativize(p));
                    if (apply) Files.delete(p);
                }
            }

            System.out.printf("%nDone: %d file(s) %s.%n%n", count, apply ? "deleted" : "would be deleted");
        }
    }
}
