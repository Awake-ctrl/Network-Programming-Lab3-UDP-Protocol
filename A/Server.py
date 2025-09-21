import socket
import select
import struct
import time
import random

# Protocol constants
MAGIC = 0xC461
VERSION = 1
CMD_HELLO = 0
CMD_DATA = 1
CMD_ALIVE = 2
CMD_GOODBYE = 3

class Session:
    def __init__(self, session_id, addr, sock):
        self.session_id = session_id
        self.addr = addr
        self.sock = sock
        self.expected_seq = 0
        self.last_activity = time.time()
        self.active = True
        self.logical_clock = 0
        
    def update_clock(self, received_clock=None):
        if received_clock is not None:
            self.logical_clock = max(self.logical_clock, received_clock)
        self.logical_clock += 1
        return self.logical_clock
        
    def create_message(self, command, seq_num, data=b''):
        clock = self.update_clock()
        timestamp = int(time.time() * 1000)
        
        header = struct.pack('!HBBIIQQ', 
                            MAGIC, VERSION, command,
                            seq_num, self.session_id,
                            clock, timestamp)
        return header + data
        
    def send_message(self, command, seq_num, data=b''):
        message = self.create_message(command, seq_num, data)
        self.sock.sendto(message, self.addr)
        
    def handle_hello(self, header):
        if self.expected_seq != 0:
            print(f"{hex(self.session_id)} Protocol error: HELLO in wrong state")
            self.send_message(CMD_GOODBYE, self.expected_seq)
            self.active = False
            return
            
        print(f"{hex(self.session_id)} [0] Session created")
        self.update_clock(header[2])  # logical_clock
        self.send_message(CMD_HELLO, self.expected_seq)
        self.expected_seq += 1
        
    def handle_data(self, header, data):
        command, seq_num, logical_clock, timestamp = header
        
        if seq_num < self.expected_seq:
            print(f"{hex(self.session_id)} [{seq_num}] duplicate packet")
            return
            
        # Check for lost packets
        while self.expected_seq < seq_num:
            print(f"{hex(self.session_id)} [{self.expected_seq}] Lost packet!")
            self.expected_seq += 1
            
        # Process this packet
        text = data.decode('utf-8', errors='ignore').strip()
        if text:
            print(f"{hex(self.session_id)} [{seq_num}] {text}")
            
        self.update_clock(logical_clock)
        self.send_message(CMD_ALIVE, self.expected_seq)
        self.expected_seq += 1
        self.last_activity = time.time()
        
        # Calculate latency
        latency = time.time() * 1000 - timestamp
        # print(f"Latency: {latency:.2f}ms")
        
    def handle_goodbye(self, header):
        print(f"{hex(self.session_id)} [{header[1]}] GOODBYE from client.")
        self.update_clock(header[2])
        self.send_message(CMD_GOODBYE, self.expected_seq)
        print(f"{hex(self.session_id)} Session closed")
        self.active = False
        
    def check_timeout(self):
        if time.time() - self.last_activity > 10 and self.active:
            print(f"{hex(self.session_id)} Session timeout")
            self.send_message(CMD_GOODBYE, self.expected_seq)
            self.active = False
            return True
        return False

class UDPServer:
    def __init__(self, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(('localhost', port))
        self.sock.setblocking(False)
        self.sessions = {}
        self.running = True
        
    def parse_message(self, data):
        if len(data) < 28:
            return None, None
            
        magic, version, command, seq_num, session_id, logical_clock, timestamp = \
            struct.unpack('!HBBIIQQ', data[:28])
            
        if magic != MAGIC or version != VERSION:
            return None, None
            
        return (command, seq_num, session_id, logical_clock, timestamp), data[28:]
        
    def handle_packet(self, data, addr):
        header, payload = self.parse_message(data)
        if not header:
            return
            
        command, seq_num, session_id, logical_clock, timestamp = header
        
        # Find or create session
        if session_id not in self.sessions:
            if command != CMD_HELLO:
                return  # Only HELLO can start a session
            self.sessions[session_id] = Session(session_id, addr, self.sock)
            
        session = self.sessions[session_id]
        
        # Handle message based on type
        if command == CMD_HELLO:
            session.handle_hello((command, seq_num, logical_clock, timestamp))
        elif command == CMD_DATA:
            session.handle_data((command, seq_num, logical_clock, timestamp), payload)
        elif command == CMD_GOODBYE:
            session.handle_goodbye((command, seq_num, logical_clock, timestamp))
            del self.sessions[session_id]
            
    def run(self):
        print(f"Waiting on port {self.sock.getsockname()[1]}...")
        inputs = [self.sock]
        
        while self.running:
            # Check for timeout sessions
            for session_id in list(self.sessions.keys()):
                session = self.sessions[session_id]
                if session.check_timeout():
                    del self.sessions[session_id]
            
            # Wait for socket activity
            readable, _, _ = select.select(inputs, [], [], 1.0)
            
            for s in readable:
                if s is self.sock:
                    try:
                        data, addr = self.sock.recvfrom(1024)
                        self.handle_packet(data, addr)
                    except BlockingIOError:
                        pass
                    except:
                        self.running = False
                        
        # Cleanup
        for session_id in list(self.sessions.keys()):
            session = self.sessions[session_id]
            session.send_message(CMD_GOODBYE, session.expected_seq)
            del self.sessions[session_id]

if __name__ == "__main__":
    import sys
    if len(sys.argv) != 2:
        print("Usage: python Server.py <port>")
        sys.exit(1)
        
    port = int(sys.argv[1])
    server = UDPServer(port)
    server.run()