package org.vorpal.research.kex

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.config.FileConfig
import org.vorpal.research.kex.config.RunnerCmdConfig
import org.vorpal.research.kex.config.RuntimeConfig
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.launcher.*
import org.vorpal.research.kex.util.getPathSeparator
import java.nio.file.Files
import java.nio.file.Paths

@ExperimentalSerializationApi
@InternalSerializationApi
fun main(args: Array<String>) {
    val cmd = RunnerCmdConfig(args)
    val properties = cmd.getCmdValue("config", "kex.ini")
    kexConfig.initialize(cmd, RuntimeConfig, FileConfig(properties))

    // initialize output dir
    (cmd.getCmdValue("output")?.let { Paths.get(it) }
        ?: kexConfig.getPathValue("kex", "outputDir")
        ?: Files.createTempDirectory(Paths.get("."), "kex-output"))
        .toAbsolutePath().also {
            RuntimeConfig.setValue("kex", "outputDir", it)
        }

    val logName = kexConfig.getStringValue("kex", "log", "kex.log")
    kexConfig.initLog(logName)

    val classPaths = cmd.getCmdValue("classpath")?.split(getPathSeparator())
    require(classPaths != null, cmd::printHelp)

    val targetName = cmd.getCmdValue("target")
    require(targetName != null, cmd::printHelp)

    val launcher: KexLauncher = when (cmd.getEnumValue("mode", LaunchMode.Symbolic, ignoreCase = true)) {
        LaunchMode.Symbolic -> SymbolicLauncher(classPaths, targetName)
        LaunchMode.Concolic -> ConcolicLauncher(classPaths, targetName)
        LaunchMode.LibChecker -> LibraryCheckLauncher(classPaths, targetName)
    }
    launcher.launch()
}