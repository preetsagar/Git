import java.nio.file.Path;

public class Main {
  public static void main(String[] args) throws Exception {
    System.err.println("Logs from your program will appear here!");

    if (args.length == 0) {
      System.err.println("usage: git <command> [<args>]");
      System.exit(1);
    }

    Path cwd = Path.of(".");
    String command = args[0];
    switch (command) {
      case "init" -> Git.init(cwd);
      case "cat-file" -> Git.catFilePretty(cwd, args[2]); // cat-file -p <sha>
      case "hash-object" -> {
        boolean write = args[1].equals("-w");
        Git.hashObject(cwd, Path.of(args[write ? 2 : 1]), write);
      }
      case "ls-tree" -> Git.lsTreeNameOnly(cwd, args[args.length - 1]); // ls-tree --name-only <sha>
      default -> {
        System.err.println("Unknown command: " + command);
        System.exit(1);
      }
    }
  }
}
