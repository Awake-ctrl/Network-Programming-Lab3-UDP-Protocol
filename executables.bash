# Create Python executables
echo '#!/bin/bash
cd "$(dirname "$0")"
python3 Client.py "$@"' > A/Client

echo '#!/bin/bash
cd "$(dirname "$0")"
python3 Server.py "$@"' > A/Server

# Create Java executables
echo '#!/bin/bash
cd "$(dirname "$0")"
javac Client.java
java Client "$@"' > B/Client

echo '#!/bin/bash
cd "$(dirname "$0")"
javac Server.java
java Server "$@"' > B/Server

# Make executable
chmod +x A/Client A/Server B/Client B/Server