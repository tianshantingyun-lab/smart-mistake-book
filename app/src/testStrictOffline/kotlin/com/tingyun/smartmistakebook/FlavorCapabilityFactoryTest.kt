package com.tingyun.smartmistakebook

import org.junit.Assert.assertFalse
import org.junit.Test

class FlavorCapabilityFactoryTest {
    @Test
    fun tutorTeachingIsUnavailableWithoutAModel() {
        val capabilities = FlavorCapabilityFactory.create()

        assertFalse(capabilities.remoteModelAvailable)
        assertFalse(capabilities.tutorTeachingEnabled)
    }
}
