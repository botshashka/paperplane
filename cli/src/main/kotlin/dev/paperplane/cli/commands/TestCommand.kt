package dev.paperplane.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.paperplane.cli.gradle.GradleBridge
import dev.paperplane.cli.ui.TerminalUI
import dev.paperplane.cli.watcher.FileWatcher
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TestCommand : CliktCommand(name = "test") {
    private val watch by option("--watch", "-w", help = "Re-run tests on file changes").flag()
    private val verbose by option("--verbose", "-v", help = "Show full error messages").flag()
    private val filter by option("--filter", "-f", help = "Filter tests by name (e.g. 'blockBreak')")
    private val projectDir = File(System.getProperty("user.dir"))

    override fun run() {
        val version = javaClass.`package`?.implementationVersion ?: "0.1.0"
        TerminalUI.header(version)

        if (watch) {
            TerminalUI.beginBlock()
            runTestsInBlock()
            TerminalUI.endBlock()

            TerminalUI.beginBlock(TerminalUI.BlockType.TRANSIENT)
            TerminalUI.status("Watching for changes...")

            val watcher = FileWatcher(File(projectDir, "src")) {
                TerminalUI.discardBlock()
                TerminalUI.beginBlock()
                TerminalUI.change("Re-running tests...")
                runTestsInBlock()
                TerminalUI.endBlock()

                TerminalUI.beginBlock(TerminalUI.BlockType.TRANSIENT)
                TerminalUI.status("Watching for changes...")
            }
            watcher.start()
            try {
                while (true) Thread.sleep(1000)
            } catch (_: InterruptedException) {
                watcher.stop()
            }
        } else {
            TerminalUI.beginBlock()
            runTestsInBlock()
            TerminalUI.endBlock()
        }
    }

    private fun runTestsInBlock() {
        val gradle = GradleBridge(projectDir)

        val buildStart = System.currentTimeMillis()
        // Clean stale results so filtered runs don't show old tests
        val resultsDir = File(projectDir, "build/test-results/test")
        if (resultsDir.exists()) {
            resultsDir.listFiles { _, name -> name.endsWith(".xml") }?.forEach { it.delete() }
        }

        val spinMessage = if (filter != null) "Running tests matching \"$filter\"..." else "Running tests..."
        val success = TerminalUI.spin(spinMessage) {
            gradle.test(quiet = true, filter = filter)
        }
        val totalDuration = System.currentTimeMillis() - buildStart

        // Parse JUnit XML results
        if (resultsDir.exists()) {
            val results = parseTestResults(resultsDir)
            displayResults(results)

            val passed = results.sumOf { it.passed }
            val failed = results.sumOf { it.failed }
            val total = passed + failed
            val totalTime = formatDuration(totalDuration)
            val testTime = formatDuration(results.sumOf { it.timeMs }.toLong())

            TerminalUI.testSummary(passed, failed, total, totalTime, testTime)
            if (failed > 0 && !verbose) {
                TerminalUI.status("Run with --verbose for full stack traces")
            }
        } else if (!success) {
            TerminalUI.error("Tests failed (no results found)", formatDuration(totalDuration))
        }

        gradle.close()
    }

    private fun parseTestResults(resultsDir: File): List<TestSuiteResult> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        return resultsDir.listFiles { _, name -> name.endsWith(".xml") }
            ?.map { file ->
                val doc = builder.parse(file)
                val suiteElement = doc.documentElement
                val suiteName = suiteElement.getAttribute("name")
                val suiteTime = suiteElement.getAttribute("time").toDoubleOrNull() ?: 0.0

                val testCases = mutableListOf<TestCaseResult>()
                val testElements = suiteElement.getElementsByTagName("testcase")
                for (i in 0 until testElements.length) {
                    val testElement = testElements.item(i)
                    val name = testElement.attributes.getNamedItem("name")?.nodeValue ?: "unknown"
                    val time = testElement.attributes.getNamedItem("time")?.nodeValue?.toDoubleOrNull() ?: 0.0
                    val failures = (testElement as org.w3c.dom.Element).getElementsByTagName("failure")
                    val errors = testElement.getElementsByTagName("error")
                    val rawMessage = if (failures.length > 0) {
                        failures.item(0).textContent
                    } else if (errors.length > 0) {
                        errors.item(0).textContent
                    } else null
                    val failureMessage = if (verbose) rawMessage else rawMessage?.lines()?.firstOrNull { it.isNotBlank() }

                    testCases.add(TestCaseResult(name, time * 1000, failureMessage))
                }

                TestSuiteResult(suiteName, suiteTime * 1000, testCases)
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun displayResults(results: List<TestSuiteResult>) {
        for (suite in results) {
            val allPassed = suite.cases.all { it.failure == null }
            TerminalUI.testClass(suite.name, allPassed, formatDuration(suite.timeMs.toLong()))
            for (case in suite.cases) {
                val passed = case.failure == null
                TerminalUI.testCase(case.name, passed, formatDuration(case.timeMs.toLong()))
                if (case.failure != null) {
                    TerminalUI.testFailure(case.failure)
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        return if (ms >= 1000) "%.1fs".format(ms / 1000.0) else "${ms}ms"
    }

    private data class TestSuiteResult(
        val name: String,
        val timeMs: Double,
        val cases: List<TestCaseResult>
    ) {
        val passed get() = cases.count { it.failure == null }
        val failed get() = cases.count { it.failure != null }
    }

    private data class TestCaseResult(
        val name: String,
        val timeMs: Double,
        val failure: String?
    )
}
