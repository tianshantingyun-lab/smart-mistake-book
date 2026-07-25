package com.tingyun.smartmistakebook.core.export

import androidx.core.content.FileProvider

/** Distinct manifest component so capture and export providers keep separate roots and grants. */
class MistakePdfFileProvider : FileProvider()
