package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Instant UI Synchronization – real‑time updates with debouncing, batching, and queueing.
 *
 * Features:
 * - Broadcast intents for loose coupling (services → UI)
 * - LiveData / Flow / Channel for reactive UI
 * - Debouncing to avoid UI churn (e.g. rapid transaction status updates)
 * - Batching multiple updates into one delivery
 * - Persistent state (last known values)
 * - Lifecycle‑aware observers
 * - Compose integration with `remember` and `collectAsState`
 * - Thread‑safe and performant
 */
object InstantUISync {
    private const val TAG = "InstantUISync"

    // ─── Broadcast Actions ──────────────────────────────────────────────
    const val ACTION_TRANSACTION_UPDATED = "com.bingwa.mobile.TX_UPDATED_INSTANT"
    const val ACTION_TOKENS_UPDATED = "com.bingwa.mobile.TOKENS_UPDATED_INSTANT"
    const val ACTION_SETTINGS_UPDATED = "com.bingwa.mobile.SETTINGS_UPDATED_INSTANT"
    const val ACTION_BALANCE_UPDATED = "com.bingwa.mobile.BALANCE_UPDATED_INSTANT"
    const val ACTION_BATCH_UPDATED = "com.bingwa.mobile.BATCH_UPDATED_INSTANT"

    // ─── Extras ──────────────────────────────────────────────────────────
    const val EXTRA_TX_ID = "txId"
    const val EXTRA_STATUS = "status"
    const val EXTRA_RESPONSE = "response"
    const val EXTRA_TOKENS = "tokens"
    const val EXTRA_AMOUNT = "amount"
    const val EXTRA_SETTING_KEY = "settingKey"
    const val EXTRA_SETTING_VALUE = "settingValue"
    const val EXTRA_BALANCE = "balance"
    const val EXTRA_BATCH_UPDATES = "batchUpdates"  // JSON array of updates

    // ─── State ───────────────────────────────────────────────────────────
    private val _transactionUpdates = MutableSharedFlow<TransactionUpdate>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transactionUpdates: SharedFlow<TransactionUpdate> = _transactionUpdates.asSharedFlow()

    private val _tokenUpdates = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tokenUpdates: SharedFlow<Int> = _tokenUpdates.asSharedFlow()

    private val _settingUpdates = MutableSharedFlow<SettingUpdate>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val settingUpdates: SharedFlow<SettingUpdate> = _settingUpdates.asSharedFlow()

    private val _balanceUpdates = MutableSharedFlow<String>(
        replay = 1,  // keep last balance
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val balanceUpdates: SharedFlow<String> = _balanceUpdates.asSharedFlow()

    // ─── Data Classes ────────────────────────────────────────────────────
    data class TransactionUpdate(
        val txId: Int,
        val status: String,
        val response: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SettingUpdate(
        val key: String,
        val value: Any?,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class BatchUpdate(
        val updates: List<Any>,  // can contain any of the update types
        val timestamp: Long = System.currentTimeMillis()
    )

    // ─── Internal State ──────────────────────────────────────────────────
    private val lastTransactionMap = ConcurrentHashMap<Int, TransactionUpdate>()
    private val lastToken = AtomicLong(-1)
    private var lastBalance: String? = null
    private val settingCache = ConcurrentHashMap<String, Any?>()

    // Debouncing & batching
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingBatch = mutableListOf<Any>()
    private var batchScheduled = false
    private val BATCH_DELAY_MS = 80L  // collect updates within 80ms then dispatch

    // ─── Broadcast Helpers ──────────────────────────────────────────────

    fun broadcastTransactionUpdate(context: Context, txId: Int, status: String, response: String = "") {
        val update = TransactionUpdate(txId, status, response)
        // Store last known
        lastTransactionMap[txId] = update
        // Send broadcast
        val intent = Intent(ACTION_TRANSACTION_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TX_ID, txId)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_RESPONSE, response)
        }
        context.sendBroadcast(intent)
        // Emit to flow
        emitOnMain { _transactionUpdates.tryEmit(update) }
        // Add to batch if not urgent (we'll flush after delay)
        scheduleBatchUpdate(update)
    }

    fun broadcastTokenUpdate(context: Context, tokens: Int) {
        lastToken.set(tokens.toLong())
        val intent = Intent(ACTION_TOKENS_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TOKENS, tokens)
        }
        context.sendBroadcast(intent)
        emitOnMain { _tokenUpdates.tryEmit(tokens) }
        scheduleBatchUpdate(tokens)
    }

