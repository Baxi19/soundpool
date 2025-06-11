package pl.ukaszapps.soundpool

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.*

private val loadExecutor: Executor = Executors.newCachedThreadPool()
private val uiHandler = Handler(Looper.getMainLooper())

class SoundpoolPlugin : MethodCallHandler, FlutterPlugin {

    companion object {
        private const val CHANNEL_NAME = "pl.ukaszapps/soundpool"

        @JvmStatic
        fun registerWith(registrar: PluginRegistry.Registrar) {
            SoundpoolPlugin().apply {
                onRegister(registrar.context(), registrar.messenger())
            }
        }
    }

    private lateinit var context: Context
    private val wrappers = mutableListOf<SoundpoolWrapper>()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onRegister(binding.applicationContext, binding.binaryMessenger)
    }

    private fun onRegister(appContext: Context, messenger: BinaryMessenger) {
        context = appContext.applicationContext
        MethodChannel(messenger, CHANNEL_NAME).setMethodCallHandler(this)
        context.cacheDir?.listFiles { _, name -> name.matches(Regex("sound.*pool")) }
            ?.forEach { it.delete() }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initSoundpool" -> {
                val args = call.arguments as Map<String, Int>
                val streamType = mapStreamType(args["streamType"] ?: -1)
                val maxStreams = args["maxStreams"] ?: 1

                if (streamType != -1) {
                    val wrapper = SoundpoolWrapper(context, maxStreams, streamType)
                    wrappers.add(wrapper)
                    result.success(wrappers.lastIndex)
                } else {
                    result.success(-1)
                }
            }

            "dispose" -> {
                val poolId = (call.argument<Int>("poolId"))!!
                wrappers[poolId].dispose()
                wrappers.removeAt(poolId)
                result.success(null)
            }

            else -> {
                val poolId = (call.argument<Int>("poolId"))!!
                wrappers[poolId].onMethodCall(call, result)
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        wrappers.forEach { it.dispose() }
        wrappers.clear()
    }

    private fun mapStreamType(index: Int): Int = when (index) {
        0 -> AudioManager.STREAM_RING
        1 -> AudioManager.STREAM_ALARM
        2 -> AudioManager.STREAM_MUSIC
        3 -> AudioManager.STREAM_NOTIFICATION
        else -> -1
    }
}

private data class VolumeInfo(val left: Float = 1.0f, val right: Float = 1.0f)

private class SoundpoolWrapper(
    private val context: Context,
    private val maxStreams: Int,
    private val streamType: Int
) {

    private var soundPool: SoundPool = createSoundPool()
    private val threadPool: ExecutorService =
        ThreadPoolExecutor(1, maxStreams, 1, TimeUnit.SECONDS, LinkedBlockingDeque())
    private val loadingSounds = mutableMapOf<Int, MethodChannel.Result>()
    private val volumeSettings = mutableMapOf<Int, VolumeInfo>()

    private fun createSoundPool(): SoundPool {
        val usage = when (streamType) {
            AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            AudioManager.STREAM_ALARM -> AudioAttributes.USAGE_ALARM
            AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
            else -> AudioAttributes.USAGE_GAME
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            SoundPool.Builder()
                .setMaxStreams(maxStreams)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setLegacyStreamType(streamType)
                        .setUsage(usage)
                        .build()
                )
                .build()
        } else {
            SoundPool(maxStreams, streamType, 1)
        }.apply {
            setOnLoadCompleteListener { _, soundId, status ->
                loadingSounds.remove(soundId)?.let { result ->
                    runOnUi {
                        if (status == 0) result.success(soundId)
                        else result.error("Loading failed", "Error code: $status", null)
                    }
                }
            }
        }
    }

    fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "load" -> loadExecutor.execute {
                try {
                    val args = call.arguments as Map<String, Any>
                    val soundData = args["rawSound"] as ByteArray
                    val priority = args["priority"] as Int
                    val tempFile = createTempFile("sound", "pool", context.cacheDir)
                    FileOutputStream(tempFile).use { it.write(soundData) }
                    tempFile.deleteOnExit()

                    val soundId = soundPool.load(tempFile.absolutePath, priority)
                    if (soundId > -1) loadingSounds[soundId] = result
                    else runOnUi { result.success(soundId) }
                } catch (e: Throwable) {
                    runOnUi { result.error("Loading failure", e.message, null) }
                }
            }

            "loadUri" -> loadExecutor.execute {
                try {
                    val args = call.arguments as Map<String, Any>
                    val soundUri = args["uri"] as String
                    val priority = args["priority"] as Int

                    val soundId = URI.create(soundUri).let { uri ->
                        if (uri.scheme == "content") {
                            soundPool.load(context.contentResolver.openAssetFileDescriptor(Uri.parse(soundUri), "r"), 1)
                        } else {
                            val tempFile = createTempFile("sound", "pool", context.cacheDir)
                            FileOutputStream(tempFile).use { it.write(uri.toURL().readBytes()) }
                            tempFile.deleteOnExit()
                            soundPool.load(tempFile.absolutePath, priority)
                        }
                    }

                    if (soundId > -1) loadingSounds[soundId] = result
                    else runOnUi { result.success(soundId) }
                } catch (e: Throwable) {
                    runOnUi { result.error("URI loading failure", e.message, null) }
                }
            }

            "play" -> {
                val args = call.arguments as Map<String, Any>
                val soundId = args["soundId"] as Int
                val repeat = args["repeat"] as? Int ?: 0
                val rate = args["rate"] as? Double ?: 1.0
                val volume = volumeSettings[soundId] ?: VolumeInfo()

                runInBg {
                    val streamId = soundPool.play(soundId, volume.left, volume.right, 0, repeat, rate.toFloat())
                    runOnUi { result.success(streamId) }
                }
            }

            "pause", "resume", "stop" -> {
                val streamId = (call.argument<Int>("streamId"))!!
                runInBg {
                    when (call.method) {
                        "pause" -> soundPool.pause(streamId)
                        "resume" -> soundPool.resume(streamId)
                        "stop" -> soundPool.stop(streamId)
                    }
                    runOnUi { result.success(streamId) }
                }
            }

            "setVolume" -> {
                val args = call.arguments as Map<String, Any?>
                val volumeLeft = (args["volumeLeft"] as Double).toFloat()
                val volumeRight = (args["volumeRight"] as Double).toFloat()

                runInBg {
                    (args["streamId"] as? Int)?.let {
                        soundPool.setVolume(it, volumeLeft, volumeRight)
                    }
                    (args["soundId"] as? Int)?.let {
                        volumeSettings[it] = VolumeInfo(volumeLeft, volumeRight)
                    }
                    runOnUi { result.success(null) }
                }
            }

            "setRate" -> {
                val args = call.arguments as Map<String, Any?>
                val streamId = args["streamId"] as Int
                val rate = (args["rate"] as? Double ?: 1.0).toFloat()

                runInBg {
                    soundPool.setRate(streamId, rate)
                    runOnUi { result.success(null) }
                }
            }

            "release" -> {
                release()
                soundPool = createSoundPool()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    fun dispose() {
        release()
        threadPool.shutdownNow()
    }

    private fun release() {
        soundPool.release()
    }

    private fun runOnUi(block: () -> Unit) {
        uiHandler.post(block)
    }

    private fun runInBg(block: () -> Unit) {
        threadPool.execute(block)
    }
}

