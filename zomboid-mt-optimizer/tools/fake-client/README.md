# PZ Fake Client (testing tool)

Uses Project Zomboid's **built-in `FakeClientManager`** (a headless console client, similar to MCC) to connect a bot player to your local dedicated server. This triggers:

- player connection / character creation
- server-side cell loading around the bot
- zombie spawning around the bot
- the ZMTOpt agent hooks that depend on a connected player

## Requirements

- A running PZ dedicated server (non-Steam, `-Dzomboid.steam=0`) listening on `127.0.0.1:16261`
- The server's `ProjectZomboid64.json` configured with the ZMTOpt agent (see repo README)

## Usage

```bash
# from the tools/fake-client directory:
./run-fake-client.sh /path/to/pzserver ./scenario.json 1
```

The bot joins as **`Client0`** at `(6000,6000)` and walks around. To spawn a horde near it from the server console, write to the server's stdin:

```bash
# if the server runs with stdin from a FIFO:
echo "createhorde 50 Client0" > /tmp/pzconsole
```

## Notes

- The scenario JSON is the format documented in the game's `zombie.network.FakeClientManager` (loaded by `load(String)`).
- The fake client simulates zombies **locally**; it does NOT force the dedicated server's zombie simulation to load `IsoZombie` Java objects (server zombies live in a native/virtual layer). See the "Known limitations" section in the repo README.
- This is a test tool; don't ship it as a gameplay mod.