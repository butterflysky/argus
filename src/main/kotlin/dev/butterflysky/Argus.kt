package dev.butterflysky

import dev.butterflysky.config.ArgusConfig
import dev.butterflysky.discord.DiscordService
import dev.butterflysky.discord.WhitelistCommands
import dev.butterflysky.service.WhitelistService
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
                logger.info("[ARGUS WHITELIST] Successfully registered 'test' subcommand")
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
                            .executes { context ->
                                ArgusConfig.load()
                                context.source.sendFeedback({ Text.literal("[Argus] Configuration reloaded") }, true)
                                logger.info("Configuration reloaded by ${context.source.name}")
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
            )
        }
    }
    
    /**
     * Initialize Discord integration
     */
    private fun initializeDiscord() {
        try {
            // Initialize in a separate thread to not block the main thread
            Thread {
                try {
                    discordService.init()
                } catch (e: Exception) {
                    logger.error("Failed to initialize Discord service", e)
                }
            }.start()
        } catch (e: Exception) {
            logger.error("Error starting Discord initialization thread", e)
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
            } catch (e: Exception) {
                logger.error("Failed to shutdown services", e)
            }
        }
    }
}