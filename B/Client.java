import java.nio.*;
import java.nio.channels.*;
import java.net.*;
import java.util.*;
import java.io.*;

public class Client {
    private DatagramChannel channel;
    private InetSocketAddress serverAddress;
    private int sessionId;
    private int seqNum;
    private long logicalClock;
    private int expectedSeq;
    private String state;
    private boolean running;
    private long timeout;
    private long lastSentTime;
    private boolean pendingAck;
    private boolean isInteractive;
    private boolean shouldSendGoodbye;

    private static final short MAGIC = (short) 0xC461;
    private static final byte VERSION = 1;
    private static final byte CMD_HELLO = 0;
    private static final byte CMD_DATA = 1;
    private static final byte CMD_ALIVE = 2;
    private static final byte CMD_GOODBYE = 3;

    public Client(String host, int port) throws Exception {
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.serverAddress = new InetSocketAddress(host, port);
        this.sessionId = new Random().nextInt();
        this.seqNum = 0;
        this.logicalClock = 0;
        this.expectedSeq = 0;
        this.state = "INIT";
        this.running = true;
        this.timeout = 5000;
        this.lastSentTime = 0;
        this.pendingAck = false;
        this.isInteractive = System.console() != null;
        this.shouldSendGoodbye = true;
    }

    private long updateClock(Long receivedClock) {
        if (receivedClock != null) {
            this.logicalClock = Math.max(this.logicalClock, receivedClock);
        }
        this.logicalClock++;
        return this.logicalClock;
    }

    private ByteBuffer createMessage(byte command, byte[] data) {
        updateClock(null);
        long timestamp = System.currentTimeMillis();

        int dataLength = (data != null) ? data.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(28 + dataLength);

        buffer.putShort(MAGIC);
        buffer.put(VERSION);
        buffer.put(command);
        buffer.putInt(seqNum);
        buffer.putInt(sessionId);
        buffer.putLong(logicalClock);
        buffer.putLong(timestamp);

        if (data != null) {
            buffer.put(data);
        }

        buffer.flip();
        return buffer;
    }

    private void sendMessage(byte command, byte[] data) throws Exception {
        ByteBuffer message = createMessage(command, data);
        channel.send(message, serverAddress);
        lastSentTime = System.currentTimeMillis();
        pendingAck = true;
        seqNum++; // Increment sequence number after sending
    }

    private Object[] parseMessage(ByteBuffer buffer) {
        if (buffer.remaining() < 28) {
            return null;
        }

        short magic = buffer.getShort();
        byte version = buffer.get();
        byte command = buffer.get();
        int seqNum = buffer.getInt();
        int sessionId = buffer.getInt();
        long logicalClock = buffer.getLong();
        long timestamp = buffer.getLong();

        if (magic != MAGIC || version != VERSION || sessionId != this.sessionId) {
            return null;
        }

        byte[] payload = null;
        if (buffer.hasRemaining()) {
            payload = new byte[buffer.remaining()];
            buffer.get(payload);
        }

        return new Object[] { command, seqNum, logicalClock, timestamp, payload };
    }

    private void processInputLine(String line) throws Exception {
        if (line == null ) {
            return;
        }

        if (line.equalsIgnoreCase("q")) {
            // System.out.println("Quitting...");
            shouldSendGoodbye = true;
            running = false;
            return;
        }

        if (state.equals("READY")) {
            state = "WAIT_ALIVE";
            sendMessage(CMD_DATA, line.getBytes("UTF-8"));
            // System.out.println("Sent: " + line);

            // If reading from file, add a small delay to avoid overwhelming
            if (!isInteractive) {
                Thread.sleep(10);
            }
        }
    }

