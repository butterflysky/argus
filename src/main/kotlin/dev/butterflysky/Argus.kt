package dev.butterflysky

import dev.butterflysky.config.ArgusConfig
import dev.butterflysky.config.Constants
import dev.butterflysky.discord.DiscordService
import dev.butterflysky.discord.WhitelistCommands
import dev.butterflysky.service.WhitelistService
import dev.butterflysky.whitelist.LinkManager
import dev.butterflysky.util.ThreadPools
import dev.butterflysky.util.ProfileApiClient
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

object Argus : ModInitializer {
    private val logger = LoggerFactory.getLogger("argus")
    private val discordService = DiscordService.getInstance()
    private val whitelistService = WhitelistService.getInstance()

	override fun onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Initializing Argus whitelist management mod")
        
        // Load configuration
        ArgusConfig.load()
        
        // Register commands in Minecraft
        registerMinecraftCommands()
        
        // Initialize Discord integration
        initializeDiscord()
        
        // Register server lifecycle events
        registerServerEvents()
	}
    
    /**
     * Register custom commands in Minecraft
     */
    private fun registerMinecraftCommands() {
        // Register our command extensions
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            // Find the whitelist command node
            val whitelistNode = dispatcher.root.getChild("whitelist")
            if (whitelistNode != null) {
                // Add our test subcommand
                whitelistNode.addChild(
                    literal("test")
                        .executes { context ->
                            val source = context.source
                            logger.info("[ARGUS WHITELIST] Test subcommand executed by {}", source.name)
                            source.sendFeedback({ Text.literal("[Argus] Whitelist test command executed successfully") }, false)
                            1
                        }
                        .build()
                )
                
                // Add our link subcommand
                whitelistNode.addChild(
                    literal("link")
                        .executes { context ->
                            val source = context.source
                            val player = source.player
                            
                            if (player == null) {
                                source.sendFeedback({ Text.literal("[Argus] This command can only be used by players") }, false)
                                return@executes 0
                            }
                            
                            val uuid = player.uuid
                            val username = player.gameProfile.name
                            
                            // Check if already linked
                            val discordUser = WhitelistService.getInstance().getDiscordUserForMinecraftAccount(uuid)
                            if (discordUser != null) {
                                source.sendFeedback({ 
                                    Text.literal("§a[Argus] §fYour Minecraft account is already linked to Discord user §b${discordUser.username} §f(ID: §b${discordUser.id}§f)")
                                }, false)
                                return@executes 1
                            }
                            
                            // Generate token
                            val linkManager = dev.butterflysky.whitelist.LinkManager.getInstance()
                            val token = linkManager.createLinkToken(uuid, username)
                            
                            // Send message with the token
                            source.sendFeedback({
                                Text.literal("§6[Argus] §eGenerated a link token for your account.\n")
                                    .append(Text.literal("§eRun this command in Discord: §b/whitelist link $token\n"))
                                    .append(Text.literal("§7(This token will expire in ${ArgusConfig.get().link.tokenExpiryMinutes} minutes)"))
                            }, false)
                            
                            logger.info("[ARGUS WHITELIST] Generated link token $token for player $username ($uuid)")
                            1
                        }
                        .build()
                )
                logger.info("[ARGUS WHITELIST] Successfully registered 'test' and 'link' subcommands")
            } else {
                logger.warn("[ARGUS WHITELIST] Could not find whitelist command to attach subcommand")
            }
        }
        
        // Register the argus command
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("argus")
                    .requires { it.hasPermissionLevel(4) } // Operator permission level
                    .then(
                        literal("reload")
                            .requires { it.hasPermissionLevel(4) } // Operator permission level
                            .executes { context ->
                                try {
                                    // Reload the configuration
                                    ArgusConfig.load()
                                    
                                    context.source.sendFeedback({ 
                                        Text.literal("§6[Argus] Configuration reloaded from disk")
                                    }, true)
                                    
                                    // Reinitialize Discord if enabled
                                    if (ArgusConfig.get().discord.enabled) {
                                        // Restart Discord service with new config
                                        restartDiscordService(context.source, "Restarting with new configuration by ${context.source.name}")
                                    }
                                    
                                    // Update Link Manager with new config
                                    LinkManager.getInstance().updateConfig()
                                    
                                    context.source.sendFeedback({ 
                                        Text.literal("§a[Argus] Configuration reload completed")
                                    }, true)
                                    
                                    logger.info("Configuration reloaded by ${context.source.name}")
                                } catch (e: Exception) {
                                    context.source.sendFeedback({ 
                                        Text.literal("§c[Argus] Error reloading configuration: ${e.message}")
                                    }, true)
                                    logger.error("Error reloading configuration", e)
                                }
                                1
                            }
                    )
                    .then(
                        literal("import")
                            .executes { context ->
                                val server = context.source.server
                                whitelistService.importExistingWhitelist()
                                context.source.sendFeedback({ Text.literal("[Argus] Imported vanilla whitelist entries") }, true)
                                logger.info("Vanilla whitelist imported by ${context.source.name}")
                                1
                            }
                    )
                    .then(
                        literal("status")
                            .executes { context ->
                                val dbConnected = WhitelistService.isDatabaseConnected()
                                val whitelistedCount = whitelistService.getWhitelistedPlayers().size
                                val discordEnabled = ArgusConfig.get().discord.enabled
                                val discordConnected = discordService.isConnected()
                                
                                context.source.sendFeedback({ 
                                    Text.literal("[Argus] Status:\n" +
                                        "Database: " + (if (dbConnected) "Connected" else "Disconnected") + "\n" +
                                        "Whitelisted Players: $whitelistedCount\n" +
                                        "Discord: " + (if (discordEnabled) "Enabled" else "Disabled") + 
                                        (if (discordEnabled) ", " + (if (discordConnected) "Connected" else "Disconnected") else "")
                                    )
                                }, false)
                                1
                            }
                    )
                    .then(
                        literal("reconnect")
                            .requires { it.hasPermissionLevel(4) } // Operator permission level
                            .executes { context ->
                                if (!ArgusConfig.get().discord.enabled) {
                                    context.source.sendFeedback({ 
                                        Text.literal("§c[Argus] Discord integration is disabled in config.")
                                    }, true)
                                    return@executes 0
                                }
                                
                                // Disconnect and reconnect to Discord
                                restartDiscordService(context.source, "Discord reconnection initiated by ${context.source.name}")
                                
                                1
                            }
                    )
            )
        }
    }
    
    /**
     * Initialize Discord integration in a separate thread
     */
    private fun initializeDiscord() {
        ThreadPools.backgroundTaskExecutor.execute {
            try {
                logger.info("Initializing Discord service on thread ${Thread.currentThread().name}")
                discordService.init()
            } catch (e: Exception) {
                logger.error("Failed to initialize Discord service", e)
                // Signal thread error to uncaught exception handler if available
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(
                    Thread.currentThread(), e
                )
            }
        }
    }
    
    /**
     * Helper method to restart the Discord service
     */
    private fun restartDiscordService(source: net.minecraft.server.command.ServerCommandSource, logMessage: String) {
        ThreadPools.backgroundTaskExecutor.execute {
            try {
                source.sendFeedback({ 
                    Text.literal("§6[Argus] Disconnecting from Discord...")
                }, true)
                
                // Disconnect from Discord
                discordService.shutdown()
                
                // Small delay to ensure clean shutdown
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt() // Restore interrupt status
                    logger.warn("Discord restart thread interrupted during shutdown delay")
                    throw e // Re-throw to abort the operation
                }
                
                source.sendFeedback({ 
                    Text.literal("§6[Argus] Reconnecting to Discord...")
                }, true)
                
                // Reconnect to Discord
                discordService.init()
                
                source.sendFeedback({ 
                    Text.literal("§a[Argus] Discord service restarted successfully")
                }, true)
                
                logger.info(logMessage)
            } catch (e: Exception) {
                source.sendFeedback({ 
                    Text.literal("§c[Argus] Error restarting Discord service: ${e.message}")
                }, true)
                logger.error("Error restarting Discord service: $logMessage", e)
                // Signal thread error to uncaught exception handler if available
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(
                    Thread.currentThread(), e
                )
            }
        }
    }
    
    /**
     * Register server lifecycle events
     */
    private fun registerServerEvents() {
        // Register the server started event
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            logger.info("Minecraft server started, initializing services")
            
            try {
                // Set the server in the Discord service
                discordService.setMinecraftServer(server)
                
                // Register whitelist commands with the Discord service
                WhitelistCommands(server).registerHandlers()
                
                logger.info("Services initialized successfully")
            } catch (e: Exception) {
                logger.error("Failed to initialize services", e)
            }
        }
        
        // Register the server stopping event
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            logger.info("Minecraft server stopping, shutting down services")
            
            try {
                // Shutdown the Discord service
                discordService.shutdown()
                
                // Shutdown the link manager
                LinkManager.getInstance().shutdown()
                
                // Shutdown the profile API client
                ProfileApiClient.getInstance().shutdown()
                
                // Shutdown all thread pools
                ThreadPools.shutdownAll()
                
                logger.info("Shutting down Argus services")
            } catch (e: Exception) {
                logger.error("Failed to shutdown services", e)
            }
        }
    }
}