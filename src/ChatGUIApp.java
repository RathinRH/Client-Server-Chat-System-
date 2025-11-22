import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;

/**
 * ChatGUIApp.java
 * Single-file GUI for server + client. Use command-line args to run as server or client,
 * or run without args to pick via chooser.
 *
 * Compile:
 * javac ChatGUIApp.java
 * Run server:
 * java ChatGUIApp server 5000
 * Run client:
 * java ChatGUIApp client localhost 5000
 */
public class ChatGUIApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            if (args.length >= 1 && "server".equalsIgnoreCase(args[0])) {
                int port = 5000;
                if (args.length >= 2) try { port = Integer.parseInt(args[1]); } catch (Exception ignored) {}
                new ChatServerGUI(port);
            } else if (args.length >= 1 && "client".equalsIgnoreCase(args[0])) {
                String host = "localhost";
                int port = 5000;
                if (args.length >= 2) host = args[1];
                if (args.length >= 3) try { port = Integer.parseInt(args[2]); } catch (Exception ignored) {}
                new ChatClientGUI(host, port);
            } else {
                ModeChooser chooser = new ModeChooser();
                chooser.setVisible(true);
            }
        });
    }

    // Mode chooser
    static class ModeChooser extends JFrame {
        ModeChooser() {
            super("Chat - Choose Mode");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(360, 200);
            setLocationRelativeTo(null);
            setLayout(new BorderLayout(10,10));
            JPanel p = new JPanel();
            p.setBorder(new EmptyBorder(12,12,12,12));
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            JLabel label = new JLabel("Start as Server or Client");
            label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            p.add(label);
            p.add(Box.createVerticalStrut(12));
            JButton serverBtn = new JButton("Start Server");
            JButton clientBtn = new JButton("Start Client");
            serverBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            clientBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            p.add(serverBtn);
            p.add(Box.createVerticalStrut(8));
            p.add(clientBtn);
            add(p, BorderLayout.CENTER);

            serverBtn.addActionListener(e -> {
                dispose();
                new ChatServerGUI(5000);
            });
            clientBtn.addActionListener(e -> {
                dispose();
                new ChatClientGUI("localhost", 5000);
            });
            setVisible(true);
        }
    }

    // Connection helper (same protocol MSG/FILE)
    static class Connection {
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private AtomicBoolean closed = new AtomicBoolean(false);

        public Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        public synchronized void sendMessage(String message) throws IOException {
            if (closed.get()) throw new IOException("Connection closed");
            out.writeUTF("MSG");
            out.writeUTF(message);
            out.flush();
        }

        public synchronized void sendFile(File file, ProgressCallback cb) throws IOException {
            if (closed.get()) throw new IOException("Connection closed");
            out.writeUTF("FILE");
            out.writeUTF(file.getName());
            out.writeLong(file.length());
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8 * 1024];
                long total = file.length();
                long sent = 0;
                int r;
                while ((r = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, r);
                    sent += r;
                    if (cb != null) cb.onProgress(sent, total);
                }
            }
            out.flush();
        }

        public void readLoop(MessageHandler handler) {
            new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        String type;
                        try { type = in.readUTF(); } catch (EOFException eof) { break; }
                        if ("MSG".equals(type)) {
                            String msg = in.readUTF();
                            if (handler != null) handler.onMessage(msg);
                        } else if ("FILE".equals(type)) {
                            String filename = in.readUTF();
                            long length = in.readLong();
                            File outFile = createUniqueFile(filename);
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] buffer = new byte[8 * 1024];
                                long remaining = length;
                                while (remaining > 0) {
                                    int toRead = (int) Math.min(buffer.length, remaining);
                                    int r = in.read(buffer, 0, toRead);
                                    if (r == -1) throw new EOFException("Unexpected EOF while reading file");
                                    fos.write(buffer, 0, r);
                                    remaining -= r;
                                }
                            }
                            if (handler != null) handler.onFileReceived(filename, outFile);
                        } else {
                            // ignore unknown
                        }
                    }
                } catch (Exception e) {
                    if (handler != null) handler.onError(e);
                } finally {
                    if (handler != null) handler.onDisconnect();
                    close();
                }
            }, "Connection-Reader").start();
        }

        private File createUniqueFile(String filename) {
            File dir = new File("received_files");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "received_" + System.currentTimeMillis() + "_" + filename);
        }

        public void close() {
            if (!closed.getAndSet(true)) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        public boolean isClosed() { return closed.get(); }
    }

    interface MessageHandler {
        void onMessage(String message);
        void onFileReceived(String filename, File saved);
        void onDisconnect();
        void onError(Exception ex);
    }

    interface ProgressCallback { void onProgress(long sent, long total); }

    // Re-usable chat panel with received-files area
    static class ChatPanel extends JPanel {
        protected JTextPane chatArea = new JTextPane();
        protected JTextField inputField = new JTextField();
        protected JButton sendBtn = new JButton("Send");
        protected JButton fileBtn = new JButton("Send File");
        protected JLabel statusLabel = new JLabel("Not connected");
        protected JProgressBar progressBar = new JProgressBar();

        // file list UI
        protected JPanel fileListPanel = new JPanel();
        protected JScrollPane fileListScroll;

        ChatPanel() {
            setLayout(new BorderLayout(8,8));
            setBorder(new EmptyBorder(10,10,10,10));

            // Top
            JPanel top = new JPanel(new BorderLayout(8,8));
            JLabel title = new JLabel("Chat");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
            top.add(title, BorderLayout.WEST);
            top.add(statusLabel, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            // Chat area
            chatArea.setEditable(false);
            chatArea.setBackground(Color.WHITE);
            chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            JScrollPane chatScroll = new JScrollPane(chatArea);
            chatScroll.setPreferredSize(new Dimension(520, 360));

            // File list (right)
            fileListPanel.setLayout(new BoxLayout(fileListPanel, BoxLayout.Y_AXIS));
            fileListPanel.setBorder(BorderFactory.createTitledBorder("Received Files"));
            fileListScroll = new JScrollPane(fileListPanel);
            fileListScroll.setPreferredSize(new Dimension(180, 360));

            JPanel centerWrap = new JPanel(new BorderLayout());
            centerWrap.add(chatScroll, BorderLayout.CENTER);
            centerWrap.add(fileListScroll, BorderLayout.EAST);
            add(centerWrap, BorderLayout.CENTER);

            // Bottom
            JPanel bottom = new JPanel(new BorderLayout(8,8));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6,6));
            buttons.add(fileBtn);
            buttons.add(sendBtn);
            bottom.add(inputField, BorderLayout.CENTER);
            bottom.add(buttons, BorderLayout.EAST);

            JPanel bottomWrap = new JPanel(new BorderLayout(6,6));
            bottomWrap.add(bottom, BorderLayout.NORTH);
            progressBar.setStringPainted(true);
            progressBar.setVisible(false);
            bottomWrap.add(progressBar, BorderLayout.SOUTH);

            add(bottomWrap, BorderLayout.SOUTH);
        }

        protected void appendMessage(String who, String text, boolean isOwn) {
            SwingUtilities.invokeLater(() -> {
                StyledDocument doc = chatArea.getStyledDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setFontFamily(attrs, Font.SANS_SERIF);
                StyleConstants.setFontSize(attrs, 14);
                StyleConstants.setSpaceAbove(attrs, 4);
                StyleConstants.setSpaceBelow(attrs, 4);
                if (isOwn) {
                    StyleConstants.setForeground(attrs, Color.DARK_GRAY);
                    try { doc.insertString(doc.getLength(), "You: ", attrs); } catch (BadLocationException ignored) {}
                } else {
                    StyleConstants.setForeground(attrs, Color.BLUE.darker());
                    try { doc.insertString(doc.getLength(), who + ": ", attrs); } catch (BadLocationException ignored) {}
                }
                SimpleAttributeSet msgAttr = new SimpleAttributeSet();
                StyleConstants.setFontFamily(msgAttr, Font.SANS_SERIF);
                StyleConstants.setFontSize(msgAttr, 14);
                StyleConstants.setForeground(msgAttr, Color.BLACK);
                try { doc.insertString(doc.getLength(), text + "\n", msgAttr); } catch (BadLocationException ignored) {}
                chatArea.setCaretPosition(doc.getLength());
            });
        }

        protected void setStatus(String s) { SwingUtilities.invokeLater(() -> statusLabel.setText(s)); }
        protected void showProgress(boolean show) { SwingUtilities.invokeLater(() -> progressBar.setVisible(show)); }
        protected void setProgress(int val, int max) {
            SwingUtilities.invokeLater(() -> { progressBar.setMaximum(max); progressBar.setValue(val); });
        }

        // add an entry in the Received Files list with an Open button
        protected void addReceivedFileEntry(File savedFile) {
            SwingUtilities.invokeLater(() -> {
                JPanel row = new JPanel(new BorderLayout(6,6));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
                JLabel nameLabel = new JLabel(savedFile.getName());
                nameLabel.setBorder(new EmptyBorder(3,3,3,3));
                JButton openBtn = new JButton("Open");
                openBtn.setFocusable(false);
                openBtn.addActionListener(e -> {
                    try {
                        if (!savedFile.exists()) {
                            JOptionPane.showMessageDialog(this, "File not found: " + savedFile.getAbsolutePath(),
                                    "Open error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        if (!Desktop.isDesktopSupported()) {
                            JOptionPane.showMessageDialog(this, "Open not supported on this platform",
                                    "Open error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                        Desktop.getDesktop().open(savedFile);
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(this, "Unable to open file: " + ex.getMessage(),
                                "Open error", JOptionPane.ERROR_MESSAGE);
                    }
                });

                row.add(nameLabel, BorderLayout.CENTER);
                row.add(openBtn, BorderLayout.EAST);
                fileListPanel.add(row);
                fileListPanel.add(Box.createVerticalStrut(6));
                fileListPanel.revalidate();
                fileListPanel.repaint();

                // auto-scroll file list to bottom
                SwingUtilities.invokeLater(() -> {
                    JViewport vp = fileListScroll.getViewport();
                    vp.setViewPosition(new Point(0, Math.max(0, fileListPanel.getHeight() - fileListScroll.getHeight())));
                });
            });
        }
    }

    // Server GUI
    static class ChatServerGUI extends JFrame {
        private ChatPanel chatPanel = new ChatPanel();
        private ServerSocket serverSocket;
        private Connection connection;
        private volatile boolean running = true;

        ChatServerGUI(int port) {
            super("Chat Server - port " + port);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(900, 560);
            setLocationRelativeTo(null);
            setLayout(new BorderLayout());
            add(chatPanel, BorderLayout.CENTER);
            chatPanel.setStatus("Server: starting...");
            setVisible(true);

            // ensure server stops on close
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    running = false;
                    try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
                    if (connection != null) connection.close();
                }
            });

            // wire buttons
            chatPanel.sendBtn.addActionListener(this::onSendMessage);
            chatPanel.fileBtn.addActionListener(this::onSendFile);
            chatPanel.inputField.addActionListener(this::onSendMessage);

            new Thread(() -> startServer(port), "Server-Accept-Thread").start();
        }

        private void startServer(int port) {
            try {
                serverSocket = new ServerSocket(port);
                chatPanel.setStatus("Server listening on port " + port + " — waiting for client...");
                while (running) {
                    Socket client = serverSocket.accept();
                    chatPanel.setStatus("Client connected: " + client.getRemoteSocketAddress());
                    connection = new Connection(client);
                    connection.readLoop(new MessageHandler() {
                        public void onMessage(String message) { chatPanel.appendMessage("Client", message, false); }
                        public void onFileReceived(String filename, File saved) {
                            chatPanel.appendMessage("Client", "sent file: " + filename + " (saved: " + saved.getName() + ")", false);
                            chatPanel.addReceivedFileEntry(saved);
                        }
                        public void onDisconnect() { chatPanel.setStatus("Client disconnected. Waiting for next client..."); }
                        public void onError(Exception ex) { chatPanel.appendMessage("System", "Connection error: " + ex.getMessage(), false); }
                    });
                    // block here until connection is closed, then continue to accept next
                    while (connection != null && !connection.isClosed()) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    }
                }
            } catch (IOException ex) {
                chatPanel.appendMessage("System", "Server error: " + ex.getMessage(), false);
                chatPanel.setStatus("Stopped");
            }
        }

        private void onSendMessage(ActionEvent e) {
            if (connection == null || connection.isClosed()) { chatPanel.appendMessage("System", "No client connected.", false); return; }
            String t = chatPanel.inputField.getText().trim();
            if (t.isEmpty()) return;
            try {
                connection.sendMessage(t);
                chatPanel.appendMessage("You", t, true);
                chatPanel.inputField.setText("");
            } catch (IOException ex) {
                chatPanel.appendMessage("System", "Send failed: " + ex.getMessage(), false);
            }
        }

        private void onSendFile(ActionEvent e) {
            if (connection == null || connection.isClosed()) { chatPanel.appendMessage("System", "No client connected.", false); return; }
            JFileChooser chooser = new JFileChooser();
            int ret = chooser.showOpenDialog(this);
            if (ret != JFileChooser.APPROVE_OPTION) return;
            File f = chooser.getSelectedFile();
            chatPanel.showProgress(true);
            chatPanel.setProgress(0, 100);
            new Thread(() -> {
                try {
                    connection.sendFile(f, (sent, total) -> {
                        int p = (int) ((sent * 100) / Math.max(1, total));
                        chatPanel.setProgress(p, 100);
                    });
                    chatPanel.appendMessage("You", "sent file: " + f.getName(), true);
                } catch (IOException ex) {
                    chatPanel.appendMessage("System", "File send failed: " + ex.getMessage(), false);
                } finally {
                    chatPanel.showProgress(false);
                }
            }).start();
        }
    }

    // Client GUI
    static class ChatClientGUI extends JFrame {
        private ChatPanel chatPanel = new ChatPanel();
        private Connection connection;

        ChatClientGUI(String host, int port) {
            super("Chat Client - " + host + ":" + port);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setSize(900, 560);
            setLocationRelativeTo(null);
            setLayout(new BorderLayout());

            add(chatPanel, BorderLayout.CENTER);
            chatPanel.setStatus("Not connected");

            // top controls: allow changing host/port and reconnect
            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8,8));
            topBar.add(new JLabel("Host:"));
            JTextField hostField = new JTextField(host, 12); topBar.add(hostField);
            topBar.add(new JLabel("Port:"));
            JTextField portField = new JTextField(String.valueOf(port), 6); topBar.add(portField);
            topBar.add(new JLabel("Name:"));
            JTextField nameField = new JTextField("Me", 8); topBar.add(nameField);
            JButton connectBtn = new JButton("Connect"); topBar.add(connectBtn);
            add(topBar, BorderLayout.NORTH);

            connectBtn.addActionListener(e -> {
                String h = hostField.getText().trim();
                int p = Integer.parseInt(portField.getText().trim());
                connect(h, p);
            });

            chatPanel.sendBtn.addActionListener(e -> sendMessage(nameField.getText()));
            chatPanel.inputField.addActionListener(e -> sendMessage(nameField.getText()));
            chatPanel.fileBtn.addActionListener(e -> sendFile(nameField.getText()));

            connect(host, port);
            setVisible(true);

            // cleanup on close
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    if (connection != null) connection.close();
                }
            });
        }

        private void connect(String host, int port) {
            if (connection != null && !connection.isClosed()) { chatPanel.appendMessage("System", "Already connected", false); return; }
            new Thread(() -> {
                try {
                    Socket socket = new Socket(host, port);
                    connection = new Connection(socket);
                    chatPanel.setStatus("Connected to " + host + ":" + port);
                    connection.readLoop(new MessageHandler() {
                        public void onMessage(String message) { chatPanel.appendMessage("Server", message, false); }
                        public void onFileReceived(String filename, File saved) {
                            chatPanel.appendMessage("Server", "sent file: " + filename + " (saved: " + saved.getName() + ")", false);
                            chatPanel.addReceivedFileEntry(saved);
                        }
                        public void onDisconnect() { chatPanel.setStatus("Disconnected"); }
                        public void onError(Exception ex) { chatPanel.appendMessage("System", "Connection error: " + ex.getMessage(), false); }
                    });
                } catch (IOException ex) {
                    chatPanel.appendMessage("System", "Unable to connect: " + ex.getMessage(), false);
                    chatPanel.setStatus("Not connected");
                }
            }).start();
        }

        private void sendMessage(String name) {
            if (connection == null || connection.isClosed()) { chatPanel.appendMessage("System", "Not connected", false); return; }
            String t = chatPanel.inputField.getText().trim();
            if (t.isEmpty()) return;
            try {
                connection.sendMessage(name + ": " + t);
                chatPanel.appendMessage("You", t, true);
                chatPanel.inputField.setText("");
            } catch (IOException ex) {
                chatPanel.appendMessage("System", "Send failed: " + ex.getMessage(), false);
            }
        }

        private void sendFile(String name) {
            if (connection == null || connection.isClosed()) { chatPanel.appendMessage("System", "Not connected", false); return; }
            JFileChooser chooser = new JFileChooser();
            int ret = chooser.showOpenDialog(this);
            if (ret != JFileChooser.APPROVE_OPTION) return;
            File f = chooser.getSelectedFile();
            chatPanel.showProgress(true);
            chatPanel.setProgress(0, 100);
            new Thread(() -> {
                try {
                    connection.sendFile(f, (sent, total) -> {
                        int p = (int) ((sent * 100) / Math.max(1, total));
                        chatPanel.setProgress(p, 100);
                    });
                    chatPanel.appendMessage("You", "sent file: " + f.getName(), true);
                } catch (IOException ex) {
                    chatPanel.appendMessage("System", "File send failed: " + ex.getMessage(), false);
                } finally {
                    chatPanel.showProgress(false);
                }
            }).start();
        }
    }
}
