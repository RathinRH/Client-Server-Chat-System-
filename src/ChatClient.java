// ChatClient.java
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class ChatClient {
    private String host;
    private int port;

    public ChatClient(String host, int port) {
        this.host = host; this.port = port;
    }

    public void start() {
        try (Socket socket = new Socket(host, port)) {
            System.out.println("Connected to server " + host + ":" + port);
            Connection conn = new Connection(socket);

            conn.readLoop(
                msg -> System.out.println("[SERVER] " + msg),
                (filename, saved) -> {
                    System.out.println("[SERVER SENT FILE] " + filename + " -> saved as " + saved.getAbsolutePath());
                    // open in background
                    new Thread(() -> tryOpenFile(saved), "OpenFile-Thread").start();
                },
                () -> System.out.println("Server closed connection.")
            );

            Scanner sc = new Scanner(System.in);
            while (!socket.isClosed()) {
                System.out.println("Enter (m)essage or (f)ile or (q)uit:");
                String cmd = sc.nextLine();
                if (cmd.equalsIgnoreCase("q")) {
                    conn.close();
                    break;
                } else if (cmd.equalsIgnoreCase("m")) {
                    System.out.print("Message: ");
                    String text = sc.nextLine();
                    conn.sendMessage(text);
                } else if (cmd.equalsIgnoreCase("f")) {
                    System.out.print("Path to file to send: ");
                    String path = sc.nextLine();
                    File f = new File(path);
                    if (f.exists() && f.isFile()) {
                        conn.sendFile(f);
                        System.out.println("File sent.");
                    } else {
                        System.out.println("File not found.");
                    }
                } else {
                    System.out.println("Unknown command.");
                }
            }
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
            //ex.printStackTrace();
        }
    }

    // Try to open the file with the system default application (if supported)
    private void tryOpenFile(File f) {
        try {
            if (f == null || !f.exists()) {
                System.out.println("File not available to open: " + (f == null ? "null" : f.getAbsolutePath()));
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                System.out.println("Open operation not supported on this platform.");
                return;
            }
            Desktop.getDesktop().open(f);
            System.out.println("Opened file: " + f.getAbsolutePath());
        } catch (IOException ex) {
            System.out.println("Unable to open file: " + ex.getMessage());
        } catch (SecurityException ex) {
            System.out.println("Permission denied when trying to open file: " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 5000;
        if (args.length >= 1) host = args[0];
        if (args.length >= 2) port = Integer.parseInt(args[1]);
        new ChatClient(host, port).start();
    }
}
