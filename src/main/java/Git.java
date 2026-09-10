import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal Git plumbing operating on the {@code .git} directory under a given root. */
public final class Git {
  private Git() {}

  /** Create a fresh {@code .git} directory: objects/, refs/, and HEAD pointing at master. */
  public static void init(Path root) throws IOException {
    Path gitDir = root.resolve(".git");
    Files.createDirectories(gitDir.resolve("objects"));
    Files.createDirectories(gitDir.resolve("refs"));
    Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
    System.out.println("Initialized git directory");
  }
}
