package xmlfluss.ksp.spi

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import xmlfluss.codegen.spi.DiagnosticReporter

/** Bridges [DiagnosticReporter] to KSP's [KSPLogger]. */
class KspDiagnosticReporter(private val logger: KSPLogger) : DiagnosticReporter {

    private var errored = false

    override fun error(nativeHandle: Any?, message: String) {
        errored = true
        if (nativeHandle is KSNode) logger.error(message, nativeHandle) else logger.error(message)
    }

    override fun warn(nativeHandle: Any?, message: String) {
        if (nativeHandle is KSNode) logger.warn(message, nativeHandle) else logger.warn(message)
    }

    override fun hasErrors(): Boolean = errored
}
