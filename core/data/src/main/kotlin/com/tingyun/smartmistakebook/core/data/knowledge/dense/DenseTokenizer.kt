package com.tingyun.smartmistakebook.core.data.knowledge.dense

/**
 * bge-small-zh-v1.5 的 WordPiece 分词器（端侧唯一实现）。
 *
 * **为什么必须逐条对拍**（spec §3.2）：离线侧那组数（融合主集 0.7333）是用
 * `tools/dense_build/export_bge_int8.py` 里的 `transformers` tokenizer 编出来的；端侧
 * tokenizer 只要有一处类判定不同，端侧跑的就**不是同一个模型**，离线数字搬不过来。
 * 对拍 fixture 与生成脚本见 `tools/dense_build/gen_tokenizer_fixture.py` +
 * `core/data/src/test/resources/dense/tokenizer-parity-*.txt`。
 *
 * **参考语义**（2026-09-24 实测 transformers 5.17.0 / tokenizers 0.23.2）：`BertTokenizer`
 * 是 `TokenizersBackend`，即 Rust 侧
 * `BertNormalizer(clean_text=True, handle_chinese_chars=True, strip_accents=False, lowercase=True)`
 * → `BertPreTokenizer()` → `WordPiece(prefix="##", unk="[UNK]", max_input_chars_per_word=100)`
 * → `TemplateProcessing("[CLS] $A [SEP]")`。本文件按该 Rust 源码（v0.23.2 的
 * `normalizers/bert.rs` / `pre_tokenizers/bert.rs` / `models/wordpiece/mod.rs`）逐条移植：
 *
 * 1. `clean_text`：删 `U+0000`、`U+FFFD` 与**类别属于 Cc/Cf/Cn/Co** 的字符（`\t\n\r` 例外，
 *    不算控制符），再把白空格（`\t\n\r` 或 Unicode White_Space）统一成半角空格；
 * 2. `handle_chinese_chars`：CJK 块两侧插空格（注意 `0x20000..=0x2A6DF` 等**星光平面**区间
 *    ——Kotlin 的 `Char` 是 UTF-16 码元，必须按**码点**迭代，否则星光平面 CJK 判定会漏）；
 * 3. `strip_accents=False`（`export_bge_int8.py` 显式传的，取自 `sentence_bert_config.json`
 *    的 `do_lower_case=true` 只影响 lowercase）⇒ **不做 NFD 去音标**；
 * 4. `lowercase`：Kotlin `String.lowercase()`（= `Locale.ROOT` 全量大小写映射）；
 * 5. `BertPreTokenizer`：先按空白切（分隔符丢弃），再按 `ASCII 标点 ∪ Unicode P*` 切
 *    （标点**独立成段**，保留）；
 * 6. WordPiece：单段码点数 > 100 ⇒ 整段 `[UNK]`；否则"最长匹配 + `##` 续接前缀"贪心切分，
 *    任一步匹配不到 ⇒ 整段 `[UNK]`（不是部分 `[UNK]`）；
 * 7. 后处理 + 截断：`[CLS] + ids + [SEP]`，总长 ≤ 512（特殊符**占额度**；实测 513 字中文 ⇒ 512）。
 *
 * 空串/纯空白输入在参考实现里没有定义（tokenizers 后端对 `""` 直接 `TypeError`），端侧只要求
 * **不崩**：归一化后无段 ⇒ 返回 `[CLS][SEP]`（纯空白输入与参考行为一致）。
 */
