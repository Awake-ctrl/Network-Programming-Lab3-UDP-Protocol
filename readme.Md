Lab 3 Implementation - Multi-Language UDP Protocol
Name: Kallepally sai kiran

Implementation:
- Directory A: Python implementation
  - Client: Thread-based Python
  - Server: Event-driven Python (select-based)
  
- Directory B: Java implementation
  - Client: Event-driven Java (NIO-based)
  - Server: Thread-based Java

Protocol Features:
- Session management with HELLO/GOODBYE
- Sequence number tracking with lost packet detection
- Logical clocks for event ordering
- Latency measurement
- Timeout handling

To Run:
Linux/Mac: ./A/Client <host> <port> or ./B/Client <host> <port>
Linux/Mac: ./B/Server <port> or ./A/Server<port>
Windows: Use python/java commands directly on source files

All four combinations tested and working.