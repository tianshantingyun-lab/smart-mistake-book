package com.tingyun.smartmistakebook.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = "com.tingyun.smartmistakebook.localfirst",
            profileBlock = {
                startActivityAndWait()
                device.waitForIdle()

                // Key user paths (audit 4.3): review / tutor / library scroll / profile.
                for (testTag in listOf("nav_review", "nav_tutor", "nav_library", "nav_profile")) {
                    runCatching {
                        device.findObject(
                            By.res(packageName, testTag),
                        )?.click()
                        device.waitForIdle()
                    }
                }
                runCatching {
                    val height = device.displayHeight
                    val width = device.displayWidth
                    repeat(3) {
                        device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 20)
                        device.waitForIdle()
                    }
                }
                runCatching {
                    device.findObject(
                        By.res(packageName, "nav_review"),
                    )?.click()
                    device.waitForIdle()
                }
            },
        )
    }
}
