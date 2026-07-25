package com.tingyun.smartmistakebook

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MistakeExportNavigationInstrumentedTest {
    @Test
    fun exportRouteRoundTripsEveryExactIdentityAsUriArguments() {
        val key = MistakeRevisionKey(
            entryId = "entry/%2F 学生",
            problemId = "problem:函数/一",
            problemRevisionId = "revision?3#确认",
        )

        val route = Routes.mistakeExport(key)
        val segments = route.split('/')
        val navigationDecodedArguments = segments.drop(2).map(Uri::decode)
        val restored = Routes.decodeMistakeExportKey(
            entryId = navigationDecodedArguments[0],
            problemId = navigationDecodedArguments[1],
            problemRevisionId = navigationDecodedArguments[2],
        )

        assertEquals(5, segments.size)
        assertEquals(key, restored)
        assertFalse(route.contains(" 学生"))
        assertFalse(route.contains("函数/一"))
        assertFalse(route.contains("revision?3"))
    }
}
