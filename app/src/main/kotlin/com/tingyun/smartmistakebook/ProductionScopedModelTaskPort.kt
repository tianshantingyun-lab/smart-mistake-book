package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** UI task families. The value is an authorization label, not a model-supplied field. */
internal enum class ProductionModelTaskFeature {
    CAPTURE,
    LIBRARY,
    TUTOR,
}

/**
 * Trusted process owner that converts the global durable queue into revocable, task-family and
 * subject-scoped views. It never closes the shared queue; that remains publication-owner work.
 */
internal class ProductionScopedModelTaskPortOwner(
    private val queue: ModelTaskRepository,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val requestMonitor = Any()
    private val nextPortId = AtomicLong(0L)
    private val issuedPorts = ConcurrentHashMap.newKeySet<ProductionScopedModelTaskPort>()
    private val requestOwners = ConcurrentHashMap<String, RequestOwner>()

    fun exact(
        feature: ProductionModelTaskFeature,
        subjectId: String,
        allowedKinds: Set<ModelTaskKind>,
    ): ScopedModelTaskPort =
        issue(
            feature = feature,
            allowedKinds = allowedKinds,
            subjectPolicy = SubjectPolicy.Exact(subjectId.requireScopedSubject()),
        )

    /** Used only while a capture or saved-question owner has not returned its durable id yet. */
    fun bindOnce(
        feature: ProductionModelTaskFeature,
        allowedKinds: Set<ModelTaskKind>,
    ): ScopedModelTaskPort =
        issue(
            feature = feature,
            allowedKinds = allowedKinds,
            subjectPolicy = SubjectPolicy.BindOnce(),
        )

    /**
     * Lobby conversations are rotated by the trusted lobby lifecycle. Newer work may replace the
     * active conversation; a late request cannot rotate the port back to an older conversation.
     */
    fun rotating(
        feature: ProductionModelTaskFeature,
        allowedKinds: Set<ModelTaskKind>,
    ): ScopedModelTaskPort =
        issue(
            feature = feature,
            allowedKinds = allowedKinds,
            subjectPolicy = SubjectPolicy.RotateToNewer(),
        )

    private fun issue(
        feature: ProductionModelTaskFeature,
        allowedKinds: Set<ModelTaskKind>,
        subjectPolicy: SubjectPolicy,
    ): ScopedModelTaskPort {
        check(!closed.get()) { "Production model-task scope owner is closed" }
        require(allowedKinds.isNotEmpty()) { "A scoped model-task port requires a task family" }
        val port =
            ProductionScopedModelTaskPort(
                portId = nextPortId.incrementAndGet(),
                feature = feature,
                allowedKinds = allowedKinds.toSet(),
                subjectPolicy = subjectPolicy,
                queue = queue,
                ownerIsOpen = { !closed.get() },
                claimRequest = ::claimRequest,
                onClose = ::releasePort,
            )
        synchronized(requestMonitor) {
            check(!closed.get()) { "Production model-task scope owner is closed" }
            check(issuedPorts.add(port)) { "Scoped model-task port identity was reused" }
        }
        return port
    }

    private fun claimRequest(
        portId: Long,
        requestId: String,
        scope: ScopeKey,
    ): Boolean {
        if (closed.get()) return false
        return synchronized(requestMonitor) {
            if (closed.get() || issuedPorts.none { port -> port.portId == portId }) {
                false
            } else {
                val requestedOwner = RequestOwner(portId, scope)
                val existing = requestOwners.putIfAbsent(requestId, requestedOwner)
                existing == null || existing == requestedOwner
            }
        }
    }

    private fun releasePort(port: ProductionScopedModelTaskPort): Boolean {
        return synchronized(requestMonitor) {
            requestOwners.entries.forEach { entry ->
                if (entry.value.portId == port.portId) {
                    requestOwners.remove(entry.key, entry.value)
                }
            }
            issuedPorts.remove(port)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val ports = synchronized(requestMonitor) { issuedPorts.toList() }
        ports.forEach(ProductionScopedModelTaskPort::close)
        synchronized(requestMonitor) {
            issuedPorts.toList().forEach(issuedPorts::remove)
            requestOwners.clear()
        }
    }
}

private class ProductionScopedModelTaskPort(
    val portId: Long,
    private val feature: ProductionModelTaskFeature,
    private val allowedKinds: Set<ModelTaskKind>,
    private val subjectPolicy: SubjectPolicy,
    private val queue: ModelTaskRepository,
    private val ownerIsOpen: () -> Boolean,
    private val claimRequest: (Long, String, ScopeKey) -> Boolean,
    private val onClose: (ProductionScopedModelTaskPort) -> Boolean,
) : ScopedModelTaskPort, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override suspend fun capabilities(): ProviderCapabilitySnapshot {
        requireOpen()
        val capabilities = queue.capabilities()
        requireOpen()
        return capabilities.copy(
            supportedTasks = capabilities.supportedTasks intersect allowedKinds,
        )
    }

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> {
        if (!isOpen() || requestId.isBlank()) return flowOf(null)
        return queue.observe(requestId).map { snapshot ->
            snapshot?.takeIf { candidate ->
                candidate.request.requestId == requestId && authorizeExisting(candidate)
            }
        }
    }

    override fun observeBySubject(
        subjectId: String,
        kind: ModelTaskKind,
    ): Flow<List<ModelTaskSnapshot>> {
        if (!isOpen() || kind !in allowedKinds || !subjectPolicy.matches(subjectId)) {
            return flowOf(emptyList())
        }
        return queue.observeBySubject(subjectId, kind).map { snapshots ->
            snapshots.filter(::authorizeExisting)
        }
    }

    override fun observeRecentBySubject(
        subjectId: String,
        kind: ModelTaskKind,
        limit: Int,
    ): Flow<List<ModelTaskSnapshot>> {
        require(limit > 0) { "Recent model-task limit must be positive" }
        if (!isOpen() || kind !in allowedKinds || !subjectPolicy.matches(subjectId)) {
            return flowOf(emptyList())
        }
        return queue.observeRecentBySubject(subjectId, kind, limit).map { snapshots ->
            snapshots.filter(::authorizeExisting).takeLast(limit)
        }
    }

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
        val scope = authorizeNew(request)
            ?: throw ScopedModelTaskAccessException()
        if (!claimRequest(portId, request.requestId, scope)) throw ScopedModelTaskAccessException()
        queue.execute(request).collect { snapshot ->
            if (!isOpen() || !snapshot.matches(scope, allowedKinds, request.requestId)) {
                throw ScopedModelTaskAccessException()
            }
            emit(snapshot)
        }
    }

    override suspend fun cancel(requestId: String) {
        if (!isOpen() || requestId.isBlank()) return
        val snapshot = queue.observe(requestId).firstOrNull() ?: return
        if (snapshot.request.requestId != requestId) return
        if (!authorizeExisting(snapshot)) return
        requireOpen()
        queue.cancel(requestId)
    }

    override fun executeTutorStream(
        request: ModelTaskRequest,
        identity: TutorStreamIdentity,
    ): Flow<TutorStreamEvent> = flow {
        if (identity.requestId != request.requestId) throw ScopedModelTaskAccessException()
        val scope = authorizeNew(request)
            ?: throw ScopedModelTaskAccessException()
        if (!claimRequest(portId, request.requestId, scope)) throw ScopedModelTaskAccessException()
        queue.executeTutorStream(request, identity).collect { event ->
            if (!isOpen() || event.identity != identity) {
                throw ScopedModelTaskAccessException()
            }
            emit(event)
        }
    }

    private fun authorizeNew(request: ModelTaskRequest): ScopeKey? {
        if (!isOpen() || request.input.kind !in allowedKinds) return null
        val subjectId = request.input.subjectId.requireScopedSubject()
        if (!subjectPolicy.authorizeNew(subjectId, request.occurredAtEpochMillis)) return null
        return ScopeKey(feature, subjectId, request.input.kind)
    }

    private fun authorizeExisting(snapshot: ModelTaskSnapshot): Boolean {
        if (!isOpen()) return false
        val kind = snapshot.request.input.kind
        val subjectId = snapshot.request.input.subjectId
        if (kind !in allowedKinds || !subjectPolicy.matches(subjectId)) return false
        val scope = ScopeKey(feature, subjectId, kind)
        return claimRequest(portId, snapshot.request.requestId, scope)
    }

    private fun ModelTaskSnapshot.matches(
        scope: ScopeKey,
        allowed: Set<ModelTaskKind>,
        expectedRequestId: String,
    ): Boolean =
        request.input.kind in allowed &&
            request.input.kind == scope.kind &&
            request.input.subjectId == scope.subjectId &&
            request.requestId == expectedRequestId

    private fun requireOpen() {
        check(isOpen()) { "Scoped model-task port is closed" }
    }

    private fun isOpen(): Boolean = !closed.get() && ownerIsOpen()

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose(this)
    }
}

