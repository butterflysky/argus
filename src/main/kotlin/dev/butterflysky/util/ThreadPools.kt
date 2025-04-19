package dev.butterflysky.util

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import com.google.common.util.concurrent.ThreadFactoryBuilder
import dev.butterflysky.config.ArgusConfig

object ThreadPools {
    private val logger = LoggerFactory.getLogger("argus-threads")
    
    /**
     * Creates a scheduled executor service with proper configuration
     * @param name Thread pool name used for thread naming and logging
     * @param corePoolSize Number of threads in the pool
     */
    fun newScheduledExecutor(name: String, corePoolSize: Int = 1): ScheduledExecutorService {
        val threadFactory = ThreadFactoryBuilder()
            .setNameFormat("argus-$name-%d")
            .setUncaughtExceptionHandler { thread, exception ->
                logger.error("Uncaught exception in thread ${thread.name}", exception)
            }
            .build()
        
        // Create a properly configured scheduled executor
        val executor = ScheduledThreadPoolExecutor(
            corePoolSize,
            threadFactory,
            RejectedExecutionHandler { _, executor ->
                logger.error("Task rejected from scheduled executor '$name' due to queue capacity reached")
                logger.error("Executor stats - Active: ${executor.activeCount}, Pool size: ${executor.poolSize}")
                throw RejectedExecutionException("Task rejected from argus-$name executor - queue full")
            }
        )
        
        // Configure queue capacity
        executor.maximumPoolSize = corePoolSize // Make it fixed-size
        executor.setKeepAliveTime(60L, TimeUnit.SECONDS)
        
        return executor
    }
    
    /**
     * Creates a fixed-size thread pool with proper configuration
     * @param name Thread pool name used for thread naming and logging
     * @param size Number of threads in the pool
     * @param queueCapacity Maximum number of tasks that can be queued before rejecting
     */
    fun newFixedThreadPool(name: String, size: Int, queueCapacity: Int = ArgusConfig.get().threadPools.defaultQueueSize): ExecutorService {
        val threadFactory = ThreadFactoryBuilder()
            .setNameFormat("argus-$name-%d")
            .setUncaughtExceptionHandler { thread, exception ->
                logger.error("Uncaught exception in thread ${thread.name}", exception)
            }
            .build()
            
        val queue = LinkedBlockingQueue<Runnable>(queueCapacity)
        
        return ThreadPoolExecutor(
            size, // core pool size
            size, // max pool size (same as core for fixed pool)
            60L, TimeUnit.SECONDS, // keep-alive time for excess threads
            queue, // work queue
            threadFactory,
            RejectedExecutionHandler { _, executor ->
                logger.error("Task rejected from executor '${name}' due to queue capacity (${queueCapacity}) reached")
                logger.error("Executor stats - Active: ${executor.activeCount}, Pool size: ${executor.poolSize}, Queue size: ${queue.size}")
                throw RejectedExecutionException("Task rejected from argus-$name executor - queue full")
            }
        )
    }
    
    /**
     * Creates a thread pool for handling Discord commands.
     * Uses config values for pool size and queue capacity.
     */
    val discordCommandExecutor: ExecutorService by lazy {
        val config = ArgusConfig.get().threadPools
        newFixedThreadPool(
            "discord-command", 
            config.discordCommandPoolSize, 
            config.discordCommandQueueSize
        )
    }
    
    /**
     * Creates a thread pool for background operations like bulk whitelist management.
     */
    val backgroundTaskExecutor: ExecutorService by lazy {
        val config = ArgusConfig.get().threadPools
        newFixedThreadPool(
            "background-task", 
            config.backgroundTaskPoolSize, 
            config.backgroundTaskQueueSize
        )
    }
    
    /**
     * Creates a scheduled executor for Discord reconnection logic
     */
    val discordReconnectExecutor: ScheduledExecutorService by lazy {
        newScheduledExecutor("discord-reconnect", 1)
    }
    
    /**
     * Creates a scheduled executor for link token cleanup tasks
     */
    val linkCleanupExecutor: ScheduledExecutorService by lazy {
        newScheduledExecutor("link-cleanup", 1)
    }
    
    /**
     * Creates a single thread executor for profile API lookups
     * A single thread is used to ensure we don't exceed rate limits
     */
    val profileApiExecutor: ExecutorService by lazy {
        newFixedThreadPool("profile-api", 1, 50)
    }
    
    /**
     * Shutdown all thread pools
     */
    fun shutdownAll() {
        logger.info("Shutting down all thread pools")
        
        // Add any shared executors here
        // Define a reusable shutdown function for each executor service
        val shutdownExecutor = { executor: ExecutorService, executorName: String ->
            logger.info("Shutting down $executorName executor")
            executor.shutdown()
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("$executorName executor did not terminate in time, forcing shutdown")
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                logger.error("Interrupted while shutting down $executorName executor", e)
                Thread.currentThread().interrupt()
                executor.shutdownNow()
            }
        }
        
        // Shutdown all executors
        shutdownExecutor(discordCommandExecutor, "discord-command")
        shutdownExecutor(backgroundTaskExecutor, "background-task")
        shutdownExecutor(discordReconnectExecutor, "discord-reconnect")
        shutdownExecutor(linkCleanupExecutor, "link-cleanup")
        shutdownExecutor(profileApiExecutor, "profile-api")
    }
}

class RateLimiter(private val maxRequests: Int, private val windowMs: Long) {
    private val timestamps = ConcurrentLinkedQueue<Long>()
    
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        
        // Remove expired timestamps
        while (timestamps.isNotEmpty() && timestamps.peek() < now - windowMs) {
            timestamps.poll()
        }
        
        // Check if we can acquire
        if (timestamps.size < maxRequests) {
            timestamps.add(now)
            return true
        }
        
        return false
    }
}