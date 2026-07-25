package com.tingyun.smartmistakebook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val reviewOpenRequests = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordReviewOpenRequest(intent)
        enableEdgeToEdge()
        setContent {
            SmartMistakeBookTheme {
                SmartMistakeBookRoot(reviewOpenRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordReviewOpenRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as SmartMistakeBookApplication).refreshStudyExperience()
    }

    private fun recordReviewOpenRequest(intent: Intent?) {
        if (
            intent?.action == ReviewReminderContract.ACTION_OPEN_REVIEW &&
            intent.getBooleanExtra(ReviewReminderContract.EXTRA_OPEN_REVIEW, false)
        ) {
            requestReviewOpen()
        }
    }

    internal fun requestReviewOpen() {
        reviewOpenRequests.value += 1L
    }
}
