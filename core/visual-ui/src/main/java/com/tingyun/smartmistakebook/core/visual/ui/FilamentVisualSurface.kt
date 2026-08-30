package com.tingyun.smartmistakebook.core.visual.ui

import android.content.Context
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.model.TutorVisualCamera
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DKind
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualProjection
import com.tingyun.smartmistakebook.core.model.TutorVisualTransform3D
import com.tingyun.smartmistakebook.core.model.TutorVisualVector3
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFrame
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualLatticeCompiler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun FilamentVisualSurface(
    geometries: List<TutorVisualGeometry3DElement>,
    lattices: List<TutorVisualLatticeElement>,
    frame: TutorVisualFrame,
    camera: TutorVisualCamera,
    modifier: Modifier,
    onReadyChanged: (Boolean) -> Unit,
    onElementSelected: (String?) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(context) { FilamentVisualController(context) }
    LaunchedEffect(controller, geometries, lattices) {
        onReadyChanged(false)
        runCatching {
            val materialPackage = TutorVisualMaterialRepository.loadOrCompile(context)
            controller.setDocument(materialPackage, geometries, lattices)
        }.onSuccess {
            onReadyChanged(true)
        }.onFailure {
            onReadyChanged(false)
        }
    }
    LaunchedEffect(controller, frame, camera) {
        controller.update(frame, camera)
    }
    AndroidView(
        factory = { controller.surfaceView },
        modifier = modifier
            .pointerInput(controller) {
                detectTapGestures { offset ->
                    controller.pickAt(offset.x, offset.y)
                }
            },
    )
    DisposableEffect(controller) {
        onDispose {
            onReadyChanged(false)
            controller.destroy()
        }
    }
    // PR-11 picking: filament resolves the tap against the rendered depth
    // buffer a couple of frames after View.pick, so the callback surfaces
    // the entity asynchronously on the main looper.
    DisposableEffect(controller, onElementSelected) {
        controller.pickListener = onElementSelected
        onDispose { controller.pickListener = null }
    }
}

