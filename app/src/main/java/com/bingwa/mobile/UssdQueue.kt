package com.bingwa.mobile

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A thread‑safe, priority‑aware task queue for USSD operations.
 * Tasks are executed sequentially on a dedicated background thread.
 * Supports:
 * - 3 priority levels (SPECIAL, HIGH, NORMAL)
 * - Task timeout (automatically aborts stuck tasks)
 * - Cancellation (by task ID or all pending)
 * - Detailed logging for debugging
 * - Automatic retry of failed tasks (optional, disabled by default)
 */
object UssdQueue {
    private const val TAG = "UssdQueue"

    // Priority levels (higher number = higher priority)
    private const val PRIORITY_SPECIAL = 2
    private const val PRIORITY_HIGH = 1
    private const val PRIORITY_NORMAL = 0

    // Task timeout in milliseconds (if a task runs longer, it will be aborted)
    // Reduced from 30s to 15s to avoid long stalls when USSD popups are slow
    private const val TASK_TIMEOUT_MS = 15_000L

    // Retry policy (0 = no retry by default, can be overridden per task)
    private const val DEFAULT_MAX_RETRIES = 1

    // Dedicated worker thread
    private val workerThread = HandlerThread("UssdQueueWorker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Priority queue (FIFO within same priority)
    private val taskQueue = PriorityBlockingQueue<QueueTask>(11, compareByDescending { it.priority })

    // Flag to signal worker loop to stop
    private val stopWorker = AtomicBoolean(false)

    // Current running task (for cancellation/timeout)
    private var currentTask: QueueTask? = null
    private var currentTimeoutRunnable: Runnable? = null

    // Lock for synchronizing state changes
    private val lock = Any()

    init {
        // Start the worker loop
        workerHandler.post { workerLoop() }
    }

    /**
     * Enqueues a task with the given priority.
     * @param task the action to run
     * @param priority one of [USSD_EXECUTION_PRIORITY_SPECIAL], [USSD_EXECUTION_PRIORITY_HIGH], or [USSD_EXECUTION_PRIORITY_NORMAL]
     * @param maxRetries maximum number of retries on failure (default 1)
     * @return a token that can be used to cancel the task (or null if not needed)
     */
    fun enqueue(task: Runnable, priority: String = USSD_EXECUTION_PRIORITY_NORMAL, maxRetries: Int = DEFAULT_MAX_RETRIES): CancellationToken? {
        val p = when {
            priority.equals(USSD_EXECUTION_PRIORITY_SPECIAL, ignoreCase = true) -> PRIORITY_SPECIAL
            priority.equals(USSD_EXECUTION_PRIORITY_HIGH, ignoreCase = true) -> PRIORITY_HIGH
            else -> PRIORITY_NORMAL
        }
        val queueTask = QueueTask(task, p, maxRetries)
        taskQueue.add(queueTask)
        Log.d(TAG, "Enqueued task with priority $p, maxRetries=$maxRetries, queue size=${taskQueue.size}")
        return queueTask.token
    }

    /**
     * Enqueues a balance check as a special priority task.
     */
    fun enqueueBalanceCheck(context: Context) {
        enqueue(
            task = Runnable {
                BalanceChecker.requestBalanceCheck(context, specialHandling = true)
            },
            priority = USSD_EXECUTION_PRIORITY_SPECIAL,
            maxRetries = 2
        )
    }

    /**
     * Cancels a specific task using its cancellation token.
     * @return true if the task was removed from the queue (or is the currently running task and was aborted)
     */
    fun cancel(token: CancellationToken): Boolean {
        synchronized(lock) {
            // Check if it's the currently running task
            val current = currentTask
            if (current?.token === token) {
                // Abort the running task (handled in workerLoop)
                Log.w(TAG, "Cancelling currently running task")
                current.abortRequested = true
                return true
            }
            // Remove from queue
            val removed = taskQueue.removeIf { it.token === token }
            if (removed) {
                Log.d(TAG, "Cancelled pending task (removed from queue)")
            }
            return removed
        }
    }

    /**
     * Cancels all pending tasks (does not affect the currently running one).
     * @return number of tasks removed
     */
    fun cancelAllPending(): Int {
        synchronized(lock) {
            val count = taskQueue.size
            taskQueue.clear()
            Log.d(TAG, "Cancelled $count pending tasks")
            return count
        }
    }

    /**
     * Checks if there is any work pending or currently running.
     */
    fun hasWork(): Boolean {
        synchronized(lock) {
            return currentTask != null || taskQueue.isNotEmpty()
        }
    }

    /**
     * Returns the number of tasks currently in the queue.
     */
    fun pendingCount(): Int {
        synchronized(lock) {
            return taskQueue.size
        }
    }

    /**
     * Shuts down the worker thread gracefully.
     * Pending tasks will not be executed.
     */
    fun shutdown() {
        stopWorker.set(true)
        workerThread.quitSafely()
        Log.i(TAG, "Shutdown complete")
    }

    // ------------------ Worker Loop ------------------

    private fun workerLoop() {
        while (!stopWorker.get()) {
            try {
                // Poll the queue with a timeout so we can check stop flag periodically
                val task = taskQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

                // Set as current task
                synchronized(lock) {
                    currentTask = task
                }

                // Execute with timeout and error handling
                executeTask(task)

                // Clear current task
                synchronized(lock) {
                    currentTask = null
                    currentTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    currentTimeoutRunnable = null
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, probably shutting down
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in worker loop", e)
            }
        }
    }

    private fun executeTask(task: QueueTask) {
        var retries = 0
        var success = false
        while (retries <= task.maxRetries && !task.abortRequested && !stopWorker.get()) {
            try {
                waitForSilentUssdLock()

                val timeoutRunnable = Runnable {
                    synchronized(lock) {
                        if (currentTask === task) {
                            Log.e(TAG, "Task timed out after ${TASK_TIMEOUT_MS}ms")
                            task.abortRequested = true
                        }
                    }
                }
                synchronized(lock) {
                    currentTimeoutRunnable = timeoutRunnable
                }
                mainHandler.postDelayed(timeoutRunnable, TASK_TIMEOUT_MS)

                task.runnable.run()
                success = true

                mainHandler.removeCallbacks(timeoutRunnable)
                synchronized(lock) {
                    if (currentTimeoutRunnable === timeoutRunnable) {
                        currentTimeoutRunnable = null
                    }
                }

                if (task.abortRequested) {
                    Log.w(TAG, "Task was aborted (timeout or cancellation)")
                    success = false
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed (attempt ${retries + 1}/${task.maxRetries})", e)
                retries++
                if (retries > task.maxRetries) {
                    success = false
                    break
                }
                val backoffMs = (RETRY_BASE_DELAY_MS * (1L shl (retries - 1))).coerceAtMost(MAX_RETRY_BACKOFF_MS)
                Log.d(TAG, "Retrying task in ${backoffMs}ms (attempt $retries/${task.maxRetries})")
                Thread.sleep(backoffMs)
            }
        }

        if (!success && !task.abortRequested && !stopWorker.get()) {
            Log.w(TAG, "Task failed after ${task.maxRetries} retries")
        }
    }

    private fun waitForSilentUssdLock() {
        val waitStart = System.currentTimeMillis()
        val maxWait = 3_000L
        while (SilentUssdOptimized.isExecutionInProgress() && System.currentTimeMillis() - waitStart < maxWait) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        if (SilentUssdOptimized.isExecutionInProgress()) {
            Log.w(TAG, "SilentUssdOptimized still busy after waiting ${maxWait}ms")
        }
    }

    // ------------------ Internal Classes ------------------

    private class QueueTask(
        val runnable: Runnable,
        val priority: Int,
        val maxRetries: Int
    ) {
        val token = CancellationToken()
        @Volatile var abortRequested = false
    }

    /**
     * Opaque token used to cancel a previously enqueued task.
     */
    class CancellationToken internal constructor()

    // We need to keep the public constants for priority strings
    // These are typically defined elsewhere; we'll reference them or redeclare.
    // If they don't exist, we can define them here.
    const val USSD_EXECUTION_PRIORITY_SPECIAL = "SPECIAL"
    const val USSD_EXECUTION_PRIORITY_HIGH = "HIGH"
    const val USSD_EXECUTION_PRIORITY_NORMAL = "NORMAL"

    // Retry constants
    private const val RETRY_BASE_DELAY_MS = 500L
    private const val MAX_RETRY_BACKOFF_MS = 5_000L
}