private sealed interface SubjectPolicy {
    fun matches(subjectId: String): Boolean

    fun authorizeNew(subjectId: String, occurredAtEpochMillis: Long): Boolean

    class Exact(
        private val subjectId: String,
    ) : SubjectPolicy {
        override fun matches(subjectId: String): Boolean = this.subjectId == subjectId

        override fun authorizeNew(subjectId: String, occurredAtEpochMillis: Long): Boolean =
            matches(subjectId)
    }

    class BindOnce : SubjectPolicy {
        private val subjectId = AtomicReference<String?>(null)

        override fun matches(subjectId: String): Boolean = this.subjectId.get() == subjectId

        override fun authorizeNew(subjectId: String, occurredAtEpochMillis: Long): Boolean {
            val current = this.subjectId.get()
            return if (current == null) this.subjectId.compareAndSet(null, subjectId) else current == subjectId
        }
    }

    class RotateToNewer : SubjectPolicy {
        private val monitor = Any()
        private var subjectId: String? = null
        private var newestOccurredAt: Long = -1L

        override fun matches(subjectId: String): Boolean =
            synchronized(monitor) { this.subjectId == subjectId }

        override fun authorizeNew(subjectId: String, occurredAtEpochMillis: Long): Boolean {
            require(occurredAtEpochMillis >= 0L)
            return synchronized(monitor) {
                val currentSubject = this.subjectId
                if (currentSubject == subjectId) {
                    newestOccurredAt = maxOf(newestOccurredAt, occurredAtEpochMillis)
                    true
                } else if (currentSubject != null && occurredAtEpochMillis <= newestOccurredAt) {
                    false
                } else {
                    this.subjectId = subjectId
                    newestOccurredAt = occurredAtEpochMillis
                    true
                }
            }
        }
    }
}

