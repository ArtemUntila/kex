package org.jetbrains.research.kex.trace.runner

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.serialization.KexSerializer
import org.jetbrains.research.kex.trace.symbolic.ExecutionResult
import org.jetbrains.research.kex.util.getPathSeparator
import org.jetbrains.research.kthelper.KtException
import org.jetbrains.research.kthelper.logging.log
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

class ExecutionException(cause: Throwable) : KtException("", cause)

class SymbolicExternalTracingRunner(val ctx: ExecutionContext) {
    private val outputDir = kexConfig.getPathValue("kex", "outputDir")!!
    private val traceFile = outputDir.resolve("trace.json").toAbsolutePath()
    private val executorPath = (kexConfig.getPathValue(
        "kex", "executorPath"
    ) ?: Paths.get("kex-executor/target/kex-executor-0.0.1-jar-with-dependencies.jar")).toAbsolutePath()
    private val executorKlass = "org.jetbrains.research.kex.KexExecutorKt"
    private val instrumentedCodeDir = outputDir.resolve(
        kexConfig.getStringValue("output", "instrumentedDir", "instrumented")
    ).toAbsolutePath()
    private val compiledCodeDir = outputDir.resolve(
        kexConfig.getStringValue("compile", "compileDir", "compiled")
    ).toAbsolutePath()
    private val executionClassPath = listOf(
        executorPath,
        instrumentedCodeDir,
        compiledCodeDir
    )

    @ExperimentalSerializationApi
    @InternalSerializationApi
    fun run(klass: String, setup: String, test: String): ExecutionResult {
        val pb = ProcessBuilder(
            "java",
            "-classpath", executionClassPath.joinToString(getPathSeparator()),
            executorKlass,
            "--classpath", ctx.classPath.joinToString(getPathSeparator()),
            "--package", ctx.pkg.toString().replace('/', '.'),
            "--class", klass,
            "--setup", setup,
            "--test", test,
            "--output", "$traceFile"
        )
        log.debug("Executing process with command ${pb.command().joinToString(" ")}")

        val process = pb.start()
        process.waitFor()

//        if (process.exitValue() != 0)
//            throw ExecutionException(process.errorStream.bufferedReader().readText())

        return KexSerializer(ctx.cm).fromJson<ExecutionResult>(traceFile.readText()).also {
            traceFile.deleteIfExists()
        }
    }
}