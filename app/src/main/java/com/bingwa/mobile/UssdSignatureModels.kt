package com.bingwa.mobile

import androidx.annotation.Keep
import java.text.SimpleDateFormat
import java.util.*

/**
 * A single learned step from a USSD menu.
 * Enhanced with timestamps, versioning, and validation status.
 */
@Keep
data class UssdSignatureStep(
    val stepIndex: Int = 0,
    val expectedInput: String = "",
    val menuTitle: String = "",
    val menuText: String = "",
    val selectedOptionLabel: String = "",
    val menuOptionsSnapshot: List<String> = emptyList(),

    // New fields (all have defaults, so no breaking changes)
    val timestamp: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val isValidated: Boolean = false,
    val validationNotes: String = "",
    val alternativeOptions: List<String> = emptyList()
) {
    /**
     * Returns a human-readable summary of this step.
     */
    fun summary(): String = buildString {
        append("Step $stepIndex")
        if (menuTitle.isNotBlank()) append(": $menuTitle")
        if (selectedOptionLabel.isNotBlank()) append(" → $selectedOptionLabel")
        if (expectedInput.isNotBlank()) append(" (input: $expectedInput)")
        if (!isValidated) append(" [not validated]")
    }

    /**
     * Checks whether this step contains enough information to be useful.
     */
    fun isValid(): Boolean = stepIndex >= 0 && selectedOptionLabel.isNotBlank()

    companion object {
        /**
         * Creates an empty step for placeholders.
         */
        fun empty() = UssdSignatureStep()

        /**
         * Creates a step from a JSON string (if using Gson or similar).
         * This is a convenience method – you can implement your own serialization.
         */
        fun fromJson(json: String): UssdSignatureStep? = runCatching {
            // Use your JSON library (e.g., Gson, kotlinx.serialization) here.
            // For example with Gson: Gson().fromJson(json, UssdSignatureStep::class.java)
            null // Placeholder – replace with actual deserialization
        }.getOrNull()
    }
}

/**
 * A capture of a USSD popup screen during learning.
 * Now includes timestamps, capture source, and confidence scoring.
 */
@Keep
data class UssdLearningCapture(
    val stepIndex: Int = -1,
    val enteredInput: String = "",
    val selectedOptionLabel: String = "",
    val popupText: String = "",

    // New fields
    val timestamp: Long = System.currentTimeMillis(),
    val captureType: String = "auto",   // "auto", "manual", "retry"
    val confidenceScore: Float = 0f,    // 0..1, how reliable this capture is
    val validationStatus: String = ""   // "pending", "approved", "rejected"
) {
    /**
     * Returns a compact description for logging.
     */
    fun description(): String = buildString {
        append("Capture[step=$stepIndex")
        if (selectedOptionLabel.isNotBlank()) append(", option=$selectedOptionLabel")
        if (enteredInput.isNotBlank()) append(", input=$enteredInput")
        if (confidenceScore > 0f) append(", confidence=${"%.2f".format(confidenceScore)}")
        append("]")
    }

    companion object {
        fun empty() = UssdLearningCapture()
    }
}

/**
 * Final result of an advanced USSD dispatch.
 * Now includes timing, error codes, and a success flag.
 */
