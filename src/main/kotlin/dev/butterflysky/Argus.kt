package dev.butterflysky

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

object Argus : ModInitializer {
    private val logger = LoggerFactory.getLogger("argus")

	override fun onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Hello Fabric world!")
        
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
        
        // Register a test command for verification
        WhitelistTest.registerTestCommand()
	}
}