private class FilamentVisualController(
    context: Context,
) {
    val surfaceView = SurfaceView(context)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private val choreographer = Choreographer.getInstance()
    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    private val skybox: Skybox
    private val cameraEntity: Int
    private val records = mutableListOf<RenderableRecord>()
    private val meshes = mutableMapOf<TutorVisualGeometry3DKind, MeshResource>()
    private val materials = mutableListOf<MaterialInstance>()
    private var material: Material? = null
    private var swapChain: SwapChain? = null
    private var started = false
    private var destroyed = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    var pickListener: ((String?) -> Unit)? = null
    private val entityToElement = HashMap<Int, String>()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started || destroyed) return
            choreographer.postFrameCallback(this)
            if (uiHelper.isReadyToRender && swapChain != null) {
                if (renderer.beginFrame(requireNotNull(swapChain), frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }

    init {
        ensureFilamentInitialized()
        engine = Engine.Builder()
            .featureLevel(Engine.FeatureLevel.FEATURE_LEVEL_1)
            .build()
        engine.setAutomaticInstancingEnabled(true)
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = engine.entityManager.create()
        camera = engine.createCamera(cameraEntity)
        skybox = Skybox.Builder().color(0.965f, 0.957f, 0.925f, 1f).build(engine)
        scene.skybox = skybox
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = false
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
        start()
    }

    fun setDocument(
        packageBuffer: ByteBuffer,
        geometries: List<TutorVisualGeometry3DElement>,
        lattices: List<TutorVisualLatticeElement>,
    ) {
        if (destroyed) return
        clearDocument()
        val source = packageBuffer.duplicate().apply { rewind() }
        material = Material.Builder().payload(source, source.remaining()).build(engine)
        material?.compile(
            Material.CompilerPriorityQueue.HIGH,
            Material.UserVariantFilterBit.ALL,
            Handler(Looper.getMainLooper()),
        ) {}
        createPalette()

        geometries.forEach { geometry ->
            when (geometry.kind) {
                TutorVisualGeometry3DKind.LINE_SEGMENT,
                TutorVisualGeometry3DKind.POLYLINE,
                -> geometry.points.zipWithNext().forEachIndexed { index, (start, end) ->
                    createRenderable(
                        elementId = geometry.elementId,
                        instanceId = "${geometry.elementId}-segment-$index",
                        kind = TutorVisualGeometry3DKind.CYLINDER,
                        transform = segmentTransform(start, end),
                        paletteIndex = paletteIndex(geometry.label),
                    )
                }
                TutorVisualGeometry3DKind.GROUP -> Unit
                else -> {
                    val transforms = geometry.instanceTransforms.ifEmpty { listOf(geometry.transform) }
                    transforms.forEachIndexed { index, transform ->
                        createRenderable(
                            elementId = geometry.elementId,
                            instanceId = "${geometry.elementId}-instance-$index",
                            kind = geometry.kind,
                            transform = transform,
                            paletteIndex = paletteIndex(geometry.label) + index,
                        )
                    }
                }
            }
        }
        lattices.forEach { lattice ->
            TutorVisualLatticeCompiler.expand(lattice).forEachIndexed { index, instance ->
                createRenderable(
                    elementId = lattice.elementId,
                    instanceId = instance.elementId,
                    kind = TutorVisualGeometry3DKind.SPHERE,
                    transform = instance.transform,
                    paletteIndex = paletteIndex(instance.label) + index,
                )
            }
        }
        engine.flush()
    }

    fun update(frame: TutorVisualFrame, cameraState: TutorVisualCamera) {
        if (destroyed) return
        updateCamera(cameraState)
        records.forEach { record ->
            val state = frame.elements[record.elementId]
            val shouldShow = state?.visible != false
            if (shouldShow && !record.inScene) {
                scene.addEntity(record.entity)
                record.inScene = true
            } else if (!shouldShow && record.inScene) {
                scene.removeEntities(intArrayOf(record.entity))
                record.inScene = false
            }
            if (!shouldShow || state == null) return@forEach
            val adjusted = record.baseTransform.copy(
                translation = TutorVisualVector3(
                    record.baseTransform.translation.x +
                        (state.properties[TutorVisualBindingProperty.X] ?: 0.0),
                    record.baseTransform.translation.y +
                        (state.properties[TutorVisualBindingProperty.Y] ?: 0.0),
                    record.baseTransform.translation.z +
                        (state.properties[TutorVisualBindingProperty.Z] ?: 0.0),
                ),
                rotationDegrees = TutorVisualVector3(
                    record.baseTransform.rotationDegrees.x +
                        (state.properties[TutorVisualBindingProperty.ROTATION_X_DEGREES] ?: 0.0),
                    record.baseTransform.rotationDegrees.y +
                        (state.properties[TutorVisualBindingProperty.ROTATION_Y_DEGREES] ?: 0.0),
                    record.baseTransform.rotationDegrees.z +
                        (state.properties[TutorVisualBindingProperty.ROTATION_Z_DEGREES] ?: 0.0),
                ),
                scale = record.baseTransform.scale.let { scale ->
                    val factor = state.properties[TutorVisualBindingProperty.SCALE] ?: 1.0
                    TutorVisualVector3(scale.x * factor, scale.y * factor, scale.z * factor)
                },
            )
            val transformManager = engine.transformManager
            transformManager.setTransform(
                transformManager.getInstance(record.entity),
                adjusted.toMatrix(),
            )
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stop()
        uiHelper.detach()
        clearDocument()
        engine.destroySkybox(skybox)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    /**
     * PR-11 picking: queues a picking query at the tap position. The result
     * arrives on the main looper a couple of rendered frames later; empty
     * space resolves to null so the selection clears, mirroring the
     * fallback canvas behavior.
     */
    fun pickAt(xPx: Float, yPx: Float) {
        if (destroyed || swapChain == null) return
        val height = viewportHeight
        val x = xPx.toInt().coerceIn(0, max(0, viewportWidth - 1))
        // View.pick documents a bottom-origin y axis (GL convention);
        // Compose taps arrive top-origin, so the coordinate flips.
        val y = (height - yPx.toInt()).coerceIn(0, max(0, height - 1))
        view.pick(x, y, Handler(Looper.getMainLooper())) { result ->
            if (destroyed) return@pick
            pickListener?.invoke(entityToElement[result.renderable])
        }
    }

    private fun createPalette() {
        val source = requireNotNull(material)
        PALETTE.forEach { color ->
            materials += source.createInstance().apply {
                setParameter(
                    "baseColor",
                    Colors.RgbaType.SRGB,
                    color[0],
                    color[1],
                    color[2],
                    color[3],
                )
            }
        }
    }

    private fun createRenderable(
        elementId: String,
        instanceId: String,
        kind: TutorVisualGeometry3DKind,
        transform: TutorVisualTransform3D,
        paletteIndex: Int,
    ) {
        val resolvedKind = when (kind) {
            TutorVisualGeometry3DKind.PLANE,
            TutorVisualGeometry3DKind.GRID,
            -> TutorVisualGeometry3DKind.CUBE
            TutorVisualGeometry3DKind.AXES -> TutorVisualGeometry3DKind.CYLINDER
            TutorVisualGeometry3DKind.LINE_SEGMENT,
            TutorVisualGeometry3DKind.POLYLINE,
            TutorVisualGeometry3DKind.GROUP,
            -> return
            else -> kind
        }
        val mesh = meshes.getOrPut(resolvedKind) { createMesh(resolvedKind) }
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1.05f, 1.05f, 1.05f))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                mesh.vertexBuffer,
                mesh.indexBuffer,
                0,
                mesh.indexCount,
            )
            .material(0, materials[Math.floorMod(paletteIndex, materials.size)])
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        engine.transformManager.setTransform(
            engine.transformManager.getInstance(entity),
            transform.toMatrix(),
        )
        scene.addEntity(entity)
        entityToElement[entity] = elementId
        records += RenderableRecord(
            elementId = elementId,
            instanceId = instanceId,
            entity = entity,
            baseTransform = transform,
            inScene = true,
        )
    }

    private fun createMesh(kind: TutorVisualGeometry3DKind): MeshResource {
        val meshData = when (kind) {
            TutorVisualGeometry3DKind.SPHERE -> sphereMesh()
            TutorVisualGeometry3DKind.CYLINDER -> cylinderMesh()
            else -> cubeMesh()
        }
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(meshData.vertices.size / 3)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                3 * Float.SIZE_BYTES,
            )
            .build(engine)
        val vertexBytes = ByteBuffer.allocateDirect(meshData.vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        meshData.vertices.forEach(vertexBytes::putFloat)
        vertexBytes.flip()
        vertexBuffer.setBufferAt(engine, 0, vertexBytes)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(meshData.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val indexBytes = ByteBuffer.allocateDirect(meshData.indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        meshData.indices.forEach(indexBytes::putShort)
        indexBytes.flip()
        indexBuffer.setBuffer(engine, indexBytes)
        return MeshResource(vertexBuffer, indexBuffer, meshData.indices.size)
    }

    private fun updateCamera(state: TutorVisualCamera) {
        val azimuth = Math.toRadians(state.azimuthDegrees)
        val elevation = Math.toRadians(state.elevationDegrees)
        val horizontal = state.distance * cos(elevation)
        val eyeX = state.target.x + horizontal * sin(azimuth)
        val eyeY = state.target.y + state.distance * sin(elevation)
        val eyeZ = state.target.z + horizontal * cos(azimuth)
        camera.lookAt(
            eyeX,
            eyeY,
            eyeZ,
            state.target.x,
            state.target.y,
            state.target.z,
            0.0,
            1.0,
            0.0,
        )
        val aspect = viewportWidth.toDouble() / max(1, viewportHeight).toDouble()
        when (state.projection) {
            TutorVisualProjection.PERSPECTIVE ->
                camera.setProjection(42.0, aspect, 0.05, 1_000.0, Camera.Fov.VERTICAL)
            TutorVisualProjection.ORTHOGRAPHIC -> {
                val zoom = max(0.25, state.distance * 0.55)
                camera.setProjection(
                    Camera.Projection.ORTHO,
                    -aspect * zoom,
                    aspect * zoom,
                    -zoom,
                    zoom,
                    0.0,
                    1_000.0,
                )
            }
        }
    }

    private fun clearDocument() {
        records.forEach { record ->
            if (record.inScene) scene.removeEntities(intArrayOf(record.entity))
            engine.destroyEntity(record.entity)
            EntityManager.get().destroy(record.entity)
        }
        records.clear()
        entityToElement.clear()
        meshes.values.forEach { mesh ->
            engine.destroyVertexBuffer(mesh.vertexBuffer)
            engine.destroyIndexBuffer(mesh.indexBuffer)
        }
        meshes.clear()
        materials.forEach(engine::destroyMaterialInstance)
        materials.clear()
        material?.let(engine::destroyMaterial)
        material = null
    }

    private fun start() {
        if (started) return
        started = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stop() {
        if (!started) return
        started = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let(engine::destroySwapChain)
            var flags = uiHelper.swapChainFlags
            if (SwapChain.isSRGBSwapChainSupported(engine)) {
                flags = flags or SwapChainFlags.CONFIG_SRGB_COLORSPACE
            }
            swapChain = engine.createSwapChain(surface, flags)
            displayHelper.attach(renderer, surfaceView.display)
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            viewportWidth = max(1, width)
            viewportHeight = max(1, height)
            view.viewport = Viewport(0, 0, viewportWidth, viewportHeight)
            FilamentHelper.synchronizePendingFrames(engine)
        }
    }

    private data class MeshResource(
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
        val indexCount: Int,
    )

    private data class RenderableRecord(
        val elementId: String,
        val instanceId: String,
        @param:Entity val entity: Int,
        val baseTransform: TutorVisualTransform3D,
        var inScene: Boolean,
    )

    companion object {
        private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)
        private val PALETTE = listOf(
            floatArrayOf(0.10f, 0.50f, 0.42f, 1f),
            floatArrayOf(0.82f, 0.39f, 0.20f, 1f),
            floatArrayOf(0.28f, 0.42f, 0.68f, 1f),
            floatArrayOf(0.47f, 0.35f, 0.66f, 1f),
            floatArrayOf(0.15f, 0.18f, 0.20f, 1f),
        )

        private fun ensureFilamentInitialized() {
            if (initialized.compareAndSet(false, true)) Filament.init()
        }

        private fun paletteIndex(label: String?): Int = label?.hashCode() ?: 0
    }
}

private data class MeshData(
    val vertices: FloatArray,
    val indices: ShortArray,
)

private fun cubeMesh(): MeshData = MeshData(
    vertices = floatArrayOf(
        -0.5f, -0.5f, -0.5f,
        0.5f, -0.5f, -0.5f,
        0.5f, 0.5f, -0.5f,
        -0.5f, 0.5f, -0.5f,
        -0.5f, -0.5f, 0.5f,
        0.5f, -0.5f, 0.5f,
        0.5f, 0.5f, 0.5f,
        -0.5f, 0.5f, 0.5f,
    ),
    indices = shortArrayOf(
        0, 1, 2, 0, 2, 3,
        4, 6, 5, 4, 7, 6,
        0, 4, 5, 0, 5, 1,
        3, 2, 6, 3, 6, 7,
        1, 5, 6, 1, 6, 2,
        0, 3, 7, 0, 7, 4,
    ),
)

private fun sphereMesh(latitudeSegments: Int = 10, longitudeSegments: Int = 14): MeshData {
    val vertices = ArrayList<Float>()
    val indices = ArrayList<Short>()
    for (latitude in 0..latitudeSegments) {
        val theta = PI * latitude / latitudeSegments
        val y = cos(theta).toFloat() * 0.5f
        val ring = sin(theta).toFloat() * 0.5f
        for (longitude in 0..longitudeSegments) {
            val phi = 2.0 * PI * longitude / longitudeSegments
            vertices += (ring * cos(phi)).toFloat()
            vertices += y
            vertices += (ring * sin(phi)).toFloat()
        }
    }
    for (latitude in 0 until latitudeSegments) {
        for (longitude in 0 until longitudeSegments) {
            val first = latitude * (longitudeSegments + 1) + longitude
            val second = first + longitudeSegments + 1
            indices += first.toShort()
            indices += second.toShort()
            indices += (first + 1).toShort()
            indices += second.toShort()
            indices += (second + 1).toShort()
            indices += (first + 1).toShort()
        }
    }
    return MeshData(vertices.toFloatArray(), indices.toShortArray())
}

private fun cylinderMesh(segments: Int = 16): MeshData {
    val vertices = ArrayList<Float>()
    val indices = ArrayList<Short>()
    for (side in 0..1) {
        val y = if (side == 0) -0.5f else 0.5f
        for (segment in 0 until segments) {
            val angle = 2.0 * PI * segment / segments
            vertices += (0.5 * cos(angle)).toFloat()
            vertices += y
            vertices += (0.5 * sin(angle)).toFloat()
        }
    }
    val bottomCenter = vertices.size / 3
    vertices += 0f
    vertices += -0.5f
    vertices += 0f
    val topCenter = vertices.size / 3
    vertices += 0f
    vertices += 0.5f
    vertices += 0f
    for (segment in 0 until segments) {
        val next = (segment + 1) % segments
        val top = segment + segments
        val topNext = next + segments
        indices += segment.toShort()
        indices += top.toShort()
        indices += next.toShort()
        indices += next.toShort()
        indices += top.toShort()
        indices += topNext.toShort()
        indices += bottomCenter.toShort()
        indices += next.toShort()
        indices += segment.toShort()
        indices += topCenter.toShort()
        indices += top.toShort()
        indices += topNext.toShort()
    }
    return MeshData(vertices.toFloatArray(), indices.toShortArray())
}

private fun segmentTransform(
    start: TutorVisualVector3,
    end: TutorVisualVector3,
): TutorVisualTransform3D {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dz = end.z - start.z
    val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
    val pitch = Math.toDegrees(acos((dy / length).coerceIn(-1.0, 1.0)))
    val yaw = Math.toDegrees(kotlin.math.atan2(dx, dz))
    return TutorVisualTransform3D(
        translation = TutorVisualVector3(
            (start.x + end.x) / 2.0,
            (start.y + end.y) / 2.0,
            (start.z + end.z) / 2.0,
        ),
        rotationDegrees = TutorVisualVector3(pitch, yaw, 0.0),
        scale = TutorVisualVector3(0.035, length, 0.035),
    )
}

private fun TutorVisualTransform3D.toMatrix(): FloatArray {
    val matrix = FloatArray(16)
    Matrix.setIdentityM(matrix, 0)
    Matrix.translateM(
        matrix,
        0,
        translation.x.toFloat(),
        translation.y.toFloat(),
        translation.z.toFloat(),
    )
    Matrix.rotateM(matrix, 0, rotationDegrees.z.toFloat(), 0f, 0f, 1f)
    Matrix.rotateM(matrix, 0, rotationDegrees.y.toFloat(), 0f, 1f, 0f)
    Matrix.rotateM(matrix, 0, rotationDegrees.x.toFloat(), 1f, 0f, 0f)
    Matrix.scaleM(matrix, 0, scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat())
    return matrix
}
