package dev.butterflysky.config

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileWriter
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Tests for configuration system
 */
class ConfigTest {
    
    @TempDir
    lateinit var tempDir: Path
    
    /**
     * Test the merge functionality when properties are missing
     */
    @Test
    fun `should merge missing properties with defaults`() {
        // Create a partial config
        val partialConfig = """
        {
            "discord": {
                "enabled": true,
                "token": "MY_TOKEN"
            },
            "whitelist": {
                "cooldownHours": 72
            }
        }
        """.trimIndent()
        
        // Write to a temp file
        val configFile = tempDir.resolve("config.json").toFile()
        FileWriter(configFile).use { it.write(partialConfig) }
        
        // Parse with defaults using our reflection-based approach
        val jsonElement = JsonParser.parseReader(configFile.reader())
        val defaultInstance = ArgusConfig.ConfigData()
        
        val mergedJson = mergeWithDefaults(jsonElement.asJsonObject, defaultInstance)
        val gson = Gson()
        val resultConfig = gson.fromJson(mergedJson, ArgusConfig.ConfigData::class.java)
        
        // Verify the merged config has values from both original and defaults
        assertThat(resultConfig.discord.enabled).isTrue() // From original
        assertThat(resultConfig.discord.token).isEqualTo("MY_TOKEN") // From original
        
        // These should be filled from defaults
        assertThat(resultConfig.discord.guildId).isEqualTo("YOUR_DISCORD_GUILD_ID")
        assertThat(resultConfig.discord.adminRoles).containsExactly("Admins", "Moderator")
        
        assertThat(resultConfig.whitelist.cooldownHours).isEqualTo(72) // From original
        assertThat(resultConfig.whitelist.defaultHistoryLimit).isEqualTo(10) // From default
        
        // These should all be from defaults
        assertThat(resultConfig.reconnect.initialDelayMs).isEqualTo(5000)
        assertThat(resultConfig.link.tokenExpiryMinutes).isEqualTo(10)
        assertThat(resultConfig.timeouts.profileLookupSeconds).isEqualTo(5)
    }
    
    /**
     * Test helper function - same as the one in ArgusConfig but copied here for testing
     */
    private fun mergeWithDefaults(loadedJson: com.google.gson.JsonObject, defaultInstance: Any): com.google.gson.JsonElement {
        val gson = Gson()
        val defaultJson = gson.toJsonTree(defaultInstance)
        if (!defaultJson.isJsonObject) {
            return loadedJson
        }
        
        val result = loadedJson.deepCopy()
        var modified = false
        
        for (entry in defaultJson.asJsonObject.entrySet()) {
            val propertyName = entry.key
            val defaultValue = entry.value
            
            if (!result.has(propertyName)) {
                // Property missing in loaded config, add it from defaults
                result.add(propertyName, defaultValue)
                modified = true
            } else if (defaultValue.isJsonObject && result.get(propertyName).isJsonObject) {
                // For nested objects, recursively merge
                val propertyType = defaultInstance::class.memberProperties
                    .firstOrNull { it.name == propertyName }
                    ?.returnType?.classifier as? KClass<*>
                
                if (propertyType != null) {
                    val defaultPropertyInstance = propertyType.primaryConstructor?.callBy(emptyMap())
                    if (defaultPropertyInstance != null) {
                        val mergedProperty = mergeWithDefaults(
                            result.get(propertyName).asJsonObject,
                            defaultPropertyInstance
                        )
                        
                        if (mergedProperty != result.get(propertyName)) {
                            result.add(propertyName, mergedProperty)
                            modified = true
                        }
                    }
                }
            }
        }
        
        return if (modified) result else loadedJson
    }
}