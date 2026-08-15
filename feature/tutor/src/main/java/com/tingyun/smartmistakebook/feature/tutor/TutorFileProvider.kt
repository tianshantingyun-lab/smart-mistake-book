package com.tingyun.smartmistakebook.feature.tutor

import androidx.core.content.FileProvider

/**
 * FileProvider subclass for tutor module's camera capture.
 * Required because multiple FileProvider instances with the same class name cannot coexist.
 */
class TutorFileProvider : FileProvider()
