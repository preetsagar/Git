import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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
    int nul = indexOf(raw, 0, (byte) 0);
    System.out.write(raw, nul + 1, raw.length - nul - 1);
    System.out.flush();
  }

  /** {@code hash-object [-w] <file>}: print the blob SHA, optionally storing the object. */
  public static String hashObject(Path root, Path file, boolean write) throws IOException {
    byte[] content = Files.readAllBytes(file);
    String sha = write ? storeObject(root, "blob", content) : sha1Hex(wrap("blob", content));
    System.out.println(sha);
    return sha;
  }

  /** {@code ls-tree --name-only <sha>}: print each tree entry name on its own line, in stored order. */
  public static void lsTreeNameOnly(Path root, String sha) throws IOException {
    byte[] raw = readObject(root, sha);
    int pos = indexOf(raw, 0, (byte) 0) + 1; // skip "tree <size>\0"
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

  /** {@code write-tree}: recursively store the working tree, print the root tree SHA. */
  public static String writeTree(Path root) throws IOException {
    String sha = writeTreeRecursive(root, root);
    System.out.println(sha);
    return sha;
  }

  /** Store a tree object for {@code dir}; returns its 40-char hex SHA. */
  private static String writeTreeRecursive(Path root, Path dir) throws IOException {
    record Entry(String name, byte[] line) {}
    List<Entry> entries = new ArrayList<>();

    try (var stream = Files.list(dir)) {
      for (Path child : (Iterable<Path>) stream::iterator) {
        String name = child.getFileName().toString();
        if (name.equals(".git")) {
          continue;
        }
        String mode;
        String childSha;
        if (Files.isDirectory(child)) {
          mode = "40000";
          childSha = writeTreeRecursive(root, child);
        } else {
          mode = Files.isExecutable(child) ? "100755" : "100644";
          childSha = storeObject(root, "blob", Files.readAllBytes(child));
        }
        // sort key: git compares directory names as if they ended in '/'
        String sortName = mode.equals("40000") ? name + "/" : name;
        byte[] line = concat((mode + " " + name + "\0").getBytes(), HexFormat.of().parseHex(childSha));
        entries.add(new Entry(sortName, line));
      }
    }

    entries.sort(Comparator.comparing(Entry::name));
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (Entry e : entries) {
      body.writeBytes(e.line());
    }
    return storeObject(root, "tree", body.toByteArray());
  }

  // --- object store ---------------------------------------------------------

  /** Inflate the object file at {@code .git/objects/xx/yyy...} into its raw bytes (header + body). */
  static byte[] readObject(Path root, String sha) throws IOException {
    Path path = root.resolve(".git/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
    try (InflaterInputStream in = new InflaterInputStream(Files.newInputStream(path))) {
      return in.readAllBytes();
    }
  }

  /** Wrap {@code body} with a {@code "<type> <len>\0"} header, hash, zlib-store it; returns hex SHA. */
  static String storeObject(Path root, String type, byte[] body) throws IOException {
    byte[] object = wrap(type, body);
    String sha = sha1Hex(object);
    Path dir = root.resolve(".git/objects").resolve(sha.substring(0, 2));
    Files.createDirectories(dir);
    Path path = dir.resolve(sha.substring(2));
    if (!Files.exists(path)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      try (DeflaterOutputStream out =
          new DeflaterOutputStream(buf, new Deflater(Deflater.BEST_SPEED))) {
        out.write(object);
      }
      Files.write(path, buf.toByteArray());
    }
    return sha;
  }

  static byte[] wrap(String type, byte[] body) {
    return concat((type + " " + body.length + "\0").getBytes(), body);
  }

  // --- helpers -------------------------------------------------------------

  static String sha1Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
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

  static int indexOf(byte[] bytes, int from, byte target) {
    for (int i = from; i < bytes.length; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return -1;
  }
}
