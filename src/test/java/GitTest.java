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

  @Test
  void commitTreeIsReadableByGit(@TempDir Path root) throws Exception {
    run(root, "git", "init", "-q");
    Files.writeString(root.resolve("a.txt"), "hi\n");
    run(root, "git", "add", "-A");
    run(root, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first");
    String parent = run(root, "git", "rev-parse", "HEAD").trim();
    String tree = run(root, "git", "rev-parse", "HEAD^{tree}").trim();

    String sha =
        capture(() -> Git.commitTree(root, tree, java.util.List.of(parent), "my message")).trim();

    assertEquals(tree, run(root, "git", "rev-parse", sha + "^{tree}").trim());
    assertEquals(parent, run(root, "git", "rev-parse", sha + "^").trim());
    assertEquals("my message", run(root, "git", "log", "-1", "--format=%B", sha).strip());
    // commit body after the header blank line must be exactly "<message>\n" (what go-git checks)
    String raw = capture(() -> Git.catFilePretty(root, sha));
    assertTrue(raw.endsWith("\nmy message\n"), raw);
  }

  @Test
  void unpackExplodesAPackWithRefDeltas(@TempDir Path src, @TempDir Path dst) throws Exception {
    // Source repo with a big file changed slightly across two commits -> git will delta them.
    run(src, "git", "init", "-q");
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      big.append("line ").append(i).append('\n');
    }
    Files.writeString(src.resolve("big.txt"), big);
    Files.createDirectories(src.resolve("d"));
    Files.writeString(src.resolve("d/small.txt"), "hello\n");
    run(src, "git", "add", "-A");
    run(src, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "one");
    Files.writeString(src.resolve("big.txt"), big.append("one more line\n"));
    run(src, "git", "add", "-A");
    run(src, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "two");

    // Build a REF_DELTA pack (mirrors what a server without ofs-delta capability sends).
    String objects = run(src, "git", "rev-list", "--all", "--objects");
    Path shaList = src.resolve("shas.txt");
    StringBuilder shas = new StringBuilder();
    for (String line : objects.strip().split("\n")) {
      shas.append(line.split(" ")[0]).append('\n');
    }
    Files.writeString(shaList, shas);
    byte[] pack =
        runBytes(
            src, shaList, "git", "-c", "pack.useDeltaBaseOffset=false", "pack-objects", "--stdout");

    Git.init(dst);
    Clone.unpack(pack, dst);

    String headTree = run(src, "git", "rev-parse", "HEAD^{tree}").trim();
    // Every object from the source must now be a valid loose object in dst.
    for (String line : objects.strip().split("\n")) {
      String sha = line.split(" ")[0];
      assertEquals(
          run(src, "git", "cat-file", "-t", sha).trim(),
          run(dst, "git", "cat-file", "-t", sha).trim(),
          "type mismatch for " + sha);
    }
    assertEquals(
        run(src, "git", "cat-file", "-p", headTree),
        run(dst, "git", "cat-file", "-p", headTree));
  }

  private static String run(Path dir, String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + out);
    }
    return out;
  }

  /** Run a command with {@code stdinFile} piped to stdin; return raw stdout bytes. */
  private static byte[] runBytes(Path dir, Path stdinFile, String... cmd) throws Exception {
    Process p =
        new ProcessBuilder(cmd)
            .directory(dir.toFile())
            .redirectInput(stdinFile.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    byte[] out = p.getInputStream().readAllBytes();
    if (p.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", cmd) + " exited nonzero");
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
