package dev.butterflysky.argus.common

import org.slf4j.LoggerFactory

object AuditLogger {
    private val logger = LoggerFactory.getLogger("argus-audit")
    private var dispatcher: ((String) -> Unit)? = null

    fun configure(dispatcher: ((String) -> Unit)?) {
        this.dispatcher = dispatcher
    }

    fun log(message: String) {
        logger.info(message)
        dispatcher?.invoke(message)
    }
}
