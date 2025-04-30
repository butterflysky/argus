# Argus

![Argus](src/main/resources/assets/argus/icon.png)

Argus is a powerful whitelist management mod for Minecraft servers, offering Discord integration and comprehensive player management.

## Features

- **Discord Integration**: Control your whitelist through Discord commands
- **Player Verification**: Link Minecraft accounts to Discord users
- **Admin Commands**: Manage banned players and whitelist from Minecraft or Discord
- **Audit Logging**: Track all whitelist actions with detailed logs
- **Legacy Import**: Automatically import existing vanilla whitelist entries

## Installation

1. Install [Fabric](https://fabricmc.net/use/) for Minecraft 1.21.5
2. Place the `argus.jar` file in your server's `mods` folder
3. Start your server once to generate the default configuration
4. Edit `config/argus.json` to configure Discord integration and other settings

## Configuration

Argus is highly configurable:

```json
{
  "discord": {
    "enabled": true,
    "token": "YOUR_DISCORD_BOT_TOKEN",
    "guildId": "YOUR_DISCORD_GUILD_ID",
    "serverName": "Your Server Name",
    "adminRoles": ["Admins", "Moderator"],
    "patronRole": "Patron",
    "adultRole": "Adults",
    "loggingChannel": "server-admin-messages"
  },
  "whitelist": {
    "cooldownHours": 48,
    "autoRemoveOnLeave": false
  }
}
```

## Commands

### Minecraft Commands

- `/whitelist link` - Generate a token to link your Minecraft account to Discord
- `/whitelist test` - Test if Argus is working correctly
- `/argus reload` - Reload the configuration
- `/argus import` - Import vanilla whitelist entries
- `/argus status` - Show Argus status
- `/argus reconnect` - Reconnect to Discord

### Discord Commands

- `/whitelist link <token>` - Link Discord account with a Minecraft account
- `/whitelist add <username>` - Add a player to the whitelist
- `/whitelist remove <username>` - Remove a player from the whitelist
- `/whitelist check <username>` - Check if a player is whitelisted

## Development

### Building from Source

```bash
./gradlew build
```

### Running in Development

```bash
./gradlew runClient  # For client testing
./gradlew runServer  # For server testing
```

### Testing

```bash
./gradlew test
```

## License

This project is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE)

## Links

- [Homepage](https://abfielder.com/)
- [GitHub Repository](https://github.com/butterflysky/argus)