    private void shutdown() throws Exception {
        if (shouldSendGoodbye && state.equals("READY")) {
            // System.out.println("Sending GOODBYE to server...");
            sendMessage(CMD_GOODBYE, null);

            // Wait briefly for response with timeout
            long goodbyeTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - goodbyeTime < 1000) {
                ByteBuffer buffer = ByteBuffer.allocate(1024);

                // Check for GOODBYE response with short timeout
                try {
                    channel.configureBlocking(false);
                    if (channel.receive(buffer) != null) {
                        buffer.flip();
                        Object[] result = parseMessage(buffer);
                        if (result != null) {
                            byte command = (Byte) result[0];
                            if (command == CMD_GOODBYE) {
                                // System.out.println("Received GOODBYE confirmation from server");
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore, we're shutting down anyway
                }
                Thread.sleep(100);
            }
        }

        channel.close();
        // System.out.println("Session terminated: " + Integer.toHexString(sessionId));
        System.out.println("eof");
    }

public void run() throws Exception {
    try {
        // Send HELLO to initiate session
        state = "WAIT_HELLO";
        sendMessage(CMD_HELLO, null);
        // System.out.println("Session started: " + Integer.toHexString(sessionId));

        Scanner scanner = new Scanner(System.in);
        Selector selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);

        long startTime = System.currentTimeMillis();
        long lastActivityTime = System.currentTimeMillis();
        
        // Wait for HELLO response from server before processing any input
        boolean helloAcknowledged = false;
        long helloWaitStart = System.currentTimeMillis();
        
        while (!helloAcknowledged && running) {
            // Check timeout for HELLO response
            if (System.currentTimeMillis() - helloWaitStart > timeout) {
                System.out.println("Timeout waiting for server response");
                shouldSendGoodbye = false;
                running = false;
                break;
            }
            
            // Check for network activity
            if (selector.select(100) > 0) {
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isReadable()) {
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        SocketAddress sender = channel.receive(buffer);
                        if (sender != null) {
                            buffer.flip();
                            lastActivityTime = System.currentTimeMillis();

                            Object[] result = parseMessage(buffer);
                            if (result == null) continue;

                            byte command = (Byte) result[0];
                            int seqNum = (Integer) result[1];
                            long logicalClock = (Long) result[2];
                            long timestamp = (Long) result[3];

                            pendingAck = false;
                            updateClock(logicalClock);

                            if (command == CMD_HELLO && state.equals("WAIT_HELLO")) {
                                state = "READY";
                                helloAcknowledged = true;
                                // System.out.println("Connected to server");
                                break;
                            }
                        }
                    }
                }
                selector.selectedKeys().clear();
            }
            
            // Small sleep to prevent CPU spinning
            Thread.sleep(10);
        }

        // NOW process user input after connection is established
        while (running) {
            // Check timeout for pending acknowledgements
            if (pendingAck && System.currentTimeMillis() - lastSentTime > timeout) {
                System.out.println("Timeout, no response from server");
                shouldSendGoodbye = false;
                break;
            }

            // Check for user input - only now that we're connected
            if (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                lastActivityTime = System.currentTimeMillis();
                processInputLine(line);
            }

            // Check for network activity
            if (selector.select(100) > 0) {
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isReadable()) {
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        SocketAddress sender = channel.receive(buffer);
                        if (sender != null) {
                            buffer.flip();
                            lastActivityTime = System.currentTimeMillis();

                            Object[] result = parseMessage(buffer);
                            if (result == null) continue;

                            byte command = (Byte) result[0];
                            int seqNum = (Integer) result[1];
                            long logicalClock = (Long) result[2];
                            long timestamp = (Long) result[3];

                            pendingAck = false;
                            updateClock(logicalClock);

                            if (command == CMD_ALIVE && state.equals("WAIT_ALIVE")) {
                                state = "READY";
                                long latency = System.currentTimeMillis() - timestamp;
                                // System.out.println("ALIVE received, latency: " + latency + "ms");
                            } else if (command == CMD_GOODBYE) {
                                // System.out.println("Server sent GOODBYE");
                                shouldSendGoodbye = false;
                                running = false;
                            }
                        }
                    }
                }
                selector.selectedKeys().clear();
            }

            // If reading from file and no more input, check if we should exit
            if (!isInteractive && !scanner.hasNextLine()) {
                if (System.currentTimeMillis() - lastActivityTime > 1000 && !pendingAck) {
                    break;
                }
            }

            Thread.sleep(10);
        }

        shutdown();

    } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
        e.printStackTrace();
    }
}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java Client <host> <port>");
            // System.out.println("Input can be provided via stdin (interactive or file redirection)");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        Client client = new Client(host, port);
        client.run();
    }
}