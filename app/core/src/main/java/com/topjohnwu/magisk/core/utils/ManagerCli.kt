package com.topjohnwu.magisk.core.utils

import com.topjohnwu.magisk.core.AppBinaryPath
import java.io.IOException

class ManagerCliException(
    val status: ManagerCli.Status,
    val exitCode: Int,
    val commandErrors: List<String>,
) : IOException(
    "Manager command failed: $status (exit=$exitCode)" +
        commandErrors.firstOrNull()?.let { ": $it" }.orEmpty()
)

/** Runs privileged management requests as the signed Android manager UID. */
object ManagerCli {

    enum class Status {
        SUCCESS,
        ACCESS_DENIED,
        DAEMON_UNAVAILABLE,
        PROTOCOL_MISMATCH,
        DATABASE_FAILURE,
        COMMAND_FAILED,
        TRANSPORT_FAILURE,
    }

    data class Result(
        val code: Int,
        val output: List<String>,
        val errors: List<String>,
        val status: Status,
    ) {
        val isSuccess get() = status == Status.SUCCESS

        fun requireSuccess(): Result {
            if (!isSuccess) {
                throw ManagerCliException(status, code, errors)
            }
            return this
        }
    }

    fun execute(vararg args: String): Result = runCatching {
        if (AppBinaryPath.isEmpty()) {
            return Result(-1, emptyList(), emptyList(), Status.DAEMON_UNAVAILABLE)
        }
        val command = ArrayList<String>(args.size + 1).apply {
            add(AppBinaryPath)
            addAll(args)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        val (code, output, errors) = BoundedProcess.capture(process, 15_000)

        Result(code, output, errors, statusForCode(code))
    }.getOrElse { error ->
        if (error is InterruptedException) Thread.currentThread().interrupt()
        Result(
            -1,
            emptyList(),
            listOfNotNull(error.message),
            Status.TRANSPORT_FAILURE,
        )
    }

    internal fun statusForCode(code: Int): Status = when (code) {
        0 -> Status.SUCCESS
        10 -> Status.ACCESS_DENIED
        11 -> Status.DAEMON_UNAVAILABLE
        12 -> Status.PROTOCOL_MISMATCH
        13 -> Status.DATABASE_FAILURE
        else -> Status.COMMAND_FAILED
    }
}
