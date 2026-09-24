package com.tingyun.smartmistakebook.core.data.knowledge.dense

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * 查询文本 → L2 归一化句向量（端侧唯一入口）。
 *
 * 抽成接口的原因（不是"为了抽象"）：模型推理是 **Android-only**（LiteRT 的 `.so` 在 JVM
 * 单测里不存在），而"融合 / 扫描 / 回退"这些必须能在 JVM 上测——所以模型推理留在
 * [LiteRtDenseQueryEncoder]，JVM 测试注入假实现（含"加载失败"注入，见
 * `DenseRecallFallbackTest`）。
 */
internal interface DenseQueryEncoder {
    /** 查询向量（已 L2 归一化；失败抛异常，由上层回退到纯词面）。 */
    fun encode(text: String): FloatArray
}

/**
 * LiteRT 推理实现：**懒加载一次**，之后复用同一个 `Interpreter`。
 *
 * 模型件与运行时的关系（实测，2026-09-24）：
 * - 依赖 `com.google.ai.edge.litert:litert`（ARM64 `libLiteRt.so` 5.25 MiB、各 ABI
 *   `PT_LOAD p_align=0x4000` = 16KB 合规，本次已复核 AAR 字节）；
 * - 该 AAR 的 Java 入口是经典 `org.tensorflow.lite.Interpreter`（`litert-api` 的
 *   `CompiledModel` 是另一套新 API；本实现用前者，与"LiteRT Interpreter 加载模型"一致）。
 *
 * 模型件（2026-09-24 起随包分发）：`core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite`
 * ——由 `build/dense-model/bge-small-zh-v1.5-int8.onnx`（权重-only int8，路线 B）经
 * `tools/dense_build/freeze_onnx_static.py` 定长 512 + onnx2tf 转出，转换链与哈希见
 * `tools/dense_build/README.md` §7。模型缺失时 `openFromAssets` 抛错 ⇒ 上层回退纯词面
 * （设计内行为，见 `DenseRecallFallbackTest`）。
 *
 * 端侧推理路径**只有仪表化能覆盖**（`.so` 不在 JVM 里）：导入内容见
 * `DenseEncoderParityInstrumentedTest`——真机 290 条对拍 min cosine 0.99963、编码
 * p50 71ms / p95 76ms（API 34 x86_64 模拟器、XNNPACK 开）。
 */
