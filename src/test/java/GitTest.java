import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

  @Test
  void hashObjectMatchesGitSha(@TempDir Path root) throws IOException {
    Git.init(root);
    Path file = root.resolve("hello.txt");
    Files.writeString(file, "hello world\n");

    // sha1 of "blob 12\0hello world\n" == known git blob sha
    String sha = capture(() -> Git.hashObject(root, file, true)).trim();
    assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", sha);

    assertTrue(
        Files.exists(root.resolve(".git/objects/3b/18e512dba79e4c8300dd08aeb37f8e728b8dad")),
        "object not written");
  }

  @Test
  void catFileRoundTripsBlobBody(@TempDir Path root) throws IOException {
    Git.init(root);
    Path file = root.resolve("data.bin");
    byte[] body = "line1\nline2 no trailing newline".getBytes();
    Files.write(file, body);

    String sha = capture(() -> Git.hashObject(root, file, true)).trim();
    byte[] out = captureBytes(() -> Git.catFilePretty(root, sha));
    assertEquals(new String(body), new String(out));
  }

  private interface Action {
    void run() throws IOException;
  }

  private static String capture(Action a) throws IOException {
    return new String(captureBytes(a));
  }

  private static byte[] captureBytes(Action a) throws IOException {
    PrintStream original = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf));
    try {
      a.run();
    } finally {
      System.setOut(original);
    }
    return buf.toByteArray();
  }
}
