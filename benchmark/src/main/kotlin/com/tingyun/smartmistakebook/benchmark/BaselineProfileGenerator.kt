package com.tingyun.smartmistakebook.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline profile generation covering the key user paths from acceptance
 * audit §4.3: tab navigation, library scroll/search, capture import entry,
 * tutor conversation entry and the backup page.
 *
 * Every lookup uses stable Compose `testTag`s (exposed to UiAutomator via
 * `testTagsAsResourceId`). Steps are defensive (`runCatching` + waits) so a
 * fresh install without data still produces a usable profile.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            profileBlock = {
                startActivityAndWait()
                device.waitForIdle()

                // Key user paths (audit 4.3): review / tutor / library / profile.
                for (testTag in listOf("nav_review", "nav_tutor", "nav_library", "nav_profile")) {
                    clickTag(testTag)
                }
                repeat(3) { scrollUp() }
                clickTag("nav_review")

                // Capture import entry (audit 4.3): tutor → capture, then back.
                clickTag("nav_tutor")
                runCatching {
                    device.findObject(By.res(packageName, "tutor_capture_button"))?.click()
                    device.waitForIdle()
                    device.pressBack()
                    device.waitForIdle()
                }

                // Tutor conversation entry: choose-existing leads to the library.
                runCatching {
                    device.findObject(By.res(packageName, "tutor_choose_existing_button"))?.click()
                    device.waitForIdle()
                    device.pressBack()
                    device.waitForIdle()
                }

                // Library search entry: open the search field when data exists.
                clickTag("nav_library")
                runCatching {
                    device.findObject(By.res(packageName, "library_search_field"))?.click()
                    device.waitForIdle()
                    device.pressBack()
                    device.waitForIdle()
                }
                repeat(3) { scrollUp() }

                // Backup page walk (audit 4.3): profile → storage & backup.
                clickTag("nav_profile")
                runCatching {
                    device.findObject(By.res(packageName, "profile_storage_setting"))?.click()
                    device.waitForIdle()
                    device.pressBack()
                    device.waitForIdle()
                }
            },
        )
    }

    private fun MacrobenchmarkScope.clickTag(tag: String) {
        runCatching {
            device.wait(Until.hasObject(By.res(packageName, tag)), WAIT_MS)
            device.findObject(By.res(packageName, tag))?.click()
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.scrollUp() {
        runCatching {
            val height = device.displayHeight
            val width = device.displayWidth
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 20)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.tingyun.smartmistakebook.localfirst"
        const val WAIT_MS = 5_000L
    }
}
