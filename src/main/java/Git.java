import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Minimal Git plumbing operating on the {@code .git} directory under a given root. */
public final class Git {
  private Git() {}

  /** Create a fresh {@code .git} directory: objects/, refs/, and HEAD. */
  public static void init(Path root) throws IOException {
    Path gitDir = root.resolve(".git");
    Files.createDirectories(gitDir.resolve("objects"));
    Files.createDirectories(gitDir.resolve("refs"));
    Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
    System.out.println("Initialized git directory");
  }

  /** {@code cat-file -p <sha>}: write the raw body of the object to stdout. */
  public static void catFilePretty(Path root, String sha) throws IOException {
    byte[] raw = readObject(root, sha);
    int nul = indexOf(raw, (byte) 0);
    System.out.write(raw, nul + 1, raw.length - nul - 1);
    System.out.flush();
  }

  /** {@code hash-object [-w] <file>}: print the blob SHA, optionally storing the object. */
  public static String hashObject(Path root, Path file, boolean write) throws IOException {
    byte[] content = Files.readAllBytes(file);
    byte[] object = concat(("blob " + content.length + "\0").getBytes(), content);
    String sha = sha1Hex(object);
    if (write) {
      writeObject(root, sha, object);
    }
    System.out.println(sha);
    return sha;
  }

  /** {@code ls-tree --name-only <sha>}: print each tree entry name on its own line, in stored order. */
  public static void lsTreeNameOnly(Path root, String sha) throws IOException {
    byte[] raw = readObject(root, sha);
    int pos = indexOf(raw, (byte) 0) + 1; // skip "tree <size>\0"
    StringBuilder out = new StringBuilder();
    while (pos < raw.length) {
      pos = indexOf(raw, pos, (byte) ' ') + 1; // skip "<mode> "
      int nameEnd = indexOf(raw, pos, (byte) 0);
      out.append(new String(raw, pos, nameEnd - pos)).append('\n');
      pos = nameEnd + 1 + 20; // skip name\0 + 20-byte sha
    }
    System.out.print(out);
    System.out.flush();
  }

  // --- object store ---------------------------------------------------------

  /** Inflate the object file at {@code .git/objects/xx/yyy...} into its raw bytes (header + body). */
  static byte[] readObject(Path root, String sha) throws IOException {
    Path path = root.resolve(".git/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
    try (InflaterInputStream in = new InflaterInputStream(Files.newInputStream(path))) {
      return in.readAllBytes();
    }
  }

  /** Zlib-compress {@code object} and store it at {@code .git/objects/xx/yyy...}. */
  static void writeObject(Path root, String sha, byte[] object) throws IOException {
    Path dir = root.resolve(".git/objects").resolve(sha.substring(0, 2));
    Files.createDirectories(dir);
    Path path = dir.resolve(sha.substring(2));
    if (Files.exists(path)) {
      return;
    }
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (DeflaterOutputStream out = new DeflaterOutputStream(buf, new Deflater(Deflater.BEST_SPEED))) {
      out.write(object);
    }
    Files.write(path, buf.toByteArray());
  }

  // --- helpers -------------------------------------------------------------

  static String sha1Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha1(bytes));
  }

  static byte[] sha1(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] r = new byte[a.length + b.length];
    System.arraycopy(a, 0, r, 0, a.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }

  static int indexOf(byte[] bytes, byte target) {
    return indexOf(bytes, 0, target);
  }

  static int indexOf(byte[] bytes, int from, byte target) {
    for (int i = from; i < bytes.length; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return -1;
  }
}
