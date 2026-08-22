package com.tingyun.smartmistakebook.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.uiautomator.By
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

                navigateTo("nav_review")
                navigateTo("nav_tutor")
                navigateTo("nav_library")
                scrollVisibleList()
                navigateTo("nav_profile")
                navigateTo("nav_review")
            },
        )
    }

    private fun BaselineProfileRule.Scope.navigateTo(testTag: String) {
        runCatching {
            val selector = By.res("$packageName", testTag)
            device.findObject(selector)?.click()
            device.waitForIdle()
        }
    }

    private fun BaselineProfileRule.Scope.scrollVisibleList() {
        runCatching {
            val height = device.displayHeight
            val width = device.displayWidth
            for (i in 0 until 3) {
                device.swipe(
                    width / 2,
                    height * 3 / 4,
                    width / 2,
                    height / 4,
                    20,
                )
                device.waitForIdle()
            }
        }
    }
}
