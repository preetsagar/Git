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

  @Test
  void lsTreeNameOnlyListsEntriesInOrder(@TempDir Path root) throws Exception {
    // Build a real tree with git, then check our reader against `git ls-tree --name-only`.
    run(root, "git", "init", "-q");
    Files.writeString(root.resolve("root.txt"), "a");
    Files.createDirectories(root.resolve("beta"));
    Files.writeString(root.resolve("beta/x"), "b");
    Files.createDirectories(root.resolve("alpha"));
    Files.writeString(root.resolve("alpha/y"), "c");
    run(root, "git", "add", "-A");
    run(root, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "t");
    String treeSha = run(root, "git", "rev-parse", "HEAD^{tree}").trim();

    String expected = run(root, "git", "ls-tree", "--name-only", treeSha);
    String actual = capture(() -> Git.lsTreeNameOnly(root, treeSha));
    assertEquals(expected, actual);
  }

  @Test
  void writeTreeMatchesGit(@TempDir Path root) throws Exception {
    // Same layout the tester builds: a root file, dir1 with two files, dir2 with one.
    Files.writeString(root.resolve("root.txt"), "a\n");
    Files.createDirectories(root.resolve("zed"));
    Files.writeString(root.resolve("zed/f2"), "b\n");
    Files.writeString(root.resolve("zed/f3"), "cc\n");
    Files.createDirectories(root.resolve("mid"));
    Files.writeString(root.resolve("mid/f4"), "dddd\n");
    // a name that collides on the dir-vs-file sort boundary
    Files.writeString(root.resolve("mid.txt"), "e\n");

    Git.init(root);
    String ours = capture(() -> Git.writeTree(root)).trim();

    run(root, "git", "init", "-q");
    run(root, "git", "add", "-A");
    String expected = run(root, "git", "write-tree").trim();
    assertEquals(expected, ours);
  }

  private static String run(Path dir, String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + out);
    }
    return out;
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