private data class ScopeKey(
    val feature: ProductionModelTaskFeature,
    val subjectId: String,
    val kind: ModelTaskKind,
)

private data class RequestOwner(
    val portId: Long,
    val scope: ScopeKey,
)

internal class ScopedModelTaskAccessException : SecurityException(
    "Model task is outside the current feature subject scope",
)

private fun String.requireScopedSubject(): String = also { value ->
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) { "Model-task subject scope is invalid" }
}

internal val CAPTURE_UI_MODEL_TASK_KINDS: Set<ModelTaskKind> =
    setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE)

internal val LIBRARY_UI_MODEL_TASK_KINDS: Set<ModelTaskKind> =
    setOf(ModelTaskKind.PROBLEM_CLASSIFY, ModelTaskKind.PROBLEM_RELATE)

internal val TUTOR_SESSION_UI_MODEL_TASK_KINDS: Set<ModelTaskKind> =
    setOf(
        ModelTaskKind.TUTOR_PLAN,
        ModelTaskKind.TUTOR_RESPOND,
        ModelTaskKind.TUTOR_VISUAL_GENERATE,
        ModelTaskKind.TUTOR_VISUAL_REVIEW,
    )

internal val TUTOR_LOBBY_UI_MODEL_TASK_KINDS: Set<ModelTaskKind> =
    setOf(ModelTaskKind.TUTOR_LOBBY)
