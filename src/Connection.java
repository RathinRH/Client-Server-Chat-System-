import java.io.*;
import java.net.Socket;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Connection {
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    public Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    // Send a text message
    public synchronized void sendMessage(String message) throws IOException {
        out.writeUTF("MSG");
        out.writeUTF(message);
        out.flush();
    }

    // Send a file (filename, byte[] or stream)
    public synchronized void sendFile(File file) throws IOException {
        out.writeUTF("FILE");
        out.writeUTF(file.getName());
        out.writeLong(file.length());
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.flush();
    }

    /**
     * Read loop: run on a background thread.
     *
     * onMessage -> Consumer<String> : receives text messages
     * onFile    -> BiConsumer<String, File> : receives filename and saved File reference
     * onDisconnect -> Runnable : called when connection ends
     */
    public void readLoop(Consumer<String> onMessage, BiConsumer<String, File> onFile, Runnable onDisconnect) {
        new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    String type = null;
                    try {
                        type = in.readUTF();
                    } catch (EOFException eof) {
                        // remote closed the connection gracefully
                        System.out.println("Remote closed connection (EOF).");
                        break; // break out of while
                    } catch (IOException ioe) {
                        System.out.println("IO error while reading data type: " + ioe.getMessage());
                        break; // break out of while
                    }

                    // if type is null here, we break above; otherwise proceed
                    if ("MSG".equals(type)) {
                        try {
                            String msg = in.readUTF();
                            if (onMessage != null) onMessage.accept(msg);
                        } catch (IOException e) {
                            System.out.println("Error reading message: " + e.getMessage());
                            break;
                        }
                    } else if ("FILE".equals(type)) {
                        try {
                            String filename = in.readUTF();
                            long length = in.readLong();
                            File outFile = new File("received_" + System.currentTimeMillis() + "_" + filename);
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buffer = new byte[4096];
                                long remaining = length;
                                while (remaining > 0) {
                                    int toRead = (int) Math.min(buffer.length, remaining);
                                    int r = in.read(buffer, 0, toRead);
                                    if (r == -1) throw new EOFException("Unexpected EOF while reading file");
                                    fos.write(buffer, 0, r);
                                    remaining -= r;
                                }
                                fos.flush();
                            }
                            if (onFile != null) onFile.accept(filename, outFile);
                        } catch (IOException e) {
                            System.out.println("Error receiving file: " + e.getMessage());
                            break;
                        }
                    } else {
                        // Unknown type: ignore or extend protocol as needed
                        System.out.println("Unknown data type received: " + type);
                    }
                }
            } catch (Exception ex) {
                // Unexpected exception from loop - log it
                System.out.println("Connection read loop terminated: " + ex.getMessage());
            } finally {
                // notify and cleanup
                try {
                    if (onDisconnect != null) onDisconnect.run();
                } catch (Exception ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
            }
        }, "Connection-Reader").start();
    }

    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
