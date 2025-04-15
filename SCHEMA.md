# Argus Whitelist Database Schema

This document outlines the database schema for the Argus whitelist management system.

## Overview

The Argus whitelist system is designed around the following core principles:

1. Discord accounts are the primary identifier for players (using immutable Discord IDs)
2. Minecraft accounts can be transferred between players
3. All username changes are tracked for both platforms
4. Moderator actions are audited
5. Whitelist eligibility follows a 48-hour cooldown by default

## Tables

### discord_users

Stores information about Discord users. Uses Discord's numeric ID as the primary key since it's immutable.

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT | PRIMARY KEY, Discord user ID |
| current_username | VARCHAR(128) | Current Discord username |
| current_servername | VARCHAR(128) | Current nickname in server (nullable) |
| joined_server_at | TIMESTAMP | When the user joined the server |
| is_in_server | BOOLEAN | Whether the user is currently in the server |
| left_server_at | TIMESTAMP | When the user left the server (nullable) |
| created_at | TIMESTAMP | When the record was created |

**Special IDs:**
- `-1`: Represents unmapped Discord users (legacy Minecraft accounts without Discord mapping)
- `-2`: System user for automated actions

### discord_username_history

Tracks all changes to Discord usernames and nicknames.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER | PRIMARY KEY |
| discord_id | BIGINT | FOREIGN KEY to discord_users.id |
| username | VARCHAR(128) | The username value |
| type | ENUM | Either USERNAME, SERVERNAME, or NICKNAME |
| recorded_at | TIMESTAMP | When the change was recorded |
| recorded_by | BIGINT | FOREIGN KEY to discord_users.id (nullable) |

### minecraft_users

Stores information about Minecraft accounts.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PRIMARY KEY, Minecraft account UUID |
| current_username | VARCHAR(128) | Current Minecraft username |
| created_at | TIMESTAMP | When the record was created |
| current_owner_id | BIGINT | FOREIGN KEY to discord_users.id (nullable) |
| transferred_at | TIMESTAMP | When ownership was last transferred (nullable) |

### minecraft_username_history

Tracks all changes to Minecraft usernames.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER | PRIMARY KEY |
| minecraft_uuid | UUID | FOREIGN KEY to minecraft_users.id |
| username | VARCHAR(128) | The minecraft username |
| recorded_at | TIMESTAMP | When the change was recorded |
| recorded_by | BIGINT | FOREIGN KEY to discord_users.id (nullable) |

### whitelist_applications

Tracks the relationship between Discord users and Minecraft accounts for whitelisting purposes.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER | PRIMARY KEY |
| discord_id | BIGINT | FOREIGN KEY to discord_users.id |
| minecraft_uuid | UUID | FOREIGN KEY to minecraft_users.id |
| status | ENUM | PENDING, APPROVED, REJECTED, or REMOVED |
| applied_at | TIMESTAMP | When the application was submitted |
| eligible_at | TIMESTAMP | When the application becomes eligible for approval |
| is_moderator_created | BOOLEAN | Whether this was created directly by a moderator |
| processed_at | TIMESTAMP | When the application was processed (nullable) |
| processed_by | BIGINT | FOREIGN KEY to discord_users.id (nullable) |
| override_reason | TEXT | Reason for overriding the cooldown (nullable) |
| notes | TEXT | Any additional notes about the application (nullable) |

### audit_logs

Tracks all actions taken in the system for accountability and security.

| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER | PRIMARY KEY |
| action_type | VARCHAR(32) | Type of action performed |
| entity_type | VARCHAR(32) | Type of entity affected |
| entity_id | VARCHAR(64) | ID of affected entity |
| performed_by | BIGINT | FOREIGN KEY to discord_users.id (nullable) |
| performed_at | TIMESTAMP | When the action was performed |
| details | TEXT | Additional details about the action |

## Entity Relationships

- A Discord user can own multiple Minecraft accounts
- A Minecraft account has one current owner (Discord user)
- A Discord user can submit multiple whitelist applications
- A Minecraft account can have multiple whitelist applications (over time)
- Each application has a status (PENDING, APPROVED, REJECTED, REMOVED)
- Username history tables track changes to usernames for both platforms

## Cooldown System

The system enforces a 48-hour cooldown period for whitelist eligibility, which helps prevent griefing by requiring a waiting period. Moderators can override this restriction with a reason.

## Audit Logging

All significant actions in the system are logged in the audit_logs table, providing a full history of changes and accountability.

## Legacy Schema (Prior to Refactor)

Below is the legacy schema that was replaced in the refactor:

### MinecraftUsers Table

- id: UUID - Primary key, the Minecraft player's UUID, supplied by Minecraft's game profile API
- username: String - Player's Minecraft username
- isWhitelisted: Boolean - Whether the player is whitelisted
- addedAt: DateTime - When the player was added to the whitelist
- addedBy: String - ID of the Discord user who added them

### DiscordUsers Table

- id: String - Primary key, the Discord user's ID
- username: String - Discord username
- isAdmin: Boolean - Whether the user has admin privileges
- isModerator: Boolean - Whether the user has moderator privileges

### UserMappings Table (Junction Table)

- id: Integer - Auto-incrementing primary key
- minecraftUserId: UUID - Foreign key to MinecraftUsers.id
- discordUserId: String - Foreign key to DiscordUsers.id
- createdAt: DateTime - When the mapping was created
- createdBy: String - Discord ID of who created the mapping
- isPrimary: Boolean - Whether this is the primary Minecraft account for this Discord user

### UsernameHistory Table

- id: Integer - Auto-incrementing primary key
- minecraftUserId: UUID - Foreign key to MinecraftUsers.id (nullable)
- discordUserId: String - Foreign key to DiscordUsers.id (nullable)
- oldName: String - Previous username
- newName: String - New username
- changedAt: DateTime - When the username was changed
- platform: String - Which platform ("minecraft" or "discord")

### WhitelistEvents Table (Audit Log)

- id: Integer - Auto-incrementing primary key
- minecraftUserId: UUID - Foreign key to MinecraftUsers.id
- discordUserId: String - Discord user ID involved in the action (nullable)
- eventType: String - Type of event (add, remove, link, unlink, name update)
- timestamp: DateTime - When the event occurred
- actorDiscordId: String - Discord ID of who performed the action (nullable)
- comment: Text - Optional admin/moderator comment
- details: Text - Technical details about the action