package dev.butterflysky

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

/**
 * Utility class for whitelist command testing
 */
object WhitelistTest {
    private val logger = LoggerFactory.getLogger("argus-test")
    
    /**
     * Register a test command to verify our whitelist test subcommand
     */
    fun registerTestCommand() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("argustest")
                    .executes { context ->
                        logger.info("Running argustest command")
                        val success = runTest(context.source.name)
                        if (success) {
                            context.source.sendFeedback({ Text.literal("Argus test command registered successfully") }, false)
                        } else {
                            context.source.sendFeedback({ Text.literal("Argus test failed") }, false)
                        }
                        1
                    }
            )
        }
    }
    
    private fun runTest(executor: String): Boolean {
        logger.info("Argus test running by {}", executor)
        return true
    }
}