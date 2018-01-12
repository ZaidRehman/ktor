package io.ktor.network.util

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.internal.*
import kotlin.coroutines.experimental.intrinsics.*
import java.io.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*

internal class IOCoroutineDispatcher(private val nThreads: Int) : CoroutineDispatcher(), Closeable {
    private val dispatcherThreadGroup = ThreadGroup(ioThreadGroup, "io-pool-group-sub")
    @Volatile
    private var closed = false

    init {
        require(nThreads > 0) { "nThreads should be positive but $nThreads specified"}
    }

    private val threads = (1..nThreads).map {
        IOThread().apply { start() }
    }
    private val parked = ArrayChannel<IOThread>(nThreads)
    private val rnd = Random()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val current = Thread.currentThread() as? IOThread
        if (current != null && current.threadGroup === dispatcherThreadGroup && current.taskQueue.isEmpty) {
            if (tryDispatch(current, block)) return
        }

        while (true) {
            parked.poll()?.let { if (tryDispatch(it, block)) return }

            if (current != null && tryDispatch(current, block)) return

            val random = threads[rnd.nextInt(nThreads)]
            if (tryDispatch(random, block)) return
            if (closed) {
                DefaultDispatcher.dispatch(context, block)
                return
            }
        }
    }

    private fun tryDispatch(th: IOThread, block: Runnable): Boolean {
        if (th.taskQueue.addLast(block)) {
            th.resumeSuspension()
            return true
        }

        return false
    }

    override fun close() {
        closed = true
        threads.forEach {
            it.taskQueue.close()
        }
    }

    internal inner class IOThread : Thread(dispatcherThreadGroup, "io-thread") {
        @Volatile
        @JvmField
        var cont : Continuation<Unit>? = null
        val taskQueue = LockFreeMPSCQueue<Runnable>()

        init {
            isDaemon = true
        }

        fun resumeSuspension() {
            if (cont != null) {
                ThreadCont.getAndSet(this, null)?.resume(Unit)
            }
        }

        override fun run() {
            runBlocking {
                try {
                    while (true) {
                        val task = receiveOrNull() ?: break
                        run(task)
                    }
                } catch (t: Throwable) {
                    println("thread died: $t")
                }
            }
        }

        @Suppress("NOTHING_TO_INLINE")
        private suspend inline fun receiveOrNull(): Runnable? {
            val r = taskQueue.removeFirstOrNull()
            if (r != null) return r
            return receiveSuspend()
        }

        @Suppress("NOTHING_TO_INLINE")
        private suspend inline fun receiveSuspend(): Runnable? {
            do {
                val t = taskQueue.removeFirstOrNull()
                if (t != null) return t
                if (closed) return null
                await()
            } while (true)
        }

        private val awaitSuspendBlock = { c: Continuation<Unit>? ->
            // nullable param is to avoid null check
            // we know that it is always non-null
            // and it will never crash if it is actually null
            val ThreadCont = ThreadCont
            if (!ThreadCont.compareAndSet(this, null, c)) throw IllegalStateException()
            if (!taskQueue.isEmpty && ThreadCont.compareAndSet(this, c, null)) Unit
            else {
                parked.offer(this)
                COROUTINE_SUSPENDED
            }
        }

        private suspend fun await() {
            return suspendCoroutineOrReturn(awaitSuspendBlock)
        }

        private fun run(task: Runnable) {
            try {
                task.run()
            } catch (t: Throwable) {
            }
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private val ThreadCont =
                AtomicReferenceFieldUpdater.newUpdater<IOThread, Continuation<*>>(IOThread::class.java, Continuation::class.java, IOThread::cont.name)
        as AtomicReferenceFieldUpdater<IOThread, Continuation<Unit>?>
    }
}
