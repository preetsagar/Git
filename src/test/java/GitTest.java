import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitTest {
  @Test
  void initCreatesRepoLayout(@TempDir Path root) throws IOException {
    Git.init(root);

    Path git = root.resolve(".git");
    assertTrue(Files.isDirectory(git.resolve("objects")), "objects/ missing");
    assertTrue(Files.isDirectory(git.resolve("refs")), "refs/ missing");
    assertEquals("ref: refs/heads/main\n", Files.readString(git.resolve("HEAD")));
  }
}
