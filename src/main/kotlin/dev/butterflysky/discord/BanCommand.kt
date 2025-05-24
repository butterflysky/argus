package dev.butterflysky.discord

import dev.butterflysky.config.ArgusConfig
import dev.butterflysky.db.WhitelistDatabase
import dev.butterflysky.service.WhitelistService
import dev.butterflysky.config.Constants
import dev.butterflysky.util.ThreadPools
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory

class GlobalBanCommand(private val whitelistService: WhitelistService) {

    private val logger = LoggerFactory.getLogger(GlobalBanCommand::class.java)

    companion object {
        fun register(): CommandData {
            return Commands.slash("ban", "Bans all Minecraft accounts linked to a Discord user.")
                .addOptions(
                    OptionData(OptionType.USER, "discord_user", "The Discord user whose accounts to ban.", true),
                    OptionData(OptionType.STRING, "reason", "The reason for the ban.", false)
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS))
                .setGuildOnly(true) // Assuming this is a guild-specific command
        }
    }

    fun handleCommand(event: SlashCommandInteractionEvent) {
        val targetDiscordUser = event.getOption("discord_user")!!.asUser
        val reason = event.getOption("reason")?.asString ?: "No reason provided."
        val moderator = event.user

        event.deferReply().queue() // Acknowledge interaction, actual work might take time

        logger.info("Global ban initiated by ${moderator.name} on ${targetDiscordUser.name} for reason: $reason")

        ThreadPools.discordCommandExecutor.execute {
            try {
                val linkedAccounts = whitelistService.getMinecraftAccountsForDiscordUser(targetDiscordUser.id)

                val embed = EmbedBuilder()
                    .setTitle("🚫 Global Ban Results")
                    .setAuthor(moderator.name, null, moderator.effectiveAvatarUrl)
                    .setTimestamp(Clock.System.now().toJavaInstant())

                if (linkedAccounts.isEmpty()) {
                    embed.setColor(Constants.INFO_COLOR)
                    embed.setDescription("No Minecraft accounts found linked to ${targetDiscordUser.asMention}. No bans issued.")
                    event.hook.editOriginalEmbeds(embed.build()).queue()
                    return@execute
                }

                embed.setDescription("Attempting to ban all Minecraft accounts linked to ${targetDiscordUser.asMention} for reason: `$reason`")
                var allBansSuccessful = true
                var anyBansAttempted = false

                linkedAccounts.forEach { mcAccount ->
                    anyBansAttempted = true
                    try {
                        val success = whitelistService.banPlayer(
                            uuid = mcAccount.uuid,
                            username = mcAccount.username,
                            discordId = moderator.id, // Use moderator's Discord ID
                            reason = reason
                        )
                        if (success) {
                            embed.addField("${mcAccount.username} (${mcAccount.uuid})", "✅ Banned successfully.", false)
                        } else {
                            embed.addField("${mcAccount.username} (${mcAccount.uuid})", "❌ Ban failed (already banned or other issue).", false)
                            allBansSuccessful = false
                        }
                    } catch (e: Exception) {
                        logger.error("Exception while trying to ban Minecraft account ${mcAccount.username}: ${e.message}", e)
                        embed.addField("${mcAccount.username} (${mcAccount.uuid})", "❌ Ban failed: ${e.message}", false)
                        allBansSuccessful = false
                    }
                }

                if (!anyBansAttempted) { // Should not happen if linkedAccounts is not empty, but as a safeguard
                    embed.setColor(Constants.INFO_COLOR)
                    embed.setDescription("No Minecraft accounts found linked to ${targetDiscordUser.asMention}. No bans issued.")
                } else if (allBansSuccessful) {
                    embed.setColor(Constants.SUCCESS_COLOR)
                } else {
                    embed.setColor(Constants.ERROR_COLOR)
                }

                event.hook.editOriginalEmbeds(embed.build()).queue()

            } catch (e: Exception) {
                logger.error("Error during global ban command for ${targetDiscordUser.name}", e)
                event.hook.editOriginal("An unexpected error occurred while processing the ban. Please check logs.").queue()
            }
        }
    }
}
