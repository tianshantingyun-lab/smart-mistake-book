package com.tingyun.smartmistakebook.core.database

import android.content.Context
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityLegacyBusinessWriteBarrierOwner

/** Narrow Kotlin boundary over the hidden database-package terminal bridge. */
internal class ProductionLegacyBarrierHandoff private constructor(
    private val lease: LegacyTerminalBarrierHandoffLease,
) : AutoCloseable {
    val owner: ThreeAuthorityLegacyBusinessWriteBarrierOwner
        get() = lease.owner()

    override fun close() = lease.close()

    companion object {
        fun open(context: Context): ProductionLegacyBarrierHandoff =
            ProductionLegacyBarrierHandoff(
                LegacyTerminalBarrierHandoffLease.open(context),
            )
    }
}