internal class DenseTokenizer(
    private val vocab: DenseVocab,
    private val maxLength: Int = DENSE_MAX_SEQUENCE_LENGTH,
) {
    private val clsId = vocab.requireId(CLS_TOKEN)
    private val sepId = vocab.requireId(SEP_TOKEN)
    private val unkId = vocab.requireId(UNK_TOKEN)
    val padId: Int = vocab.requireId(PAD_TOKEN)

    /** `[CLS] ids [SEP]`，长度 ≤ [maxLength]（特殊符占额度）。 */
    fun encode(text: String): IntArray {
        val limit = maxLength - 2
        val ids = ArrayList<Int>(minOf(limit, 64) + 2)
        ids.add(clsId)
        var taken = 0
        var cursor = 0
        while (cursor < text.length && taken < limit) {
            val special = specialTokenAt(text, cursor)
            if (special != null) {
                // 特殊符是**在原文上大小写敏感地**整段认出来的（实测：`[CLS]` 触发、`[cls]` 不触发，
                // 且不需要词边界——`a[CLS]b` 也触发）：命中的位置直接产出该 token id，
                // 不参与归一化/切段/WordPiece。
                ids.add(special.second)
                taken++
                cursor += special.first.length
                continue
            }
            val next = nextSpecialTokenStart(text, cursor)
            for (piece in preTokenize(normalize(text.substring(cursor, next)))) {
                for (token in wordPiece(piece)) {
                    if (taken == limit) break
                    ids.add(token)
                    taken++
                }
                if (taken == limit) break
            }
            cursor = next
        }
        ids.add(sepId)
        return ids.toIntArray()
    }

    /**
     * 编码 + 右侧 PAD 到 [length]（定长输入后端用）。
     *
     * [length] 不许小于本串编码长度：定长模型比 512 短时**不截断后假装对齐**——那会让端侧
     * 输入与参考口径不一致而不报错（调用方按"模型的定长 < 512"直接判不可用）。
     */
    fun encodePadded(text: String, length: Int): IntArray {
        val ids = encode(text)
        require(ids.size <= length) {
            "encoded length ${ids.size} exceeds the model input length $length"
        }
        return if (ids.size == length) ids else IntArray(length) { index -> if (index < ids.size) ids[index] else padId }
    }

    /** 注意力掩码（右 PAD ⇒ 尾部 0）。 */
    fun attentionMask(ids: IntArray): LongArray = LongArray(ids.size) { if (ids[it] == padId) 0L else 1L }

    // ---- 正文：与 Rust 参考逐条同语义 ----

    /** 词表里的特殊符（= 参考 tokenizer 的 added vocabulary，5 个；都在词表里各占一行）。 */
    private val specialTokens: List<Pair<String, Int>> = SPECIAL_TOKENS.map { it to vocab.requireId(it) }

    /** 该位置是否正好是一个特殊符（**大小写敏感**，与参考一致）。 */
    private fun specialTokenAt(text: String, index: Int): Pair<String, Int>? =
        specialTokens.firstOrNull { text.startsWith(it.first, index) }

    /** 下一个特殊符的起点（没有则文末）。 */
    private fun nextSpecialTokenStart(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            if (specialTokenAt(text, index) != null) return index
            index++
        }
        return text.length
    }

    internal fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length + 8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint != 0 && codePoint != 0xFFFD && !isOtherCategory(codePoint)) {
                if (isWhiteSpace(codePoint)) cleaned.append(' ') else cleaned.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        val spaced = StringBuilder(cleaned.length + 16)
        var cursor = 0
        while (cursor < cleaned.length) {
            val codePoint = cleaned.codePointAt(cursor)
            if (isChineseChar(codePoint)) {
                spaced.append(' ').appendCodePoint(codePoint).append(' ')
            } else {
                spaced.appendCodePoint(codePoint)
            }
            cursor += Character.charCount(codePoint)
        }
        return lowercase(spaced.toString())
    }

    internal fun preTokenize(normalized: String): List<String> {
        if (normalized.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        for (word in normalized.split(' ')) {
            if (word.isEmpty()) continue
            splitOnPunctuation(word, pieces)
        }
        return pieces
    }

    private fun splitOnPunctuation(word: String, out: MutableList<String>) {
        var start = 0
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            if (isBertPunctuation(codePoint)) {
                if (index > start) out.add(word.substring(start, index))
                out.add(word.substring(index, index + Character.charCount(codePoint)))
                start = index + Character.charCount(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        if (start < word.length) out.add(word.substring(start))
    }

    internal fun wordPiece(piece: String): List<Int> {
        val codePoints = piece.codePoints().toArray()
        if (codePoints.size > MAX_INPUT_CHARS_PER_WORD) return listOf(unkId)
        val out = ArrayList<Int>(codePoints.size)
        var start = 0
        while (start < codePoints.size) {
            var end = codePoints.size
            var matched: Int? = null
            while (start < end) {
                val candidate = buildString(end - start + 2) {
                    if (start > 0) append(CONTINUING_SUBWORD_PREFIX)
                    for (position in start until end) appendCodePoint(codePoints[position])
                }
                val id = vocab.idOf(candidate)
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            if (matched == null) return listOf(unkId)
            out.add(matched)
            start = end
        }
        return out
    }
}

/** 词表（`vocab.txt` 的行序即 id）。 */
internal class DenseVocab private constructor(
    private val idByToken: Map<String, Int>,
    val size: Int,
) {
    fun idOf(token: String): Int? = idByToken[token]

    fun requireId(token: String): Int = idByToken[token]
        ?: error("vocab is missing the required token $token")

    companion object {
        /**
         * 行序即 id；**同 token 重复出现时取后者**——与 HF `load_vocab`
         * （`vocab[token] = index` 逐行覆盖）同语义。
         * 顺带吃掉行尾 `\r`：词表是随包分发的文本件，CRLF 检出会让每个 token 都带 `\r`
         * （不剥则全部 `[UNK]`，是静默灾难）；词表字节身份另有 sha256 测试钉住。
         */
        fun fromLines(lines: List<String>): DenseVocab {
            val map = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { index, raw -> map[raw.removeSuffix("\r")] = index }
            return DenseVocab(map, lines.size)
        }
    }
}

// ---- 参考语义的常量与类判定（Rust 侧同名函数逐条移植）----

internal const val CLS_TOKEN = "[CLS]"
internal const val SEP_TOKEN = "[SEP]"
internal const val UNK_TOKEN = "[UNK]"
internal const val PAD_TOKEN = "[PAD]"
internal const val MASK_TOKEN = "[MASK]"
internal const val CONTINUING_SUBWORD_PREFIX = "##"
internal const val MAX_INPUT_CHARS_PER_WORD = 100

/** 序列上限（含 `[CLS]`/`[SEP]`）：bge-small-zh-v1.5 的 `max_position_embeddings`。 */
internal const val DENSE_MAX_SEQUENCE_LENGTH = 512

/** 参考 tokenizer 的特殊符（added vocabulary）：`special_tokens_map.json` 的 5 个。 */
internal val SPECIAL_TOKENS = listOf(CLS_TOKEN, SEP_TOKEN, UNK_TOKEN, PAD_TOKEN, MASK_TOKEN)

/**
 * 逐码点的**全量**小写映射：`Character.toLowerCase` + 唯一的无条件多字符映射（`U+0130`）。
 *
 * 为什么不用 `String.lowercase()`：Java 的实现带 **Final_Sigma 语境规则**（词尾 Σ → ς），
 * Rust 的 `str::to_lowercase` 不带——实测参考侧 `Σ ΟΣ ΟΣΑ` → `σ οσ οσα`（不是 `ος`、`οςα`），
 * 用 Java 的会差一个字符（tokenizer 对拍 fixture 的 `p_unicode_greek_sigma` 就是这一条）。
 * 其余差异（立陶宛语/土耳其语的条件映射）在 root locale 下不适用。
 */
internal fun lowercase(value: String): String {
    val out = StringBuilder(value.length + 4)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        when (codePoint) {
            0x0130 -> out.append('i').append('\u0307') // İ → i + COMBINING DOT ABOVE（无条件映射）
            else -> out.appendCodePoint(Character.toLowerCase(codePoint))
        }
        index += Character.charCount(codePoint)
    }
    return out.toString()
}

/** Rust `tokenizers::normalizers::bert::is_whitespace`：`\t\n\r` 或 Unicode White_Space。 */
internal fun isWhiteSpace(codePoint: Int): Boolean = when (codePoint) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D -> true
    0x20, 0x85, 0xA0, 0x1680 -> true
    in 0x2000..0x200A -> true
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/**
 * Rust `tokenizers::normalizers::bert::is_control`：`\t\n\r` **不算**控制符，其余按
 * `unicode_categories::is_other()` 判定。
 *
 * **实测校正**（对拍 fixture 的 `p_unassigned`）：`U+0378`（Cn / 未分配）被参考**保留**，
 * 所以这里的集合是 **Cc ∪ Cf ∪ Co**，不含 Cn——与"按类别名直译"的直觉相反，但这就是参考行为
 * （`unicode_categories` 的 `is_other` 与 Unicode 表的版本共同决定）。
 * 残余风险：Java 的 Unicode 表版本与 Rust crate 的不完全一致，"未分配"判定在跨版本新增码点上
 * 可能各异——本仓库的语料（中文教材题面/词条）不落在这些码点上，且 fixture 覆盖的 333 条已全绿。
 */
internal fun isOtherCategory(codePoint: Int): Boolean = when (codePoint) {
    0x09, 0x0A, 0x0D -> false
    else -> when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        -> true
        else -> false
    }
}

/** Rust `tokenizers::normalizers::bert::is_chinese_char` 的区间表（逐字照抄，含 `0x2B920`）。 */
internal fun isChineseChar(codePoint: Int): Boolean = when (codePoint) {
    in 0x4E00..0x9FFF -> true
    in 0x3400..0x4DBF -> true
    in 0x20000..0x2A6DF -> true
    in 0x2A700..0x2B73F -> true
    in 0x2B740..0x2B81F -> true
    in 0x2B920..0x2CEAF -> true
    in 0xF900..0xFAFF -> true
    in 0x2F800..0x2FA1F -> true
    else -> false
}

/**
 * Rust `tokenizers::pre_tokenizers::bert::is_bert_punc` =
 * `char::is_ascii_punctuation(x) || x.is_punctuation()`；
 * ASCII 标点即 `U+0021..U+002F / U+003A..U+0040 / U+005B..U+0060 / U+007B..U+007E`。
 */
internal fun isBertPunctuation(codePoint: Int): Boolean {
    if (codePoint in 0x21..0x2F || codePoint in 0x3A..0x40 ||
        codePoint in 0x5B..0x60 || codePoint in 0x7B..0x7E
    ) {
        return true
    }
    return when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}