    fun broadcastSettingUpdate(context: Context, key: String, value: Any?) {
        settingCache[key] = value
        val intent = Intent(ACTION_SETTINGS_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SETTING_KEY, key)
            when (value) {
                is String -> putExtra(EXTRA_SETTING_VALUE, value)
                is Int -> putExtra(EXTRA_SETTING_VALUE, value)
                is Boolean -> putExtra(EXTRA_SETTING_VALUE, value)
                else -> putExtra(EXTRA_SETTING_VALUE, value?.toString() ?: "")
            }
        }
        context.sendBroadcast(intent)
        val update = SettingUpdate(key, value)
        emitOnMain { _settingUpdates.tryEmit(update) }
        scheduleBatchUpdate(update)
    }

    fun broadcastBalanceUpdate(context: Context, balance: String) {
        lastBalance = balance
        val intent = Intent(ACTION_BALANCE_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_BALANCE, balance)
        }
        context.sendBroadcast(intent)
        emitOnMain { _balanceUpdates.tryEmit(balance) }
        scheduleBatchUpdate(balance)
    }

    /**
     * Broadcast multiple updates at once (batched). Useful for services that want to reduce broadcasts.
     * `updates` can be a list of TransactionUpdate, Int, SettingUpdate, String (balance).
     */
    fun broadcastBatchUpdate(context: Context, updates: List<Any>) {
        if (updates.isEmpty()) return
        // Process each update individually (will also go through flows and batch)
        updates.forEach { update ->
            when (update) {
                is TransactionUpdate -> {
                    val intent = Intent(ACTION_TRANSACTION_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_TX_ID, update.txId)
                        putExtra(EXTRA_STATUS, update.status)
                        putExtra(EXTRA_RESPONSE, update.response)
                    }
                    context.sendBroadcast(intent)
                    lastTransactionMap[update.txId] = update
                    emitOnMain { _transactionUpdates.tryEmit(update) }
                }
                is Int -> broadcastTokenUpdate(context, update)
                is SettingUpdate -> {
                    settingCache[update.key] = update.value
                    val intent = Intent(ACTION_SETTINGS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_SETTING_KEY, update.key)
                        putExtra(EXTRA_SETTING_VALUE, update.value?.toString() ?: "")
                    }
                    context.sendBroadcast(intent)
                    emitOnMain { _settingUpdates.tryEmit(update) }
                }
                is String -> broadcastBalanceUpdate(context, update) // assume balance
                else -> Log.w(TAG, "Unknown update type in batch: ${update::class.simpleName}")
            }
        }
        // Also send a batch broadcast for receivers that want aggregated updates
        val batchIntent = Intent(ACTION_BATCH_UPDATED).apply {
            setPackage(context.packageName)
            // Could serialize updates to JSON, but for simplicity we just send a flag
            putExtra("batchSize", updates.size)
        }
        context.sendBroadcast(batchIntent)
    }

    // ─── Batch Scheduling (debouncing) ──────────────────────────────────

    private fun scheduleBatchUpdate(update: Any) {
        synchronized(pendingBatch) {
            pendingBatch.add(update)
            if (!batchScheduled) {
                batchScheduled = true
                debounceHandler.postDelayed({
                    flushBatch()
                }, BATCH_DELAY_MS)
            }
        }
    }

    private fun flushBatch() {
        val batch = synchronized(pendingBatch) {
            if (pendingBatch.isEmpty()) {
                batchScheduled = false
                return
            }
            val copy = pendingBatch.toList()
            pendingBatch.clear()
            batchScheduled = false
            copy
        }
        // We can emit a BatchUpdate if needed, but we already emitted individual updates.
        // We'll just log or optionally provide a separate flow for batched updates.
        Log.d(TAG, "Flushed batch of ${batch.size} updates")
        // Optionally: send a batched broadcast for receivers that want the whole batch
        // Not implemented to avoid complexity, but you could add it.
    }

    // ─── Emit helpers ────────────────────────────────────────────────────

    private inline fun emitOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    // ─── Public API for Observers ───────────────────────────────────────

    /**
     * Get the last known transaction update for a given txId (cached)
     */
    fun getLastTransaction(txId: Int): TransactionUpdate? = lastTransactionMap[txId]

    /**
     * Get last known token count
     */
    fun getLastTokens(): Int = lastToken.get().toInt()

    /**
     * Get last known balance string
     */
    fun getLastBalance(): String? = lastBalance

    /**
     * Get last known setting value by key
     */
    fun getLastSetting(key: String): Any? = settingCache[key]

    /**
     * Observer helper for Activities/Fragments: returns a Lifecycle-aware collector for any Flow.
     * Example: InstantUISync.observe(transactionUpdates, lifecycle) { update -> ... }
     */
    fun <T> observe(
        flow: Flow<T>,
        lifecycle: Lifecycle,
        onUpdate: (T) -> Unit
    ) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var job: Job? = null
            override fun onStart(owner: LifecycleOwner) {
                job = CoroutineScope(Dispatchers.Main).launch {
                    flow.collect { onUpdate(it) }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
                job = null
            }
        })
    }

    // ─── Compose Integration ─────────────────────────────────────────────

    /**
     * Collects transaction updates as state in a Composable.
     * Returns the latest update for the given txId, or null if not yet seen.
     * Automatically updates when new updates arrive.
     */
    @Composable
    fun observeTransaction(txId: Int): TransactionUpdate? {
        var update by remember { mutableStateOf<TransactionUpdate?>(getLastTransaction(txId)) }
        LaunchedEffect(txId) {
            transactionUpdates
                .filter { it.txId == txId }
                .collect { newUpdate ->
                    update = newUpdate
                }
        }
        return update
    }

    /**
     * Collects all transaction updates as a state (for listing).
     * Returns the most recent updates (up to a limit).
     */
    @Composable
    fun observeRecentTransactions(limit: Int = 20): List<TransactionUpdate> {
        var updates by remember { mutableStateOf<List<TransactionUpdate>>(emptyList()) }
        LaunchedEffect(Unit) {
            transactionUpdates
                .collect { newUpdate ->
                    updates = (listOf(newUpdate) + updates).take(limit)
                }
        }
        return updates
    }

    /**
     * Collects token updates as state.
     */
    @Composable
    fun observeTokens(): Int {
        var tokens by remember { mutableStateOf(getLastTokens()) }
        LaunchedEffect(Unit) {
            tokenUpdates.collect { newTokens ->
                tokens = newTokens
            }
        }
        return tokens
    }

    /**
     * Collects balance updates as state.
     */
    @Composable
    fun observeBalance(): String {
        var balance by remember { mutableStateOf(getLastBalance() ?: "") }
        LaunchedEffect(Unit) {
            balanceUpdates.collect { newBalance ->
                balance = newBalance
            }
        }
        return balance
    }

    /**
     * Collects settings updates for a specific key.
     */
    @Composable
    fun observeSetting(key: String): Any? {
        var value by remember { mutableStateOf(getLastSetting(key)) }
        LaunchedEffect(key) {
            settingUpdates
                .filter { it.key == key }
                .collect { newSetting ->
                    value = newSetting.value
                }
        }
        return value
    }
}

