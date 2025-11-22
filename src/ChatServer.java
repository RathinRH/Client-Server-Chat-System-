// ChatServer.java
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class ChatServer {
    private int port;

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        System.out.println("Server listening on port " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                System.out.println("Waiting for a client to connect...");
                Socket client = serverSocket.accept();
                System.out.println("Client connected: " + client.getRemoteSocketAddress());
                handleClient(client);
                // after handleClient returns, server will accept next client
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            Connection conn = new Connection(client);
            conn.readLoop(
                msg -> System.out.println("[CLIENT] " + msg),
                (filename, saved) -> {
                    System.out.println("[CLIENT SENT FILE] " + filename + " -> saved as " + saved.getAbsolutePath());
                    // Attempt to open in background
                    new Thread(() -> tryOpenFile(saved), "OpenFile-Thread").start();
                },
                () -> System.out.println("Client disconnected.")
            );

            // read from stdin to send messages / files
            Scanner sc = new Scanner(System.in);
            while (!client.isClosed()) {
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
            System.out.println("Connection error: " + ex.getMessage());
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

    public static void main(String[] args) throws IOException {
        int port = 5000;
        if (args.length > 0) port = Integer.parseInt(args[0]);
        new ChatServer(port).start();
    }
}
