import java.net.*;
import java.util.*;
import java.nio.*;

public class Server {
    private DatagramSocket socket;
    private Map<Integer, Session> sessions;
    private boolean running;

    private static final short MAGIC = (short) 0xC461;
    private static final byte VERSION = 1;
    private static final byte CMD_HELLO = 0;
    private static final byte CMD_DATA = 1;
    private static final byte CMD_ALIVE = 2;
    private static final byte CMD_GOODBYE = 3;

    class Session {
        int sessionId;
        InetSocketAddress address;
        int expectedSeq;
        long lastActivity;
        boolean active;
        long logicalClock;

        Session(int sessionId, InetSocketAddress address) {
            this.sessionId = sessionId;
            this.address = address;
            this.expectedSeq = 0;
            this.lastActivity = System.currentTimeMillis();
            this.active = true;
            this.logicalClock = 0;
        }

        long updateClock(Long receivedClock) {
            if (receivedClock != null) {
                this.logicalClock = Math.max(this.logicalClock, receivedClock);
            }
            this.logicalClock++;
            return this.logicalClock;
        }

        ByteBuffer createMessage(byte command, int seqNum, byte[] data) {
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

        void sendMessage(byte command, int seqNum, byte[] data) throws Exception {
            ByteBuffer message = createMessage(command, seqNum, data);
            byte[] array = new byte[message.remaining()];
            message.get(array);
            DatagramPacket packet = new DatagramPacket(array, array.length, address);
            socket.send(packet);
        }

        void handleHello(Object[] header) throws Exception {
            if (expectedSeq != 0) {
                // System.out.println(Integer.toHexString(sessionId) + " Protocol error: HELLO in wrong state");
                sendMessage(CMD_GOODBYE, expectedSeq, null);
                active = false;
                return;
            }

            System.out.println(Integer.toHexString(sessionId) + " [0] Session created");
            updateClock((Long) header[2]);
            sendMessage(CMD_HELLO, expectedSeq, null);
            expectedSeq++;
        }

        void handleData(Object[] header, byte[] data) throws Exception {
            byte command = (Byte) header[0];
            int seqNum = (Integer) header[1];
            long logicalClock = (Long) header[2];
            long timestamp = (Long) header[3];

            if (seqNum < expectedSeq) {
                System.out.println(Integer.toHexString(sessionId) + " [" + seqNum + "] duplicate packet");
                return;
            }

            // Check for lost packets
            while (expectedSeq < seqNum) {
                System.out.println(Integer.toHexString(sessionId) + " [" + expectedSeq + "] Lost packet!");
                expectedSeq++;
            }

            // Process this packet
            String text = new String(data, "UTF-8").trim();
            
            System.out.println(Integer.toHexString(sessionId) + " [" + seqNum + "] " + text);
            

            updateClock(logicalClock);
            sendMessage(CMD_ALIVE, expectedSeq, null);
            expectedSeq++;
            lastActivity = System.currentTimeMillis();

            // Calculate latency
            long latency = System.currentTimeMillis() - timestamp;
            // System.out.println("Latency: " + latency + "ms");
        }

        void handleGoodbye(Object[] header) throws Exception {
            System.out.println(Integer.toHexString(sessionId) + " [" + header[1] + "] GOODBYE from client.");
            updateClock((Long) header[2]);
            sendMessage(CMD_GOODBYE, expectedSeq, null);
            System.out.println(Integer.toHexString(sessionId) + " Session closed");
            active = false;
        }

        boolean checkTimeout() throws Exception {
            if (System.currentTimeMillis() - lastActivity > 10000) {
                System.out.println(Integer.toHexString(sessionId) + " Session timeout");
                sendMessage(CMD_GOODBYE, expectedSeq, null);
                active = false;
                return true;
            }
            return false;
        }
    }

    public Server(int port) throws Exception {
        this.socket = new DatagramSocket(port);
        this.sessions = new HashMap<>();
        this.running = true;
    }

    private Object[] parseMessage(byte[] data) {
        if (data.length < 28) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        short magic = buffer.getShort();
        byte version = buffer.get();
        byte command = buffer.get();
        int seqNum = buffer.getInt();
        int sessionId = buffer.getInt();
        long logicalClock = buffer.getLong();
        long timestamp = buffer.getLong();

        if (magic != MAGIC || version != VERSION) {
            return null;
        }

        byte[] payload = null;
        if (buffer.remaining() > 0) {
            payload = new byte[buffer.remaining()];
            buffer.get(payload);
        }

        return new Object[] { command, seqNum, sessionId, logicalClock, timestamp, payload };
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            Object[] result = parseMessage(packet.getData());
            if (result == null)
                return;

            byte command = (Byte) result[0];
            int seqNum = (Integer) result[1];
            int sessionId = (Integer) result[2];
            long logicalClock = (Long) result[3];
            long timestamp = (Long) result[4];
            byte[] payload = (byte[]) result[5];

            InetSocketAddress address = new InetSocketAddress(
                    packet.getAddress(), packet.getPort());

            // Find or create session
            if (!sessions.containsKey(sessionId)) {
                if (command != CMD_HELLO) {
                    return; // Only HELLO can start a session
                }
                sessions.put(sessionId, new Session(sessionId, address));
            }

            Session session = sessions.get(sessionId);
            Object[] header = new Object[] { command, seqNum, logicalClock, timestamp };

            // Handle message based on type
            if (command == CMD_HELLO) {
                session.handleHello(header);
            } else if (command == CMD_DATA) {
                session.handleData(header, payload);
            } else if (command == CMD_ALIVE) {
                session.updateClock(logicalClock);
                session.lastActivity = System.currentTimeMillis();
            } else if (command == CMD_GOODBYE) {
                session.handleGoodbye(header);
                sessions.remove(sessionId);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void run() {
        System.out.println("Waiting on port " + socket.getLocalPort() + "...");

        while (running) {
            try {
                // Check for timeout sessions
                for (Iterator<Map.Entry<Integer, Session>> it = sessions.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<Integer, Session> entry = it.next();
                    if (entry.getValue().checkTimeout()) {
                        it.remove();
                    }
                }

                // Receive packets
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // Handle packet in new thread
                new Thread(() -> handlePacket(packet)).start();

            } catch (Exception e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }

        // Cleanup
        for (Session session : sessions.values()) {
            try {
                session.sendMessage(CMD_GOODBYE, session.expectedSeq, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        sessions.clear();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        Server server = new Server(port);
        server.run();
    }
}