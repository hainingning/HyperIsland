package io.github.hyperisland.xposed.hook

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses the plugin's own cancellable continuation and dispatcher, without mixing the
 * module's Kotlin runtime with the plugin's renamed Kotlin/coroutines classes.
 *
 * Only the three verified, mutex-free completion bodies may use this bridge, before
 * their label-0 body executes. A failure input must always reach the original body.
 */
internal class FocusTemplateCompletionPause private constructor(
    private val continuationClass: Class<*>,
    private val failureClass: Class<*>,
    private val interceptedMethod: Method,
    private val continuationConstructor: Constructor<*>,
    private val initCancellabilityMethod: Method,
    private val getResultMethod: Method,
    private val resumeMethod: Method,
    private val cancellationMethod: Method,
    private val functionClass: Class<*>,
    private val pluginUnit: Any,
    val suspendedMarker: Any,
) {
    fun isFailure(input: Any?): Boolean = failureClass.isInstance(input)

    /**
     * Construct before registering the waiter. Neither cancellation nor resume can
     * dispatch this continuation until [Pause.getResult] makes it suspended.
     */
    fun create(work: Any, input: Any?): Pause? {
        if (isFailure(input) || !continuationClass.isInstance(work)) return null
        val intercepted = invoke(interceptedMethod, null, work)
        if (!continuationClass.isInstance(intercepted)) return null
        val continuation = try {
            // MODE_CANCELLABLE = 1 in the inspected plugin runtime.
            continuationConstructor.newInstance(intercepted, 1)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        val pause = Pause(continuation, input)
        try {
            invoke(initCancellabilityMethod, continuation)
        } catch (error: Throwable) {
            // No getResult call has happened: a synchronous resume cannot dispatch
            // work, and releases a parent registration if initialization reached it.
            try {
                pause.resume()
                pause.getResult()
            } catch (cleanupError: Throwable) {
                if (cleanupError !== error) error.addSuppressed(cleanupError)
            }
            throw error
        }
        return pause
    }

    inner class Pause internal constructor(
        private val continuation: Any,
        private val input: Any?,
    ) {
        private val resumed = AtomicBoolean()
        private val cancellationRegistered = AtomicBoolean()

        /**
         * The gate must remove its pending request before calling this. The native
         * implementation handles a race with parent cancellation; the CAS prevents
         * two ordinary gate resolutions from resuming the same continuation.
         */
        fun resume() {
            if (resumed.compareAndSet(false, true)) {
                invoke(resumeMethod, continuation, input)
            }
        }

        /**
         * Return the exact marker to suspend. A synchronous non-marker result means
         * the hook must proceed with the original body. Cancellation is deliberately
         * unwrapped and thrown, never converted into a successful skipped body.
         */
        fun getResult(): Any? = invoke(getResultMethod, continuation)

        /**
         * Register only AFTER gate.park succeeds. Native registration immediately
         * invokes this handler when cancellation already happened. The callback only
         * forgets the waiter; native cancellation performs the actual resumption.
         *
         * Registration failure leaves the bounded gate deadline as a fallback.
         */
        fun onCancellation(action: () -> Unit): Boolean {
            if (!cancellationRegistered.compareAndSet(false, true)) return false
            return try {
                val callback = Proxy.newProxyInstance(
                    functionClass.classLoader,
                    arrayOf(functionClass),
                ) { proxy, method, args ->
                    when {
                        method.name == "invoke" && method.parameterCount == 1 -> {
                            // Bookkeeping must not make cancellation of the plugin's
                            // parent Job fail with a CancellationHandlerException.
                            try {
                                action()
                            } catch (_: Exception) {
                                // The same waiter is still bounded by the deadline.
                            }
                            pluginUnit
                        }
                        method.name == "equals" && method.parameterCount == 1 ->
                            proxy === args?.get(0)
                        method.name == "hashCode" && method.parameterCount == 0 ->
                            System.identityHashCode(proxy)
                        method.name == "toString" && method.parameterCount == 0 ->
                            "FocusTemplateCompletionCancellation"
                        else -> throw UnsupportedOperationException(method.name)
                    }
                }
                invoke(cancellationMethod, continuation, callback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        private const val VERIFIED_PLUGIN_VERSION = "17.1.3.76.0"

        /**
         * Names/signatures were verified against MIUISystemUIPlugin 17.1.3.76.0:
         * intercepted -> CancellableContinuationImpl(mode=1) -> initCancellability
         * -> getResult. Unknown versions or any signature mismatch fail open.
         */
        fun prepare(
            loader: ClassLoader,
            pluginVersionName: String,
        ): FocusTemplateCompletionPause? {
            if (pluginVersionName != VERIFIED_PLUGIN_VERSION) return null
            return try {
                fun load(name: String): Class<*> = Class.forName(name, false, loader)
                fun method(
                    owner: Class<*>,
                    name: String,
                    result: Class<*>,
                    static: Boolean,
                    vararg parameters: Class<*>,
                ): Method = owner.getMethod(name, *parameters).also {
                    check(it.returnType == result)
                    check(Modifier.isStatic(it.modifiers) == static)
                }

                val objectClass = Any::class.java
                val voidClass = java.lang.Void.TYPE
                val continuationClass = load("K0.d")
                val contextClass = load("K0.g")
                val implementationClass = load("f1.l")
                val functionClass = load("kotlin.jvm.functions.Function1")
                val failureClass = load("G0.j\$b")
                val unitClass = load("G0.s")
                val markerClass = load("L0.a")
                check(continuationClass.isInterface && contextClass.isInterface)
                check(functionClass.isInterface && continuationClass.isAssignableFrom(implementationClass))
                check(failureClass.getField("a").type == Throwable::class.java)
                method(continuationClass, "getContext", contextClass, false)
                method(continuationClass, "resumeWith", voidClass, false, objectClass)
                method(functionClass, "invoke", objectClass, false, objectClass)
                val intercepted = method(
                    load("L0.b"), "b", continuationClass, true, continuationClass,
                )
                val constructor = implementationClass.getConstructor(
                    continuationClass, Integer.TYPE,
                )
                val init = method(implementationClass, "A", voidClass, false)
                val result = method(implementationClass, "x", objectClass, false)
                val resume = method(
                    implementationClass, "resumeWith", voidClass, false, objectClass,
                )
                val cancel = method(
                    implementationClass, "f", voidClass, false, functionClass,
                )
                val unitField = unitClass.getField("a")
                check(unitField.type == unitClass && Modifier.isStatic(unitField.modifiers))
                val unit = checkNotNull(unitField.get(null))
                val markerField = markerClass.getField("a")
                check(markerField.type == markerClass && Modifier.isStatic(markerField.modifiers))
                val marker = checkNotNull(markerField.get(null))
                check((marker as? Enum<*>)?.name == "COROUTINE_SUSPENDED")
                val markerAccessor = method(load("L0.c"), "c", objectClass, true)
                check(invoke(markerAccessor, null) === marker)
                FocusTemplateCompletionPause(
                    continuationClass, failureClass, intercepted, constructor, init,
                    result, resume, cancel, functionClass, unit, marker,
                )
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: LinkageError) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? =
            try {
                method.invoke(receiver, *args)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
    }
}
