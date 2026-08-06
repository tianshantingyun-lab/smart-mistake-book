package com.tingyun.smartmistakebook.core.database

import java.lang.reflect.Proxy
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedTutorSessionDatabaseCapabilityTest {
    @Test
    fun externallyImplementedStudyDatabasePortCannotReceiveTrustedTutorSessionCapability() {
        val forgedPort =
            Proxy.newProxyInstance(
                StudyDatabasePort::class.java.classLoader,
                arrayOf(StudyDatabasePort::class.java),
            ) { _, method, _ ->
                error("Unexpected forged-port call to ${method.name}")
            } as StudyDatabasePort

        assertThrows(IllegalArgumentException::class.java) {
            StudyDatabaseFactory.authorizeTutorSession(forgedPort)
        }
    }
}
