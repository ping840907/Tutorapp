package com.tutorapp.fluenttutor.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * LFM3-Audio-2B GGUF 原生推論 (移植自 Verbead 的 Lfm3GgufRunner，該實作已在實機驗證)。
 *
 * 以子行程執行預編譯的 llama.cpp 執行檔 (打包在 jniLibs，需 extractNativeLibs=true)：
 *   - liblfm3_mtmd_exec.so = llama-mtmd-cli：語音辨識 (--audio) 與文字對話 (-p)
 *   - liblfm3_tts_exec.so  = llama-tts：語音合成，24 kHz
 * 硬體後端依序嘗試 HTP0 (Hexagon NPU) → GPUOpenCL (Adreno) → CPU。
 *
 * 任一功能所有後端都失敗時丟出 [ModelNotReadyException] (含 stderr 尾端)，不回傳預設內容。
 */
object Lfm3GgufRunner {

    private const val TAG = "Lfm3GgufRunner"
    private val runMutex = Mutex()
    private val candidateBackends = listOf("HTP0", "GPUOpenCL", "CPU")

    @Volatile var activeBackendDevice: String? = null
        private set

    private val LOG_LINE = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+[IWE]\\s+.*")
    private val SPECIAL_TOKEN = Regex("<\\|[^|>]*\\|>")

    private fun resolveExecutable(context: Context, jniLibName: String, tmpBinName: String): File? {
        val fromJni = File(File(context.applicationInfo.nativeLibraryDir), jniLibName)
        if (fromJni.exists() && fromJni.canExecute()) return fromJni
        val fromTmp = File("/data/local/tmp/llama/bin/$tmpBinName")
        if (fromTmp.exists() && fromTmp.canExecute()) return fromTmp
        return null
    }

