package com.didi.dimina

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the multi-task launch result and Recents card rules to their source paths. */
class DiminaMultiTaskContractTest {
    private fun readSource(relativePath: String): String {
        val candidates = listOf(relativePath, "dimina/$relativePath").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("source not found; tried ${candidates.map(File::getPath)}")
        return found.readText()
    }

    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) throw AssertionError("declaration not found: $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        throw AssertionError("unbalanced declaration: $signature")
    }

    private val activity by lazy {
        readSource("src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt")
    }

    @Test
    fun `shared-task mode never renames the host's Recents card`() {
        val body = bodyOf(activity, "private fun applyTaskAppearance()")
        assertTrue(body.trimStart().startsWith("if (!Dimina.getInstance().isMultiTaskEnabled()) return"))
        assertTrue(body.contains("setTaskDescription("))
    }

    @Test
    fun `bringing a retained mini program forward reports RESUMED, a new launch STARTED`() {
        val body = bodyOf(
            activity,
            "internal fun openMiniProgram(context: Activity, program: MiniProgram): Dimina.LaunchResult",
        )
        val resume = body.indexOf("if (resumeRetainedMiniProgram(context, program.appId)) {")
        assertTrue(resume >= 0)
        val resumeBlock = bodyOf(body.substring(resume), "if (resumeRetainedMiniProgram")
        assertTrue(resumeBlock.contains("return Dimina.LaunchResult.RESUMED"))
        // Everything after the resume branch starts a page stack.
        val afterResume = body.substring(body.indexOf('{', resume) + 1 + resumeBlock.length + 1)
        assertTrue(afterResume.contains("return Dimina.LaunchResult.STARTED"))
        assertTrue(!afterResume.contains("LaunchResult.RESUMED"))
        assertTrue(!body.substring(0, resume).contains("LaunchResult.RESUMED"))
    }

    @Test
    fun `leaving a mini program returns to the task it was opened from`() {
        val hide = bodyOf(activity, "fun hideMiniProgram()")
        assertTrue(hide.indexOf("moveTaskToBack(true)") in 0 until hide.indexOf("returnToOpenerTask()"))
        // Closing in multi-task mode: back to the opener first, only while visible, then finish.
        val close = bodyOf(activity, "private fun closeMiniProgram()")
        val guard = close.indexOf("if (Dimina.getInstance().isMultiTaskEnabled() &&")
        val returning = close.indexOf("returnToOpenerTask()")
        assertTrue(guard >= 0 && close.indexOf("Lifecycle.State.STARTED") in guard until returning)
        assertTrue(returning < close.indexOf("activityRegistry.closeAll("))
        val back = bodyOf(activity, "private fun returnToOpenerTask()")
        assertTrue(back.contains(".takeIf { it != taskId } ?: return"))
        assertTrue(back.contains("catch (error: RuntimeException)"))
    }

    @Test
    fun `the host facade exposes the launch result`() {
        val facade = readSource("src/main/kotlin/com/didi/dimina/Dimina.kt")
        val launch = facade.substring(facade.indexOf("fun launchMiniProgram(context: Activity, miniProgram: MiniProgram): LaunchResult"))
        assertTrue(launch.lineSequence().take(2).joinToString("\n").contains("MiniApp.getInstance().openApp(context, miniProgram)"))
        assertTrue(bodyOf(facade, "fun startMiniProgram(context: Activity, miniProgram: MiniProgram)").contains("launchMiniProgram(context, miniProgram)"))
    }
}
