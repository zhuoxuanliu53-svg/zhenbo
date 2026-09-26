package app.jingting.localplayer.subtitle

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.*

/** Horizontal subtitle detector and CTC recognizer. No network or SDK telemetry. */
class OfflineOcr(context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(1) }
    private val detector = environment.createSession(context.assets.open("ocr/det.onnx").use { it.readBytes() }, options)
    private val recognizer = environment.createSession(context.assets.open("ocr/rec.onnx").use { it.readBytes() }, options)
    private val dictionary = listOf("") + context.assets.open("ocr/characters.txt").bufferedReader().use { it.readLines() } + listOf(" ")

    fun recognize(image: Bitmap): String {
        val scale = minOf(960f / maxOf(image.width, image.height), 2f)
        val width = (image.width * scale / 32).roundToInt().coerceAtLeast(1) * 32
        val height = (image.height * scale / 32).roundToInt().coerceAtLeast(1) * 32
        val resized = Bitmap.createScaledBitmap(image, width, height, true)
        val pixels = IntArray(width * height); resized.getPixels(pixels, 0, width, 0, 0, width, height)
        if (resized !== image) resized.recycle()
        val values = FloatArray(3 * pixels.size)
        val means = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
        for (c in 0..2) for (i in pixels.indices) values[c * pixels.size + i] = (((pixels[i] shr (c * 8)) and 255) / 255f - means[c]) / std[c]
        val boxes = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
            detector.run(mapOf(detector.inputNames.first() to tensor)).use { output ->
                val result = output[0] as OnnxTensor; val shape = result.info.shape
                val h = shape[shape.size - 2].toInt(); val w = shape.last().toInt()
                val data = FloatArray(h * w); result.floatBuffer.get(data)
                regions(data, w, h).map { Rect((it.left.toFloat() / w * image.width).toInt().coerceAtLeast(0), (it.top.toFloat() / h * image.height).toInt().coerceAtLeast(0), ceil(it.right.toFloat() / w * image.width).toInt().coerceAtMost(image.width), ceil(it.bottom.toFloat() / h * image.height).toInt().coerceAtMost(image.height)) }
            }
        }
        // Detached punctuation is often a separate detection component. Join it to
        // its text line before recognition rather than interpreting it as a word.
        val merged = mutableListOf<Rect>()
        for (box in boxes.sortedBy { it.left }) {
            val target = merged.firstOrNull { existing ->
                val height = maxOf(existing.height(), box.height())
                abs(existing.centerY() - box.centerY()) < height / 2 && box.left <= existing.right + height / 2 && box.right >= existing.left - height / 2
            }
            if (target == null) merged += Rect(box) else target.union(box)
        }
        val lines = mutableListOf<Pair<Rect, String>>()
        for (box in merged) {
            if (box.width() < 2 || box.height() < 2) continue
            val crop = Bitmap.createBitmap(image, box.left, box.top, box.width(), box.height())
            try { val text = recognizeLine(crop); if (text.isNotBlank()) lines += box to text } finally { crop.recycle() }
        }
        val ordered = lines.sortedBy { it.first.centerY() }
        val groups = mutableListOf<MutableList<Pair<Rect, String>>>()
        for (line in ordered) {
            val group = groups.lastOrNull()
            if (group != null && abs(group.first().first.centerY() - line.first.centerY()) < maxOf(group.first().first.height(), line.first.height()) / 2) group += line
            else groups += mutableListOf(line)
        }
        return groups.joinToString("\n") { group -> group.sortedBy { it.first.left }.joinToString(" ") { it.second } }
    }

    private fun recognizeLine(bitmap: Bitmap): String {
        val width = ceil(bitmap.width * 48.0 / bitmap.height).toInt().coerceIn(16, 3200)
        val resized = Bitmap.createScaledBitmap(bitmap, width, 48, true)
        val pixels = IntArray(width * 48); resized.getPixels(pixels, 0, width, 0, 0, width, 48)
        if (resized !== bitmap) resized.recycle()
        val values = FloatArray(pixels.size * 3)
        for (c in 0..2) for (i in pixels.indices) values[c * pixels.size + i] = (((pixels[i] shr (c * 8)) and 255) / 127.5f - 1f)
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 48, width.toLong())).use { input ->
            recognizer.run(mapOf(recognizer.inputNames.first() to input)).use { output ->
                val tensor = output[0] as OnnxTensor; val shape = tensor.info.shape
                val steps = shape[1].toInt(); val classes = shape[2].toInt(); val buffer = tensor.floatBuffer
                val row = FloatArray(classes); val text = StringBuilder(); var previous = 0; var confidence = 0f; var count = 0
                repeat(steps) {
                    buffer.get(row); var best = 0
                    for (index in 1 until classes) if (row[index] > row[best]) best = index
                    if (best != 0 && best != previous) { text.append(dictionary.getOrElse(best) { "" }); confidence += row[best]; count++ }
                    previous = best
                }
                if (count > 0 && confidence / count >= .65f) text.toString().trim() else ""
            }
        }
    }

    private fun regions(data: FloatArray, width: Int, height: Int): List<Rect> {
        val visited = BooleanArray(data.size); val queue = IntArray(data.size); val boxes = mutableListOf<Rect>()
        for (i in data.indices) {
            if (visited[i] || data[i] < .3f) continue
            var head = 0; var tail = 1; queue[0] = i; visited[i] = true
            var left = i % width; var right = left; var top = i / width; var bottom = top; var score = 0f
            while (head < tail) {
                val p = queue[head++]; val x = p % width; val y = p / width
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y); score += data[p]
                for (n in intArrayOf(if (x > 0) p - 1 else -1, if (x < width - 1) p + 1 else -1, if (y > 0) p - width else -1, if (y < height - 1) p + width else -1)) {
                    if (n >= 0 && !visited[n] && data[n] >= .3f) { visited[n] = true; queue[tail++] = n }
                }
            }
            if (tail < 8 || score / tail < .6f || right - left < 3 || bottom - top < 2) continue
            val padding = ((bottom - top + 1) * .45f).roundToInt().coerceAtLeast(2)
            val horizontalPadding = ((bottom - top + 1) * .85f).roundToInt().coerceAtLeast(padding)
            boxes += Rect((left - horizontalPadding).coerceAtLeast(0), (top - padding).coerceAtLeast(0), (right + horizontalPadding + 1).coerceAtMost(width), (bottom + padding + 1).coerceAtMost(height))
            if (boxes.size >= 100) break
        }
        return boxes
    }
    override fun close() { recognizer.close(); detector.close(); options.close() }
}
