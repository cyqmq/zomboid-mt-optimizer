#!/bin/bash
#
# Run the Project Zomboid built-in FakeClientManager (headless console client).
# It connects a bot player to a local server, which triggers cell loading,
# zombie spawning, and lets you exercise the ZMTOpt agent hooks.
#
# Usage:
#   ./run-fake-client.sh [server_dir] [scenario_file] [loglevel]
#
#   server_dir     - PZ dedicated server root (default: /tmp/pzserver)
#   scenario_file  - path to scenario JSON (default: ./scenario.json)
#   loglevel       - 0..3 (default: 1)
#
# The bot connects as "Client0" to 127.0.0.1:16261 (edit scenario.json).

set -euo pipefail

SERVER_DIR="${1:-/tmp/pzserver}"
SCENARIO="${2:-$(dirname "$0")/scenario.json}"
LOG_LEVEL="${3:-1}"

if [ ! -d "$SERVER_DIR" ]; then
    echo "Server directory not found: $SERVER_DIR" >&2
    exit 1
fi
if [ ! -f "$SCENARIO" ]; then
    echo "Scenario file not found: $SCENARIO" >&2
    exit 1
fi

cd "$SERVER_DIR"
export LD_LIBRARY_PATH="$SERVER_DIR/linux64:$SERVER_DIR:${LD_LIBRARY_PATH:-}"

exec "$SERVER_DIR/jre64/bin/java" \
    -Djava.library.path="$SERVER_DIR/linux64" \
    -cp "java/projectzomboid.jar:java/." \
    zombie.network.FakeClientManager \
    -scenarios="$SCENARIO" \
    -loglevel="$LOG_LEVEL"