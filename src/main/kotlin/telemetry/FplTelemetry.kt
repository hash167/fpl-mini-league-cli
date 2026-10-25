package telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin OTel API wrapper. When the Java agent is not attached (no Honeycomb key),
 * GlobalOpenTelemetry is a no-op and the app still starts.
 */
object FplTelemetry {
    private val log = LoggerFactory.getLogger(FplTelemetry::class.java)
    private val installed = AtomicBoolean(false)

    private val tracer = GlobalOpenTelemetry.getTracer("fpl-web")
    private val meter = GlobalOpenTelemetry.getMeter("fpl-web")

    val sampleRefreshErrors: LongCounter = meter
        .counterBuilder("fpl.sample.refresh.errors")
        .setDescription("Live overall sample refresh failures")
        .build()

    val evaluateErrors: LongCounter = meter
        .counterBuilder("fpl.sample.evaluate.errors")
        .setDescription("evaluateLiveSquad failures during sample refresh")
        .build()

    val httpErrors: LongCounter = meter
        .counterBuilder("fpl.http.errors")
        .setDescription("Mapped HTTP error responses")
        .build()

    val uncaughtErrors: LongCounter = meter
        .counterBuilder("fpl.uncaught.errors")
        .setDescription("Uncaught exceptions that may precede process death")
        .build()

    init {
        try {
            meter.gaugeBuilder("fpl.up")
                .setDescription("Process liveness gauge (1 while running)")
                .ofLongs()
                .buildWithCallback { it.record(1) }
        } catch (_: Exception) {
            // no-op provider
        }
    }

    fun installProcessHooks() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordUncaught(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        Runtime.getRuntime().addShutdownHook(Thread({
            log.info("SIGTERM/shutdown: flushing OpenTelemetry (kill -9 / OOM may drop in-flight spans)")
            forceFlush()
        }, "otel-flush"))
    }

    fun <T> span(name: String, block: (Span) -> T): T {
        val span = tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan()
        try {
            return span.makeCurrent().use { block(span) }
        } catch (e: Exception) {
            recordException(span, e)
            throw e
        } finally {
            span.end()
        }
    }

    fun recordException(span: Span, e: Throwable) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR, e.message ?: e.javaClass.simpleName)
    }

    fun recordUncaught(thread: Thread, throwable: Throwable) {
        log.error("Uncaught exception on {} — process may die", thread.name, throwable)
        try {
            uncaughtErrors.add(1, Attributes.of(AttributeKey.stringKey("thread"), thread.name))
            val span = tracer.spanBuilder("fpl.uncaught.exception")
                .setParent(Context.root())
                .startSpan()
            try {
                span.setAttribute("thread.name", thread.name)
                recordException(span, throwable)
                span.addEvent("uncaught_exception")
            } finally {
                span.end()
            }
            forceFlush()
        } catch (e: Exception) {
            log.warn("Failed to export uncaught-exception telemetry: {}", e.message)
        }
    }

    fun incrementSampleRefreshError(e: Throwable) {
        try {
            sampleRefreshErrors.add(1)
            val span = Span.current()
            if (span.spanContext.isValid) {
                recordException(span, e)
            }
        } catch (_: Exception) {
        }
    }

    fun incrementEvaluateError() {
        try {
            evaluateErrors.add(1)
        } catch (_: Exception) {
        }
    }

    fun incrementHttpError(status: Int) {
        try {
            httpErrors.add(1, Attributes.of(AttributeKey.longKey("http.status_code"), status.toLong()))
        } catch (_: Exception) {
        }
    }

    /**
     * Best-effort flush. Agent-injected SdkTracerProvider has forceFlush; a no-op provider does not.
     */
    fun forceFlush() {
        try {
            val provider = GlobalOpenTelemetry.get().tracerProvider
            val flush = provider.javaClass.methods.firstOrNull { it.name == "forceFlush" && it.parameterCount == 0 }
            val result = flush?.invoke(provider)
            val wait = result?.javaClass?.methods?.firstOrNull {
                it.name == "join" && it.parameterCount == 2
            }
            if (wait != null) {
                wait.invoke(result, 5L, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            log.debug("OTel forceFlush skipped: {}", e.message)
        }
    }
}