    private fun resolveLibDir(context: Context): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return if (File(nativeDir, "libggml.so").exists()) nativeDir.absolutePath
        else "/data/local/tmp/llama/lib:${nativeDir.absolutePath}"
    }

    private fun requireReady(context: Context, bin: File?, what: String): File {
        val missing = ModelStore.missingFiles(context)
        if (missing.isNotEmpty()) {
            throw ModelNotReadyException("LFM3 GGUF model files missing:\n" + missing.joinToString("\n"))
        }
        return bin ?: throw ModelNotReadyException(
            "$what executable not found in ${context.applicationInfo.nativeLibraryDir} (jniLibs missing or extractNativeLibs=false)"
        )
    }

    private fun backendOrder() =
        activeBackendDevice?.let { listOf(it) + candidateBackends.filter { b -> b != it } } ?: candidateBackends

    private fun deviceArgs(cmd: MutableList<String>, backend: String) {
        if (backend == "CPU") {
            cmd.add("-ngl"); cmd.add("0")
        } else {
            cmd.add("-dev"); cmd.add(backend)
            cmd.add("-ngl"); cmd.add("99")
        }
    }

    /** 語音辨識：16 kHz PCM → 文字。成功但沒有內容 (靜音) 回傳空字串。 */
    suspend fun transcribe(context: Context, samples16k: FloatArray): String = withContext(Dispatchers.IO) {
        if (samples16k.isEmpty()) return@withContext ""
        runMutex.withLock {
            val bin = requireReady(context, resolveExecutable(context, "liblfm3_mtmd_exec.so", "llama-mtmd-cli"), "llama-mtmd-cli")
            val wav = File(context.cacheDir, "lfm3_asr_input.wav")
            writeWav16kMono(wav, samples16k, 16_000)
            var lastErr = ""
            for (backend in backendOrder()) {
                val cmd = ArrayList<String>(24)
                cmd.add(bin.absolutePath)
                cmd.add("-m"); cmd.add(ModelStore.modelFile(context).absolutePath)
                cmd.add("--mmproj"); cmd.add(ModelStore.mmprojFile(context).absolutePath)
                deviceArgs(cmd, backend)
                cmd.addAll(listOf("-fit", "off", "--no-mmproj-offload", "--no-warmup", "-t", "8"))
                cmd.add("--audio"); cmd.add(wav.absolutePath)
                cmd.add("-sys"); cmd.add("Perform ASR.")
                cmd.add("-p"); cmd.add("<__media__>")
                cmd.addAll(listOf("--temp", "0", "-n", "96"))
                val t0 = System.currentTimeMillis()
                val r = runProcess(context, cmd)
                if (r.exitCode == 0) {
                    activeBackendDevice = backend
                    val text = cleanStdout(r.stdout).replace('\n', ' ').trim()
                    Log.i(TAG, "ASR [$backend] ${System.currentTimeMillis() - t0}ms: \"$text\"")
                    return@withLock text
                }
                lastErr = "[$backend] exit=${r.exitCode}: ${r.stderr.takeLast(300)}"
                Log.w(TAG, "ASR failed $lastErr")
            }
            throw ModelNotReadyException("LFM3 ASR failed on all backends. $lastErr")
        }
    }

    /** 文字對話：用 LFM3 自己生成回覆 (system + 使用者文字)。 */
    suspend fun chat(context: Context, system: String, userText: String, maxTokens: Int = 220): String =
        withContext(Dispatchers.IO) {
            runMutex.withLock {
                val bin = requireReady(context, resolveExecutable(context, "liblfm3_mtmd_exec.so", "llama-mtmd-cli"), "llama-mtmd-cli")
                var lastErr = ""
                for (backend in backendOrder()) {
                    val cmd = ArrayList<String>(24)
                    cmd.add(bin.absolutePath)
                    cmd.add("-m"); cmd.add(ModelStore.modelFile(context).absolutePath)
                    cmd.add("--mmproj"); cmd.add(ModelStore.mmprojFile(context).absolutePath)
                    deviceArgs(cmd, backend)
                    cmd.addAll(listOf("-fit", "off", "--no-mmproj-offload", "--no-warmup", "-t", "8"))
                    cmd.add("-sys"); cmd.add(system)
                    cmd.add("-p"); cmd.add(userText)
                    cmd.addAll(listOf("--temp", "0.4", "-n", maxTokens.toString()))
                    val t0 = System.currentTimeMillis()
                    val r = runProcess(context, cmd)
                    val text = cleanStdout(r.stdout)
                    if (r.exitCode == 0 && text.isNotBlank()) {
                        activeBackendDevice = backend
                        Log.i(TAG, "CHAT [$backend] ${System.currentTimeMillis() - t0}ms raw=\"$text\"")
                        return@withLock text
                    }
                    lastErr = "[$backend] exit=${r.exitCode} stdout=${r.stdout.takeLast(120)} stderr=${r.stderr.takeLast(300)}"
                    Log.w(TAG, "CHAT failed $lastErr")
                }
                throw ModelNotReadyException("LFM3 text generation failed on all backends. $lastErr")
            }
        }

    /** 語音合成：文字 → 24 kHz 單聲道 float PCM。language 例如 "English" / "Japanese" / "Korean" / "Chinese"。 */
    suspend fun synthesize(context: Context, text: String, language: String): FloatArray =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) throw IllegalArgumentException("empty TTS text")
            runMutex.withLock {
                val bin = requireReady(context, resolveExecutable(context, "liblfm3_tts_exec.so", "llama-tts"), "llama-tts")
                val out = File(context.cacheDir, "lfm3_tts_output.wav")
                var lastErr = ""
                for (backend in backendOrder()) {
                    if (out.exists()) out.delete()
                    val cmd = ArrayList<String>(24)
                    cmd.add(bin.absolutePath)
                    cmd.add("-m"); cmd.add(ModelStore.modelFile(context).absolutePath)
                    cmd.add("-mm"); cmd.add(ModelStore.mmprojFile(context).absolutePath)
                    deviceArgs(cmd, backend)
                    cmd.addAll(listOf("-fit", "off", "--no-mmproj-offload", "-t", "8"))
                    cmd.add("-p"); cmd.add(text)
                    cmd.add("--tts-lang"); cmd.add(language)
                    cmd.add("--tts-voice"); cmd.add("female")
                    cmd.addAll(listOf("--temp", "0.4", "--top-k", "64"))
                    cmd.add("-o"); cmd.add(out.absolutePath)
                    val t0 = System.currentTimeMillis()
                    val r = runProcess(context, cmd)
                    if (r.exitCode == 0 && out.exists() && out.length() > 100L) {
                        val samples = readWavPcm16(out)
                        if (samples != null && samples.isNotEmpty()) {
                            activeBackendDevice = backend
                            Log.i(TAG, "TTS [$backend] ${samples.size / 24000f}s audio in ${System.currentTimeMillis() - t0}ms")
                            return@withLock samples
                        }
                    }
                    lastErr = "[$backend] exit=${r.exitCode}: ${r.stderr.takeLast(300)}"
                    Log.w(TAG, "TTS failed $lastErr")
                }
                throw ModelNotReadyException("LFM3 TTS failed on all backends. $lastErr")
            }
        }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private suspend fun runProcess(context: Context, cmd: List<String>): ProcessResult {
        val pb = ProcessBuilder(cmd)
        val libDir = resolveLibDir(context)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", "$libDir:/vendor/lib64:/system/lib64")
            put("ADSP_LIBRARY_PATH", "$libDir;/vendor/dsp/cdsp;/vendor/lib64/rfsa/adsp;/system/lib64/rfsa/adsp;/dsp")
            put("GGML_OPENCL_PLATFORM", "QUALCOMM")
        }
        val process = pb.start()
        runCatching { process.outputStream.close() }
        // 協程被取消 (切換場景 / 打斷) 時殺掉子行程
        val handle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) process.destroyForcibly()
        }
        try {
            var stderrStr = ""
            val errThread = thread(start = true, name = "lfm3-stderr") {
                runCatching { stderrStr = process.errorStream.bufferedReader(Charsets.UTF_8).readText() }
            }
            val stdoutStr = runCatching { process.inputStream.bufferedReader(Charsets.UTF_8).readText() }.getOrDefault("")
            errThread.join(5000)
            return ProcessResult(process.waitFor(), stdoutStr, stderrStr)
        } finally {
            handle?.dispose()
        }
    }

    /** 去掉 llama.cpp 日誌行與 <|...|> 特殊 token，保留換行 */
    internal fun cleanStdout(raw: String): String =
        raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() && !LOG_LINE.matches(it.trim()) }
            .joinToString("\n") { SPECIAL_TOKEN.replace(it, "") }.trim()

    private fun writeWav16kMono(outFile: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataSize); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataSize)
        BufferedOutputStream(FileOutputStream(outFile), 65536).use { bos ->
            bos.write(h.array())
            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (buf.remaining() < 2) { bos.write(buf.array(), 0, buf.position()); buf.clear() }
                buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            if (buf.position() > 0) bos.write(buf.array(), 0, buf.position())
        }
    }

    private fun readWavPcm16(wav: File): FloatArray? {
        val bytes = wav.readBytes()
        if (bytes.size <= 44) return null
        var dataOffset = 44
        for (i in 12 until minOf(bytes.size - 8, 256)) {
            if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() &&
                bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()
            ) { dataOffset = i + 8; break }
        }
        val n = (bytes.size - dataOffset) / 2
        if (n <= 0) return null
        val sb = ByteBuffer.wrap(bytes, dataOffset, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { (sb.get(it) / 32768f).coerceIn(-1f, 1f) }
    }
}
