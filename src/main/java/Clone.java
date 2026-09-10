import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;

/**
 * {@code clone <url> <dir>}: fetch a repository over Git's smart HTTP protocol (v1, no side-band,
 * ref-deltas only), write every object loose, and check out the default branch.
 */
public final class Clone {
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  public static void run(String url, String dir) throws IOException, InterruptedException {
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    Path root = Path.of(dir);
    Files.createDirectories(root);
    Git.init(root);

    // 1. Ref discovery.
    Refs refs = discoverRefs(url);

    // 2. Ask for the default branch tip and get a packfile back.
    byte[] pack = fetchPack(url, refs.headSha);

    // 3. Explode the packfile into loose objects.
    unpack(pack, root);

    // 4. Point HEAD + the branch ref at the fetched commit.
    Files.writeString(root.resolve(".git/HEAD"), "ref: " + refs.headRef + "\n");
    Path refFile = root.resolve(".git").resolve(refs.headRef);
    Files.createDirectories(refFile.getParent());
    Files.writeString(refFile, refs.headSha + "\n");
    Files.writeString(
        root.resolve(".git/config"),
        "[core]\n\trepositoryformatversion = 0\n\tbare = false\n"
            + "[remote \"origin\"]\n\turl = " + url + "\n");

    // 5. Check out the work tree.
    checkout(root, refs.headSha);
  }

  // --- protocol ------------------------------------------------------------

  private record Refs(String headSha, String headRef) {}