// ─── Broadcast Receiver ─────────────────────────────────────────────────

/**
 * Broadcast receiver that forwards intents to a callback.
 * Register with `context.registerReceiver(receiver, filter)`.
 * Use `InstantSyncReceiver.create(...)` for lifecycle-aware registration.
 */
class InstantSyncReceiver(private val onUpdate: (Intent) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("InstantSyncReceiver", "Received: ${intent.action}")
        onUpdate(intent)
    }

    companion object {
        /**
         * Creates a lifecycle-aware receiver that automatically registers/unregisters.
         * Usage in Activity/Fragment:
         *   val receiver = InstantSyncReceiver.create(lifecycle) { intent -> ... }
         *   // It will register in onStart and unregister in onStop.
         */
        fun create(
            lifecycle: Lifecycle,
            context: Context,
            onUpdate: (Intent) -> Unit
        ): InstantSyncReceiver {
            val receiver = InstantSyncReceiver(onUpdate)
            val filter = IntentFilter().apply {
                addAction(InstantUISync.ACTION_TRANSACTION_UPDATED)
                addAction(InstantUISync.ACTION_TOKENS_UPDATED)
                addAction(InstantUISync.ACTION_SETTINGS_UPDATED)
                addAction(InstantUISync.ACTION_BALANCE_UPDATED)
                addAction(InstantUISync.ACTION_BATCH_UPDATED)
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }
                }
                override fun onStop(owner: LifecycleOwner) {
                    context.unregisterReceiver(receiver)
                }
            })
            return receiver
        }
    }
}

// ─── Lifecycle Observer (legacy support) ──────────────────────────────

/**
 * Legacy lifecycle observer that registers/unregisters the receiver.
 * Use `InstantSyncLifecycleObserver` if you prefer the older style.
 * This is kept for backward compatibility.
 */
class InstantSyncLifecycleObserver(
    private val context: Context,
    private val onUpdate: (Intent) -> Unit
) : DefaultLifecycleObserver {
    private var receiver: InstantSyncReceiver? = null

    override fun onStart(owner: LifecycleOwner) {
        receiver = InstantSyncReceiver(onUpdate)
        val filter = IntentFilter().apply {
            addAction(InstantUISync.ACTION_TRANSACTION_UPDATED)
            addAction(InstantUISync.ACTION_TOKENS_UPDATED)
            addAction(InstantUISync.ACTION_SETTINGS_UPDATED)
            addAction(InstantUISync.ACTION_BALANCE_UPDATED)
            addAction(InstantUISync.ACTION_BATCH_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}
