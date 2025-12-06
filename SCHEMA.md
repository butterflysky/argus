# Argus Whitelist Database Schema

**Legacy note:** This schema documents the pre-reboot SQL design. The current Argus implementation is cache-first (JSON + .bak) with no database. Keep this file for historical reference only.

This document outlines the database schema for the legacy Argus whitelist management system.

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