internal class LiteRtDenseQueryEncoder private constructor(
    private val interpreter: Interpreter,
    private val tokenizer: DenseTokenizer,
    private val inputIdsIndex: Int,
    private val attentionMaskIndex: Int,
    private val tokenTypeIdsIndex: Int,
    private val fixedSequenceLength: Int,
    private val outputElements: Int,
    /**
     * 模型的 direct buffer，**必须与 interpreter 同寿命地持有**。
     *
     * LiteRT 不复制模型：native 侧直接引用这块内存（flatbuffer 的 op 数据指针就落在这里）。
     * 只把 buffer 传给 `Interpreter(modelBuffer, ...)` 而自己不持有强引用时，GC 会回收
     * （asset 的 mmap 随之 unmap）——表现是推理跑了若干帧后突然 SIGSEGV 在 libLiteRt.so 里
     * （2026-09-24 真机实测：3 秒左右、约第 3~4 帧崩，进程级 tombstone）。所以这里留一个字段。
     */
    private val modelBuffer: ByteBuffer,
) : DenseQueryEncoder {

    // 动态形状模型要求每次改变长度后 resize + allocate（固定形状模型不走这条）。
    private var currentSequenceLength = -1

    override fun encode(text: String): FloatArray {
        val ids = if (fixedSequenceLength > 0) {
            tokenizer.encodePadded(text, fixedSequenceLength)
        } else {
            tokenizer.encode(text)
        }
        val mask = tokenizer.attentionMask(ids)
        val tokenTypes = LongArray(ids.size)
        val inputIds = LongArray(ids.size) { ids[it].toLong() }
        if (fixedSequenceLength <= 0 && ids.size != currentSequenceLength) {
            val shape = intArrayOf(1, ids.size)
            interpreter.resizeInput(inputIdsIndex, shape)
            interpreter.resizeInput(attentionMaskIndex, shape)
            interpreter.resizeInput(tokenTypeIdsIndex, shape)
            interpreter.allocateTensors()
            currentSequenceLength = ids.size
        }
        // 输入/输出都按**张量真实形状**给二维数组（[1, N] / [1, dim]），不给一维的"宽松形式"。
        // 这条是 2026-09-24 真机实测钉出来的（当时模型件还不存在，这条路径从未跑过）：
        // 一维 int64 数组喂 [1, N] 输入张量时，LiteRT 的 Java 侧会按错的元素宽度拷进去——
        // **不抛异常**，但模型拿到的是坏 id，输出成了确定的错向量（实测 cos≈0.29），
        // 紧接着第二次 invoke 就 SIGSEGV 在 libLiteRt.so（tombstone 栈顶
        // `NativeInterpreterWrapper_run+79`）。输出侧一维更直接：抛
        // "Cannot copy from a TensorFlowLite tensor (…) with shape [1, 512] to a Java object
        // with shape [512]"。改二维后同一份模型真机 290 条对拍 min=0.99963、零崩溃。
        val inputs = arrayOfNulls<Any>(interpreter.getInputTensorCount())
        inputs[inputIdsIndex] = arrayOf(inputIds)
        inputs[attentionMaskIndex] = arrayOf(mask)
        inputs[tokenTypeIdsIndex] = arrayOf(tokenTypes)
        val output = Array(1) { FloatArray(outputElements) }
        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to output))
        return DenseQueryPostProcess.normalize(output[0])
    }

    companion object {
        private const val TAG = "DenseEncoder"

        /** 模型输入名（与 `tools/dense_build/export_bge_int8.py` 的导出签名一致）。 */
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"

        /**
         * 打开模型（一次）。`modelBytes` 必须是 direct/mapped buffer（LiteRT 要求）。
         *
         * 拒绝"定长 < [DENSE_MAX_SEQUENCE_LENGTH]"的模型：那会把长输入**静默截短**，
         * 端侧跑的就不是离线口径的输入了（宁可判不可用、退回纯词面）。
         */
        fun open(
            modelBytes: ByteBuffer,
            tokenizer: DenseTokenizer,
            threads: Int = DEFAULT_THREADS,
        ): LiteRtDenseQueryEncoder {
            val interpreter = Interpreter(
                modelBytes,
                Interpreter.Options()
                    .setNumThreads(threads),
            )
            val names = (0 until interpreter.getInputTensorCount()).map { interpreter.getInputTensor(it).name() }
            val inputIdsIndex = names.indexOf(INPUT_IDS)
            val attentionMaskIndex = names.indexOf(ATTENTION_MASK)
            val tokenTypeIdsIndex = names.indexOf(TOKEN_TYPE_IDS)
            require(inputIdsIndex >= 0 && attentionMaskIndex >= 0 && tokenTypeIdsIndex >= 0) {
                "dense model inputs must be $INPUT_IDS/$ATTENTION_MASK/$TOKEN_TYPE_IDS, found $names"
            }
            val sequenceShape = interpreter.getInputTensor(inputIdsIndex).shape()
            val fixedSequenceLength = if (sequenceShape.size == 2 && sequenceShape[1] > 0) sequenceShape[1] else -1
            require(fixedSequenceLength <= 0 || fixedSequenceLength >= DENSE_MAX_SEQUENCE_LENGTH) {
                "dense model input length $fixedSequenceLength < $DENSE_MAX_SEQUENCE_LENGTH would silently " +
                    "truncate long queries away from the offline tokenization"
            }
            val outputTensor = interpreter.getOutputTensor(0)
            Log.i(TAG, "dense model loaded: inputs=$names seqLen=${if (fixedSequenceLength > 0) fixedSequenceLength else "dynamic"}")
            return LiteRtDenseQueryEncoder(
                interpreter = interpreter,
                tokenizer = tokenizer,
                inputIdsIndex = inputIdsIndex,
                attentionMaskIndex = attentionMaskIndex,
                tokenTypeIdsIndex = tokenTypeIdsIndex,
                fixedSequenceLength = fixedSequenceLength,
                outputElements = outputTensor.numElements(),
                modelBuffer = modelBytes,
            )
        }

        const val DEFAULT_THREADS = 2

        /** 从 assets 里 mmap 模型件（24MB 级：拷进堆反而更贵）；失败退回直接缓冲。 */
        fun openFromAssets(context: Context, assetPath: String, tokenizer: DenseTokenizer): LiteRtDenseQueryEncoder {
            val mapped = runCatching {
                context.assets.openFd(assetPath).use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                        channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                    }
                }
            }.getOrNull()
            val buffer = mapped ?: context.assets.open(assetPath).use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            }
            return open(buffer, tokenizer)
        }
    }
}
