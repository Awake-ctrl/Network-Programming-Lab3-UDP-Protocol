import socket
import threading
import struct
import random
import time
import sys

# Protocol constants
MAGIC = 0xC461
VERSION = 1
CMD_HELLO = 0
CMD_DATA = 1
CMD_ALIVE = 2
CMD_GOODBYE = 3

class UDPClient:
    def __init__(self, host, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.server_addr = (host, port)
        self.session_id = random.randint(0, 0xFFFFFFFF)
        self.seq_num = 0
        self.logical_clock = 0
        self.expected_seq = 0  #used for the server side
        self.state = "INIT"    #to know the state in the clients FSA
        self.running = True
        self.timeout = 5.0
        self.last_sent_time = 0
        self.pending_ack = False   #ack from the server
        
    def update_clock(self, received_clock=None):
        if received_clock is not None:
            self.logical_clock = max(self.logical_clock, received_clock)
        self.logical_clock += 1
        return self.logical_clock
        
    def create_message(self, command, data=b''):
        clock = self.update_clock()
        timestamp = int(time.time() * 1000)
        
        header = struct.pack('!HBBIIQQ', 
                            MAGIC, VERSION, command,
                            self.seq_num, self.session_id,
                            clock, timestamp)
        return header + data
        
    def send_message(self, command, data=b''):
        message = self.create_message(command, data)  #create the packet
        self.sock.sendto(message, self.server_addr)   #send it over UDP
        self.last_sent_time = time.time()
        self.pending_ack = True       #Expected response
        return self.seq_num
        
    def parse_message(self, data):
        if len(data) < 28:
            return None, None
            
        magic, version, command, seq_num, session_id, logical_clock, timestamp = \
            struct.unpack('!HBBIIQQ', data[:28])
            
        if magic != MAGIC or version != VERSION or session_id != self.session_id:
            return None, None
            
        return (command, seq_num, logical_clock, timestamp), data[28:]
        
    def listen_server(self):
        while self.running:
            try:
                data, _ = self.sock.recvfrom(1024)
                header, payload = self.parse_message(data)
                if not header:
                    continue
                    
                command, seq_num, logical_clock, timestamp = header
                self.pending_ack = False
                self.update_clock(logical_clock)
                
                # --- FINITE STATE MACHINE LOGIC ---
                
                if command == CMD_HELLO and self.state == "WAIT_HELLO":
                    self.state = "READY"
                    self.seq_num += 1
                    print("Connected to server")
                    
                elif command == CMD_ALIVE and self.state == "WAIT_ALIVE":
                    self.state = "READY"
                    self.seq_num += 1
                    latency = time.time() * 1000 - timestamp
                    print(f"ALIVE received, latency: {latency:.2f}ms")
                    
                elif command == CMD_GOODBYE:
                    print("Server sent GOODBYE")
                    self.running = False
                    
            except socket.timeout:
                pass
                
    def check_timeout(self):
        while self.running:
            time.sleep(0.1)
            if self.pending_ack and time.time() - self.last_sent_time > self.timeout:
                print("Timeout, no response from server")
                self.running = False
                
    def run(self):
        # Start listener thread
        listener = threading.Thread(target=self.listen_server)
        listener.daemon = True # thread will exit when the main program exits
        listener.start()
        
        # Start timeout checker
        timeout_checker = threading.Thread(target=self.check_timeout)
        timeout_checker.daemon = True
        timeout_checker.start()
        
        # Send HELLO to initiate session
        self.state = "WAIT_HELLO"
        self.send_message(CMD_HELLO)
        print(f"Session started: {hex(self.session_id)}")
        
        # Main input loop
        try:
            while self.running:
                if self.state == "READY":
                    line = sys.stdin.readline().strip()
                    if not line or line.lower() == 'q':
                        break
                        
                    self.state = "WAIT_ALIVE"
                    self.send_message(CMD_DATA, line.encode('utf-8'))
                    print(f"Sent: {line}")
                    
        except KeyboardInterrupt:
            pass
            
        # Send GOODBYE before exiting
        if self.running:
            self.send_message(CMD_GOODBYE)
            time.sleep(0.5)
            
        self.running = False
        print("eof")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python Client.py <host> <port>")
        sys.exit(1)
        
    host, port = sys.argv[1], int(sys.argv[2])
    client = UDPClient(host, port)
    client.sock.settimeout(0.5)
    client.run()