package dev.paperplane.cli.ui

object TerminalUI {
    private val noColor = System.getenv("NO_COLOR") != null
    // ANSI codes
    private const val RESET = "\u001b[0m"
    private const val BOLD = "\u001b[1m"
    private const val DIM = "\u001b[2m"
    private const val RED = "\u001b[31m"
    private const val GREEN = "\u001b[32m"
    private const val YELLOW = "\u001b[33m"
    private const val BLUE = "\u001b[34m"
    private const val CYAN = "\u001b[36m"
    private const val WHITE = "\u001b[37m"
    private const val BRIGHT_WHITE = "\u001b[97m"

    private fun color(code: String, text: String): String =
        if (noColor) text else "$code$text$RESET"

    private fun bold(text: String) = color(BOLD, text)
    private fun dim(text: String) = color(DIM, text)
    private fun green(text: String) = color(GREEN, text)
    private fun red(text: String) = color(RED, text)
    private fun yellow(text: String) = color(YELLOW, text)
    private fun cyan(text: String) = color(CYAN, text)
    private fun brightWhite(text: String) = color(BRIGHT_WHITE, text)

    fun header(version: String) {
        println()
        println("  ${cyan("✈")}  ${bold(cyan("PaperPlane"))} ${dim("v$version")}")
        println()
    }

    fun success(message: String, duration: String? = null) {
        if (duration != null) {
            println("  ${green("✓")}  $message ${dim(duration)}")
        } else {
            println("  ${green("✓")}  $message")
        }
    }

    fun error(message: String, duration: String? = null) {
        if (duration != null) {
            println("  ${red("✗")}  $message ${dim(duration)}")
        } else {
            println("  ${red("✗")}  $message")
        }
    }

    fun info(label: String, value: String) {
        println("  ${dim("➜")}  ${bold(brightWhite(label))}  $value")
    }

    fun status(message: String) {
        println("  ${dim(message)}")
    }

    fun change(message: String) {
        println()
        println("  ${yellow("⟳")}  $message")
    }

    fun totalTime(duration: String) {
        println("  ${dim("total $duration")}")
    }

    fun buildError(filePath: String, line: Int?, errorText: String) {
        println()
        val location = if (line != null) "$filePath:$line" else filePath
        println("  ${brightWhite(location)}")
        for (errLine in errorText.lines()) {
            println("  ${red(errLine)}")
        }
    }

    fun testFailure(errorText: String) {
        val lines = errorText.lines().filter { it.isNotBlank() }
        for (errLine in lines) {
            println("       ${red(errLine)}")
        }
    }

    fun blank() {
        println()
    }

    fun fileCreated(path: String) {
        println("  ${green("✓")} $path")
    }

    // Test output (Vitest-style)
    fun testClass(name: String, passed: Boolean, duration: String) {
        val icon = if (passed) green("✓") else red("✗")
        println("  $icon  $name ${dim(duration)}")
    }

    fun testCase(name: String, passed: Boolean, duration: String) {
        val icon = if (passed) green("✓") else red("✗")
        println("     $icon $name ${dim(duration)}")
    }

    fun testSummary(passed: Int, failed: Int, total: Int, buildTime: String, testTime: String) {
        println()
        val passedText = green("$passed passed")
        val failedText = if (failed > 0) "  ${red("$failed failed")}" else ""
        println("  Tests   $passedText$failedText  ${dim("($total)")}")
        println("  Time    ${buildTime} ${dim("(build ${buildTime}, tests ${testTime})")}")
    }

    /**
     * Runs [block] while showing a spinner with [message].
     * The spinner line is replaced by whatever the caller prints next.
     */
    fun <T> spin(message: String, block: () -> T): T {
        val frames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread({
            var i = 0
            while (!done.get()) {
                val frame = if (noColor) frames[i] else "$CYAN${frames[i]}$RESET"
                print("\r  $frame  ${dim(message)}")
                System.out.flush()
                i = (i + 1) % frames.size
                Thread.sleep(80)
            }
            // Clear the spinner line
            print("\r\u001b[2K")
            System.out.flush()
        }, "spinner")
        thread.isDaemon = true
        thread.start()
        return try {
            block()
        } finally {
            done.set(true)
            thread.join(200)
        }
    }

    private fun stripAnsi(text: String): String =
        text.replace(Regex("\u001b\\[[0-9;]*m"), "")
}