@Keep
data class AdvancedDispatchResult(
    val finalResponse: String,
    val changeDetected: Boolean = false,
    val autoAdjusted: Boolean = false,
    val learningCompleted: Boolean = false,
    val suggestedCode: String = "",
    val changeSummary: String = "",
    val learnedSignature: List<UssdSignatureStep> = emptyList(),
    val learningCaptures: List<UssdLearningCapture> = emptyList(),
    val popupTranscript: List<String> = emptyList(),

    // New fields
    val startTimestamp: Long = 0L,
    val endTimestamp: Long = 0L,
    val durationMs: Long = 0L,
    val errorCode: Int = 0,              // 0 = no error
    val errorMessage: String = "",
    val retryCount: Int = 0,
    val success: Boolean = false         // true if the dispatch finished without fatal errors
) {
    /**
     * Convenience method to create a failed result.
     */
    fun isFailure(): Boolean = errorCode != 0 || !success

    /**
     * Returns a human‑readable status line.
     */
    fun statusSummary(): String = when {
        success && !changeDetected -> "✅ Success (no menu change)"
        success && changeDetected && autoAdjusted -> "⚠️ Success with auto‑adjustment"
        success && changeDetected && !autoAdjusted -> "❌ Menu changed – stopped"
        !success && errorMessage.isNotBlank() -> "❌ $errorMessage"
        else -> "❓ Unknown outcome"
    }

    /**
     * Returns the elapsed time as a formatted string.
     */
    fun formattedDuration(): String = when {
        durationMs > 0 -> formatDuration(durationMs)
        startTimestamp > 0 && endTimestamp > 0 -> formatDuration(endTimestamp - startTimestamp)
        else -> "N/A"
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> "${ms / 1000}s"
        else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
    }

    companion object {
        /**
         * Creates a result for a successful dispatch.
         */
        fun success(
            finalResponse: String,
            learnedSignature: List<UssdSignatureStep> = emptyList(),
            learningCaptures: List<UssdLearningCapture> = emptyList(),
            popupTranscript: List<String> = emptyList(),
            startTimestamp: Long = System.currentTimeMillis(),
            endTimestamp: Long = System.currentTimeMillis()
        ) = AdvancedDispatchResult(
            finalResponse = finalResponse,
            changeDetected = false,
            autoAdjusted = false,
            learningCompleted = false,
            suggestedCode = "",
            changeSummary = "",
            learnedSignature = learnedSignature,
            learningCaptures = learningCaptures,
            popupTranscript = popupTranscript,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            durationMs = endTimestamp - startTimestamp,
            success = true
        )

        /**
         * Creates a result for a failed dispatch.
         */
        fun failure(
            errorMessage: String,
            errorCode: Int = 1,
            finalResponse: String = "",
            retryCount: Int = 0,
            startTimestamp: Long = System.currentTimeMillis(),
            endTimestamp: Long = System.currentTimeMillis()
        ) = AdvancedDispatchResult(
            finalResponse = finalResponse,
            changeDetected = false,
            autoAdjusted = false,
            learningCompleted = false,
            suggestedCode = "",
            changeSummary = "",
            learnedSignature = emptyList(),
            learningCaptures = emptyList(),
            popupTranscript = emptyList(),
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            durationMs = endTimestamp - startTimestamp,
            errorCode = errorCode,
            errorMessage = errorMessage,
            retryCount = retryCount,
            success = false
        )

        /**
         * Merges two results, keeping the most complete data.
         * Useful when combining partial results.
         */
        fun merge(first: AdvancedDispatchResult, second: AdvancedDispatchResult): AdvancedDispatchResult {
            // Prefer non‑empty, non‑default values from either.
            return first.copy(
                finalResponse = second.finalResponse.takeIf { it.isNotBlank() } ?: first.finalResponse,
                changeDetected = first.changeDetected || second.changeDetected,
                autoAdjusted = first.autoAdjusted || second.autoAdjusted,
                learningCompleted = first.learningCompleted || second.learningCompleted,
                suggestedCode = second.suggestedCode.takeIf { it.isNotBlank() } ?: first.suggestedCode,
                changeSummary = second.changeSummary.takeIf { it.isNotBlank() } ?: first.changeSummary,
                learnedSignature = if (second.learnedSignature.isNotEmpty()) second.learnedSignature else first.learnedSignature,
                learningCaptures = if (second.learningCaptures.isNotEmpty()) second.learningCaptures else first.learningCaptures,
                popupTranscript = if (second.popupTranscript.isNotEmpty()) second.popupTranscript else first.popupTranscript,
                errorCode = if (second.errorCode != 0) second.errorCode else first.errorCode,
                errorMessage = second.errorMessage.takeIf { it.isNotBlank() } ?: first.errorMessage,
                retryCount = maxOf(first.retryCount, second.retryCount),
                success = first.success && second.success,
                durationMs = maxOf(first.durationMs, second.durationMs)
            )
        }
    }
}