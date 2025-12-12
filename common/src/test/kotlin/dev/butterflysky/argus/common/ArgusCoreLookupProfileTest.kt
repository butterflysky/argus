package dev.butterflysky.argus.common

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.UUID

class ArgusCoreLookupProfileTest : ArgusTestBase() {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parseMojangProfile decodes id and name and ignores extras`() {
        val body = """
            {"id":"0123456789abcdef0123456789abcdef","name":"PlayerOne","dummy":"ignore me"}
        """.trimIndent()

        val (uuid, name) = parseMojangProfile(body, json)

        assertEquals(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), uuid)
        assertEquals("PlayerOne", name)
    }

    @Test
    fun `parseMojangProfile rejects non-32-char id`() {
        val body = """{"id":"too-short","name":"PlayerTwo"}"""

        assertFailsWith<IllegalArgumentException> {
            parseMojangProfile(body, json)
        }
    }
}
