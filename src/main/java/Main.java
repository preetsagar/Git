import java.nio.file.Path;

public class Main {
  public static void main(String[] args) throws Exception {
    System.err.println("Logs from your program will appear here!");

    if (args.length == 0) {
      System.err.println("usage: git <command> [<args>]");
      System.exit(1);
    }

    final Path cwd = Path.of(".");
    final String command = args[0];
    switch (command) {
      case "init" -> Git.init(cwd);
      default -> {
        System.err.println("Unknown command: " + command);
        System.exit(1);
      }
    }
  }
}