  private static Refs discoverRefs(String url) throws IOException, InterruptedException {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url + "/info/refs?service=git-upload-pack"))
            .header("User-Agent", "git/2.40.0")
            .GET()
            .build();
    byte[] body = send(req);

    Map<String, String> refs = new HashMap<>();
    String symrefTarget = null;
    for (byte[] line : pktLines(body)) {
      String s = new String(line).trim();
      if (s.isEmpty() || s.startsWith("#")) {
        continue;
      }
      int sp = s.indexOf(' ');
      if (sp != 40) {
        continue;
      }
      String sha = s.substring(0, sp);
      String rest = s.substring(sp + 1);
      int nul = rest.indexOf('\0');
      String name = nul >= 0 ? rest.substring(0, nul) : rest;
      if (nul >= 0) {
        for (String cap : rest.substring(nul + 1).split(" ")) {
          if (cap.startsWith("symref=HEAD:")) {
            symrefTarget = cap.substring("symref=HEAD:".length());
          }
        }
      }
      refs.put(name, sha);
    }

    String headRef = symrefTarget;
    if (headRef == null) {
      headRef = refs.containsKey("refs/heads/master") ? "refs/heads/master" : "refs/heads/main";
    }
    String headSha = refs.getOrDefault(headRef, refs.get("HEAD"));
    if (headSha == null) {
      throw new IOException("could not determine HEAD from ref advertisement");
    }
    return new Refs(headSha, headRef);
  }

  private static byte[] fetchPack(String url, String wantSha)
      throws IOException, InterruptedException {
    String reqBody = pkt("want " + wantSha + "\n") + "0000" + pkt("done\n");
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url + "/git-upload-pack"))
            .header("User-Agent", "git/2.40.0")
            .header("Content-Type", "application/x-git-upload-pack-request")
            .header("Accept", "application/x-git-upload-pack-result")
            .POST(HttpRequest.BodyPublishers.ofByteArray(reqBody.getBytes()))
            .build();
    byte[] body = send(req);

    // Response: one or more pkt-lines (NAK/ACK) then the raw packfile.
    int i = 0;
    while (i + 4 <= body.length && !new String(body, i, 4).equals("PACK")) {
      int len = Integer.parseInt(new String(body, i, 4), 16);
      i += len == 0 ? 4 : len;
    }
    return java.util.Arrays.copyOfRange(body, i, body.length);
  }

  private static byte[] send(HttpRequest req) throws IOException, InterruptedException {
    HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() / 100 != 2) {
      throw new IOException(req.uri() + " -> HTTP " + resp.statusCode());
    }
    return resp.body();
  }

  private static String pkt(String s) {
    return String.format("%04x", s.length() + 4) + s;
  }

  /** Split a byte buffer of pkt-lines into their payloads (flush packets dropped). */
  private static java.util.List<byte[]> pktLines(byte[] buf) {
    var out = new java.util.ArrayList<byte[]>();
    int i = 0;
    while (i + 4 <= buf.length) {
      int len = Integer.parseInt(new String(buf, i, 4), 16);
      if (len == 0) {
        i += 4;
        continue;
      }
      out.add(java.util.Arrays.copyOfRange(buf, i + 4, i + len));
      i += len;
    }
    return out;
  }

  // --- packfile -----------------------------------------------------------

  private static final int OBJ_COMMIT = 1, OBJ_TREE = 2, OBJ_BLOB = 3, OBJ_TAG = 4;
  private static final int OBJ_OFS_DELTA = 6, OBJ_REF_DELTA = 7;

  private record RawObject(String type, byte[] data) {}

  static void unpack(byte[] pack, Path root) throws IOException {
    if (!new String(pack, 0, 4).equals("PACK")) {
      throw new IOException("bad packfile signature");
    }
    long count = u32(pack, 8);
    int[] pos = {12};

    // sha -> object, so ref-deltas can resolve against earlier entries.
    Map<String, RawObject> byId = new HashMap<>();

    for (long n = 0; n < count; n++) {
      int b = pack[pos[0]++] & 0xff;
      int type = (b >> 4) & 7;
      long size = b & 0x0f;
      int shift = 4;
      while ((b & 0x80) != 0) {
        b = pack[pos[0]++] & 0xff;
        size |= (long) (b & 0x7f) << shift;
        shift += 7;
      }

      if (type == OBJ_OFS_DELTA) {
        // We never advertise the ofs-delta capability, so the server won't send these.
        throw new IOException("unexpected ofs-delta in packfile");
      }

      if (type == OBJ_REF_DELTA) {
        String baseId = HexFormat.of().formatHex(pack, pos[0], pos[0] + 20);
        pos[0] += 20;
        byte[] delta = inflate(pack, pos, (int) size);
        RawObject base = byId.get(baseId);
        if (base == null) {
          throw new IOException("ref-delta base not in pack: " + baseId);
        }
        byte[] result = applyDelta(base.data, delta);
        store(root, byId, new RawObject(base.type, result));
      } else {
        byte[] data = inflate(pack, pos, (int) size);
        store(root, byId, new RawObject(typeName(type), data));
      }
    }
  }

  private static void store(Path root, Map<String, RawObject> byId, RawObject obj) throws IOException {
    String sha = Git.storeObject(root, obj.type, obj.data);
    byId.put(sha, obj);
  }

  private static String typeName(int type) throws IOException {
    return switch (type) {
      case OBJ_COMMIT -> "commit";
      case OBJ_TREE -> "tree";
      case OBJ_BLOB -> "blob";
      case OBJ_TAG -> "tag";
      default -> throw new IOException("unknown pack object type " + type);
    };
  }

  /** Inflate exactly {@code outSize} bytes starting at {@code pos[0]}; advance pos past compressed data. */
  private static byte[] inflate(byte[] src, int[] pos, int outSize) throws IOException {
    Inflater inf = new Inflater();
    inf.setInput(src, pos[0], src.length - pos[0]);
    byte[] out = new byte[outSize];
    try {
      int got = 0;
      // Inflate until the stream ends, so getBytesRead() covers the whole zlib member
      // (including its trailing checksum) and pos lands on the next object header.
      while (!inf.finished()) {
        int r = inf.inflate(out, got, out.length - got);
        if (r == 0) {
          if (inf.finished()) {
            break;
          }
          if (inf.needsInput()) {
            throw new IOException("truncated zlib stream in packfile");
          }
          // output buffer full but stream not done — shouldn't happen if outSize is correct
          out = java.util.Arrays.copyOf(out, out.length + 64);
        }
        got += r;
      }
      pos[0] += (int) inf.getBytesRead();
      return got == out.length ? out : java.util.Arrays.copyOf(out, got);
    } catch (java.util.zip.DataFormatException e) {
      throw new IOException(e);
    } finally {
      inf.end();
    }
  }

  /** Apply a git delta stream to {@code base}. */
  private static byte[] applyDelta(byte[] base, byte[] delta) {
    int[] p = {0};
    readVarint(delta, p); // base size (unused)
    int targetSize = (int) readVarint(delta, p);
    ByteArrayOutputStream out = new ByteArrayOutputStream(targetSize);
    while (p[0] < delta.length) {
      int op = delta[p[0]++] & 0xff;
      if ((op & 0x80) != 0) { // copy from base
        long off = 0, len = 0;
        for (int i = 0; i < 4; i++) {
          if ((op & (1 << i)) != 0) {
            off |= (long) (delta[p[0]++] & 0xff) << (8 * i);
          }
        }
        for (int i = 0; i < 3; i++) {
          if ((op & (1 << (4 + i))) != 0) {
            len |= (long) (delta[p[0]++] & 0xff) << (8 * i);
          }
        }
        if (len == 0) {
          len = 0x10000;
        }
        out.write(base, (int) off, (int) len);
      } else { // insert literal
        out.write(delta, p[0], op);
        p[0] += op;
      }
    }
    return out.toByteArray();
  }

  private static long readVarint(byte[] b, int[] p) {
    long v = 0;
    int shift = 0, x;
    do {
      x = b[p[0]++] & 0xff;
      v |= (long) (x & 0x7f) << shift;
      shift += 7;
    } while ((x & 0x80) != 0);
    return v;
  }

  private static long u32(byte[] b, int off) {
    return ((long) (b[off] & 0xff) << 24)
        | ((b[off + 1] & 0xff) << 16)
        | ((b[off + 2] & 0xff) << 8)
        | (b[off + 3] & 0xff);
  }

  // --- checkout ----------------------------------------------------------

  private static void checkout(Path root, String commitSha) throws IOException {
    byte[] commit = objectBody(root, commitSha);
    String header = new String(commit);
    String treeSha = header.substring(5, 45); // "tree <40>\n"
    writeTree(root, treeSha, root);
  }

  private static void writeTree(Path root, String treeSha, Path dir) throws IOException {
    byte[] body = objectBody(root, treeSha);
    int i = 0;
    while (i < body.length) {
      int sp = indexOf(body, i, (byte) ' ');
      String mode = new String(body, i, sp - i);
      int nul = indexOf(body, sp + 1, (byte) 0);
      String name = new String(body, sp + 1, nul - sp - 1);
      String sha = HexFormat.of().formatHex(body, nul + 1, nul + 21);
      i = nul + 21;

      Path target = dir.resolve(name);
      switch (mode) {
        case "40000" -> {
          Files.createDirectories(target);
          writeTree(root, sha, target);
        }
        case "100644", "100755" -> {
          Files.write(target, objectBody(root, sha));
          if (mode.equals("100755")) {
            try {
              Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
              perms.add(PosixFilePermission.OWNER_EXECUTE);
              perms.add(PosixFilePermission.GROUP_EXECUTE);
              perms.add(PosixFilePermission.OTHERS_EXECUTE);
              Files.setPosixFilePermissions(target, perms);
            } catch (UnsupportedOperationException ignored) {
              // non-POSIX filesystem
            }
          }
        }
        case "120000" -> {
          Path linkTarget = Path.of(new String(objectBody(root, sha)));
          Files.deleteIfExists(target);
          try {
            Files.createSymbolicLink(target, linkTarget);
          } catch (IOException | UnsupportedOperationException e) {
            Files.write(target, objectBody(root, sha));
          }
        }
        default -> {
          // gitlinks (160000) and anything else: skip
        }
      }
    }
  }

  private static byte[] objectBody(Path root, String sha) throws IOException {
    byte[] raw = Git.readObject(root, sha);
    int nul = indexOf(raw, 0, (byte) 0);
    return java.util.Arrays.copyOfRange(raw, nul + 1, raw.length);
  }

  private static int indexOf(byte[] bytes, int from, byte target) {
    for (int i = from; i < bytes.length; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return -1;
  }
}
