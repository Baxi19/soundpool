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
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.*

private val loadExecutor: Executor = Executors.newCachedThreadPool()
private val uiHandler = Handler(Looper.getMainLooper())

class SoundpoolPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    companion object {
        private const val CHANNEL_NAME = "pl.ukaszapps/soundpool"
    }

    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val wrappers = mutableListOf<SoundpoolWrapper>()

    // Flutter Plugin V2: se dispara al adjuntar al engine
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)
        // limpiar caché de sesiones anteriores
        context.cacheDir
            .listFiles { _, name -> name.matches(Regex("sound.*pool")) }
            ?.forEach { it.delete() }
    }

    // Flutter Plugin V2: se dispara al desacoplar del engine
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        wrappers.forEach { it.dispose() }
        wrappers.clear()
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
                val poolId = call.argument<Int>("poolId")!!
                wrappers[poolId].dispose()
                wrappers.removeAt(poolId)
                result.success(null)
            }

            else -> {
                val poolId = call.argument<Int>("poolId")!!
                wrappers[poolId].onMethodCall(call, result)
            }
        }
    }

    private fun mapStreamType(index: Int): Int = when (index) {
        0 -> AudioManager.STREAM_RING
        1 -> AudioManager.STREAM_ALARM
        2 -> AudioManager.STREAM_MUSIC
        3 -> AudioManager.STREAM_NOTIFICATION
        else -> -1
    }
}

// -------------------------------------
// Helpers internos
// -------------------------------------

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
                    uiHandler.post {
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
                    val data = args["rawSound"] as ByteArray
                    val priority = args["priority"] as Int
                    val tmp = createTempFile("sound", "pool", context.cacheDir)
                    FileOutputStream(tmp).use { it.write(data) }
                    tmp.deleteOnExit()

                    val soundId = soundPool.load(tmp.absolutePath, priority)
                    if (soundId > -1) loadingSounds[soundId] = result
                    else uiHandler.post { result.success(soundId) }
                } catch (e: Throwable) {
                    uiHandler.post { result.error("Load error", e.message, null) }
                }
            }

            "loadUri" -> loadExecutor.execute {
                try {
                    val args = call.arguments as Map<String, Any>
                    val uriStr = args["uri"] as String
                    val priority = args["priority"] as Int
                    val soundId = URI.create(uriStr).let { uri ->
                        if (uri.scheme == "content") {
                            soundPool.load(
                                context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r"),
                                1
                            )
                        } else {
                            val tmp = createTempFile("sound", "pool", context.cacheDir)
                            FileOutputStream(tmp).use { it.write(uri.toURL().readBytes()) }
                            tmp.deleteOnExit()
                            soundPool.load(tmp.absolutePath, priority)
                        }
                    }
                    if (soundId > -1) loadingSounds[soundId] = result
                    else uiHandler.post { result.success(soundId) }
                } catch (e: Throwable) {
                    uiHandler.post { result.error("URI load error", e.message, null) }
                }
            }

            "play", "pause", "resume", "stop", "setVolume", "setRate", "release" -> {
                // redirige internamente a cada función
                handleControl(call, result)
            }

            else -> result.notImplemented()
        }
    }

    private fun handleControl(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "play" -> {
                val args = call.arguments as Map<String, Any>
                val soundId = args["soundId"] as Int
                val repeat = args["repeat"] as? Int ?: 0
                val rate = (args["rate"] as? Double ?: 1.0).toFloat()
                val vol = volumeSettings[soundId] ?: VolumeInfo()

                threadPool.execute {
                    val streamId = soundPool.play(soundId, vol.left, vol.right, 0, repeat, rate)
                    uiHandler.post { result.success(streamId) }
                }
            }
            "pause", "resume", "stop" -> {
                val streamId = call.argument<Int>("streamId")!!
                threadPool.execute {
                    when (call.method) {
                        "pause" -> soundPool.pause(streamId)
                        "resume" -> soundPool.resume(streamId)
                        "stop" -> soundPool.stop(streamId)
                    }
                    uiHandler.post { result.success(streamId) }
                }
            }
            "setVolume" -> {
                val args = call.arguments as Map<String, Any?>
                val left = (args["volumeLeft"] as Double).toFloat()
                val right = (args["volumeRight"] as Double).toFloat()
                threadPool.execute {
                    (args["streamId"] as? Int)?.let { soundPool.setVolume(it, left, right) }
                    (args["soundId"] as? Int)?.let { volumeSettings[it] = VolumeInfo(left, right) }
                    uiHandler.post { result.success(null) }
                }
            }
            "setRate" -> {
                val streamId = call.argument<Int>("streamId")!!
                val rate = (call.argument<Double>("rate") ?: 1.0).toFloat()
                threadPool.execute {
                    soundPool.setRate(streamId, rate)
                    uiHandler.post { result.success(null) }
                }
            }
            "release" -> {
                soundPool.release()
                soundPool = createSoundPool()
                result.success(null)
            }
        }
    }

    fun dispose() {
        soundPool.release()
        threadPool.shutdownNow()
    }
}
