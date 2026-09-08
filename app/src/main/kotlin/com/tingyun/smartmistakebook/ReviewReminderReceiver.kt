package com.tingyun.smartmistakebook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object ReviewReminderContract {
    const val ACTION_DAILY_REMINDER =
        "com.tingyun.smartmistakebook.action.DAILY_REVIEW_REMINDER"
    const val ACTION_OPEN_REVIEW =
        "com.tingyun.smartmistakebook.action.OPEN_REVIEW"
    const val EXTRA_OPEN_REVIEW = "open_review"
}

class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACCEPTED_ACTIONS) return
        val pendingResult = goAsync()
        val application = context.applicationContext as? SmartMistakeBookApplication
        if (application == null) {
            pendingResult.finish()
            return
        }
        application.handleReviewReminderBroadcast(intent.action, pendingResult::finish)
    }

    private companion object {
        val ACCEPTED_ACTIONS = setOf(
            ReviewReminderContract.ACTION_DAILY_REMINDER,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
