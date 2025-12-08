# Installation

1) Download the loader jar for your server
- Fabric: `argus-<mc>-<version>-fabric.jar`
- NeoForge: `argus-<mc>-<version>-neoforge.jar`

2) Drop the jar into `mods/`.

3) Start the server once to generate `config/argus.json`.

4) Fill `config/argus.json`
- Follow [Discord Application](discord-setup.md) to create the bot and copy IDs.
- Field descriptions are in [Configuration](configuration.md).

5) (Optional) Configure from in-game
- `/argus config get <field>`
- `/argus config set <field> <value>` (tab-complete shows allowed fields/values)
- `/argus reload` to apply changes.
