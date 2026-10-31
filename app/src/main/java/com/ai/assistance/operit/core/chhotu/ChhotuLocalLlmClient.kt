package com.ai.assistance.operit.core.chhotu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.stream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** The offline model process died (native crash / killed for memory) while answering. */
class ChhotuLocalLlmCrashedException(message: String) : IOException(message)

/** The offline model process answered with an error (model failed to load / generate). */
class ChhotuLocalLlmFailedException(message: String) : IOException(message)

/**
 * Main-process side of [ChhotuLocalLlmService]. Keeps one binding to the ":llm" process and streams
 * replies back. If that process dies mid-reply the stream fails with [ChhotuLocalLlmCrashedException]
 * and the app itself keeps running.
 */
object ChhotuLocalLlmClient {
    private const val TAG = "ChhotuLlmClient"
    private const val CONNECT_TIMEOUT_MS = 20_000L

    private sealed class Event {
        data class Chunk(val text: String) : Event()
        data class Done(val providerModel: String) : Event()
        data class Failed(val error: String) : Event()
        data class Died(val why: String) : Event()
    }

    private val connectLock = Mutex()
    @Volatile private var service: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var connectWaiter: CompletableDeferred<Messenger>? = null
    private val pending = ConcurrentHashMap<Int, Channel<Event>>()
    private val nextId = AtomicInteger(1)

    private val replyHandler: Handler by lazy {
        Handler(Looper.getMainLooper()) { msg ->
            val ch = pending[msg.arg1]
            if (ch != null) {
                val data = msg.data ?: Bundle()
                when (msg.what) {
                    ChhotuLocalLlmService.MSG_CHUNK ->
                        ch.trySend(Event.Chunk(data.getString(ChhotuLocalLlmService.KEY_TEXT).orEmpty()))
                    ChhotuLocalLlmService.MSG_DONE ->
                        ch.trySend(Event.Done(data.getString(ChhotuLocalLlmService.KEY_PROVIDER_MODEL).orEmpty()))
                    ChhotuLocalLlmService.MSG_ERROR ->
                        ch.trySend(Event.Failed(data.getString(ChhotuLocalLlmService.KEY_ERROR).orEmpty()))
                }
            }
            true
        }
    }
    private val replyMessenger: Messenger by lazy { Messenger(replyHandler) }

    private fun onProcessDied(why: String) {
        AppLogger.w(TAG, "CHHOTU_LLM_PROCESS_DIED $why (pending=${pending.size})")
        service = null
        pending.values.forEach { it.trySend(Event.Died(why)) }
    }

    private suspend fun ensureConnected(context: Context): Messenger {
        service?.let { if (it.binder.isBinderAlive) return it }
        return connectLock.withLock {
            service?.let { if (it.binder.isBinderAlive) return@withLock it }
            val waiter = CompletableDeferred<Messenger>()
            connectWaiter = waiter
            if (connection == null) {
                val appContext = context.applicationContext
                val conn =
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (binder == null) return
                            val m = Messenger(binder)
                            try {
                                binder.linkToDeath({ onProcessDied("binderDied") }, 0)
                            } catch (e: RemoteException) {
                                onProcessDied("dead on connect")
                                return
                            }
                            service = m
                            AppLogger.i(TAG, "connected to offline model process")
                            connectWaiter?.complete(m)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            onProcessDied("disconnected")
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            onProcessDied("bindingDied")
                            runCatching { appContext.unbindService(this) }
                            connection = null
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            connectWaiter?.completeExceptionally(IOException("offline model service returned no binder"))
                        }
                    }
                val ok =
                    appContext.bindService(
                        Intent(appContext, ChhotuLocalLlmService::class.java),
                        conn,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                    )
                if (!ok) {
                    runCatching { appContext.unbindService(conn) }
                    throw ChhotuLocalLlmCrashedException("could not start the offline model process")
                }
                connection = conn
            }
            // already bound: after a crash the system restarts the service and calls onServiceConnected again
            service?.let { if (it.binder.isBinderAlive) return@withLock it }
            withTimeout(CONNECT_TIMEOUT_MS) { waiter.await() }
        }
    }

    /** Streams a reply from the on-device model running in the ":llm" process. */
    fun generate(
        context: Context,
        localConfig: ModelConfigData,
        history: List<PromptTurn>,
        maxTokens: Int = 320,
        onProviderModel: (String) -> Unit = {},
        crashProbe: Boolean = false,
    ): Stream<String> = stream {
        val id = nextId.getAndIncrement()
        val ch = Channel<Event>(Channel.UNLIMITED)
        pending[id] = ch
        var finished = false
        var target: Messenger? = null
        try {
            target = ensureConnected(context)
            val roles = history.map {
                when (it.kind) {
                    PromptTurnKind.SYSTEM -> "system"
                    PromptTurnKind.ASSISTANT, PromptTurnKind.TOOL_CALL -> "assistant"
                    else -> "user"
                }
            }
            val data =
                Bundle().apply {
                    putString(ChhotuLocalLlmService.KEY_PROVIDER, localConfig.apiProviderTypeId)
                    putString(ChhotuLocalLlmService.KEY_MODEL, localConfig.modelName)
                    putInt(
                        ChhotuLocalLlmService.KEY_THREADS,
                        if (localConfig.apiProviderTypeId.trim().equals("MNN", ignoreCase = true)) {
                            localConfig.mnnThreadCount
                        } else {
                            localConfig.llamaThreadCount
                        },
                    )
                    putInt(ChhotuLocalLlmService.KEY_CTX, localConfig.llamaContextSize)
                    putInt(ChhotuLocalLlmService.KEY_MAX_TOKENS, maxTokens)
                    if (crashProbe) putBoolean(ChhotuLocalLlmService.KEY_CRASH_PROBE, true)
                    putStringArray(ChhotuLocalLlmService.KEY_ROLES, roles.toTypedArray())
                    putStringArray(ChhotuLocalLlmService.KEY_CONTENTS, history.map { it.content }.toTypedArray())
                }
            val msg = Message.obtain(null, ChhotuLocalLlmService.MSG_GENERATE, id, 0)
            msg.data = data
            msg.replyTo = replyMessenger
            try {
                target.send(msg)
            } catch (e: RemoteException) {
                onProcessDied("send failed")
                throw ChhotuLocalLlmCrashedException("offline model process is not running")
            }
            while (!finished) {
                when (val ev = ch.receive()) {
                    is Event.Chunk -> emit(ev.text)
                    is Event.Done -> {
                        finished = true
                        onProviderModel(ev.providerModel)
                    }
                    is Event.Failed -> {
                        finished = true
                        throw ChhotuLocalLlmFailedException(ev.error)
                    }
                    is Event.Died -> {
                        finished = true
                        throw ChhotuLocalLlmCrashedException("offline model process stopped (${ev.why})")
                    }
                }
            }
        } finally {
            if (!finished) {
                // caller cancelled: stop generation in the other process
                runCatching {
                    target?.send(Message.obtain(null, ChhotuLocalLlmService.MSG_CANCEL, id, 0))
                }
            }
            pending.remove(id)
            ch.close()
        }
    }

    fun cancelAll() {
        val s = service ?: return
        pending.keys.forEach { id ->
            runCatching { s.send(Message.obtain(null, ChhotuLocalLlmService.MSG_CANCEL, id, 0)) }
        }
    }
}
