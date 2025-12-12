package dev.butterflysky.argus.common

import org.slf4j.LoggerFactory

data class AuditEntry(
    val action: String,
    val subject: String? = null,
    val actor: String? = null,
    val description: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toConsoleString(): String {
        val parts =
            listOfNotNull(
                action,
                subject,
                actor,
                description,
                metadata.takeIf { it.isNotEmpty() }?.entries?.joinToString(" ") { "${it.key}=${it.value}" },
            )
        return parts.joinToString(" — ")
    }
}

object AuditLogger {
    private val logger = LoggerFactory.getLogger("argus-audit")
    private var dispatcher: ((AuditEntry) -> Unit)? = null

    fun configure(dispatcher: ((AuditEntry) -> Unit)?) {
        this.dispatcher = dispatcher
    }

    fun log(entry: AuditEntry) {
        logger.info(entry.toConsoleString())
        dispatcher?.invoke(entry)
    }

    fun log(
        action: String,
        subject: String? = null,
        actor: String? = null,
        description: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = log(AuditEntry(action, subject, actor, description, metadata))

    /** Backward-compatible string logger; treated as an unstructured audit entry. */
    fun log(message: String) = log(AuditEntry(action = "audit", description = message))
}

fun auditMeta(vararg pairs: Pair<String, Any?>): Map<String, String> = pairs.mapNotNull { (k, v) -> v?.toString()?.let { k to it } }.toMap()
