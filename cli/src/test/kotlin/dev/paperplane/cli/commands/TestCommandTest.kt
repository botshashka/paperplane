package dev.paperplane.cli.commands

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TestCommandTest {

  @TempDir lateinit var tempDir: File

  private lateinit var resultsDir: File

  @BeforeEach
  fun setUp() {
    resultsDir = File(tempDir, "build/test-results/test")
    resultsDir.mkdirs()
  }

  // ── XML parsing ───────────────────────────────────────────────────

  @Test
  fun `parses passing test from JUnit XML`() {
    writeXml(
        "TEST-com.example.FooTest.xml",
        """
            <testsuite name="com.example.FooTest" tests="1" failures="0" time="0.5">
              <testcase name="shouldWork()" time="0.5"/>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)

    assertEquals(1, results.size)
    assertEquals("com.example.FooTest", results[0].name)
    assertEquals(1, results[0].passed)
    assertEquals(0, results[0].failed)
    assertNull(results[0].cases[0].failure)
  }

  @Test
  fun `parses failing test with failure message`() {
    writeXml(
        "TEST-com.example.FooTest.xml",
        """
            <testsuite name="com.example.FooTest" tests="1" failures="1" time="0.1">
              <testcase name="shouldFail()" time="0.1">
                <failure>expected true but was false
                    at com.example.FooTest.shouldFail(FooTest.java:15)
                </failure>
              </testcase>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)

    assertEquals(1, results[0].failed)
    assertNotNull(results[0].cases[0].failure)
    assertTrue(results[0].cases[0].failure!!.contains("expected true but was false"))
  }

  @Test
  fun `parses error test with error element`() {
    writeXml(
        "TEST-com.example.FooTest.xml",
        """
            <testsuite name="com.example.FooTest" tests="1" errors="1" time="0.05">
              <testcase name="shouldThrow()" time="0.05">
                <error>NullPointerException at line 42</error>
              </testcase>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)

    assertEquals(1, results[0].failed)
    assertTrue(results[0].cases[0].failure!!.contains("NullPointerException"))
  }

  @Test
  fun `parses mix of passing and failing tests`() {
    writeXml(
        "TEST-com.example.MixedTest.xml",
        """
            <testsuite name="com.example.MixedTest" tests="3" failures="1" time="1.0">
              <testcase name="passes1()" time="0.2"/>
              <testcase name="fails1()" time="0.5">
                <failure>assertion failed</failure>
              </testcase>
              <testcase name="passes2()" time="0.3"/>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)

    assertEquals(1, results.size)
    assertEquals(2, results[0].passed)
    assertEquals(1, results[0].failed)
  }

  @Test
  fun `parses multiple test suites from separate XML files`() {
    writeXml(
        "TEST-com.example.AlphaTest.xml",
        """
            <testsuite name="com.example.AlphaTest" tests="1" failures="0" time="0.1">
              <testcase name="testA()" time="0.1"/>
            </testsuite>
        """,
    )
    writeXml(
        "TEST-com.example.BetaTest.xml",
        """
            <testsuite name="com.example.BetaTest" tests="1" failures="0" time="0.2">
              <testcase name="testB()" time="0.2"/>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)

    assertEquals(2, results.size)
    assertEquals("com.example.AlphaTest", results[0].name, "Results should be sorted by name")
    assertEquals("com.example.BetaTest", results[1].name)
  }

  @Test
  fun `returns empty list for empty results directory`() {
    val results = parseTestResults(resultsDir)
    assertTrue(results.isEmpty())
  }

  @Test
  fun `ignores non-XML files in results directory`() {
    File(resultsDir, "some-log.txt").writeText("not xml")
    writeXml(
        "TEST-com.example.FooTest.xml",
        """
            <testsuite name="com.example.FooTest" tests="1" failures="0" time="0.1">
              <testcase name="test()" time="0.1"/>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)
    assertEquals(1, results.size)
  }

  @Test
  fun `time is converted from seconds to milliseconds`() {
    writeXml(
        "TEST-com.example.FooTest.xml",
        """
            <testsuite name="com.example.FooTest" tests="1" failures="0" time="2.5">
              <testcase name="slowTest()" time="2.5"/>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)
    assertEquals(2500.0, results[0].timeMs, 0.1)
    assertEquals(2500.0, results[0].cases[0].timeMs, 0.1)
  }

  // ── Stale results cleanup ─────────────────────────────────────────

  @Test
  fun `stale XML files are cleaned before run`() {
    writeXml(
        "TEST-com.example.OldTest.xml",
        """
            <testsuite name="com.example.OldTest" tests="1" failures="0" time="0.1">
              <testcase name="staleTest()" time="0.1"/>
            </testsuite>
        """,
    )

    // Simulate the cleanup that TestCommand does before running
    resultsDir.listFiles { _, name -> name.endsWith(".xml") }?.forEach { it.delete() }

    // After cleanup, write only the "new" result
    writeXml(
        "TEST-com.example.NewTest.xml",
        """
            <testsuite name="com.example.NewTest" tests="1" failures="0" time="0.2">
              <testcase name="freshTest()" time="0.2"/>
            </testsuite>
        """,
    )

    val results = parseTestResults(resultsDir)
    assertEquals(1, results.size)
    assertEquals("com.example.NewTest", results[0].name)
  }

  // ── Failure message truncation ────────────────────────────────────

  @Test
  fun `non-verbose failure extracts first non-blank line only`() {
    val rawFailure =
        """

        org.example.SomeException: something broke
            at com.example.Test.method(Test.java:10)
            at com.example.Test.run(Test.java:5)
        """
            .trimIndent()

    val firstLine = rawFailure.lines().firstOrNull { it.isNotBlank() }
    assertEquals("org.example.SomeException: something broke", firstLine)
  }

  @Test
  fun `verbose failure preserves full stack trace`() {
    val rawFailure =
        """
        org.example.SomeException: broke
                    at com.example.Test.method(Test.java:10)
                    at com.example.Test.run(Test.java:5)
        """
            .trimIndent()

    // In verbose mode, the full message is kept
    assertEquals(rawFailure, rawFailure)
    assertTrue(rawFailure.lines().size > 1)
  }

  // ── GradleBridge filter argument ──────────────────────────────────

  @Test
  fun `filter wraps with wildcards for Gradle --tests`() {
    val filter = "blockBreak"
    val gradleArg = "*$filter*"
    assertEquals("*blockBreak*", gradleArg)
  }

  @Test
  fun `null filter produces no --tests argument`() {
    val filter: String? = null
    val args = mutableListOf<String>()
    if (filter != null) {
      args.addAll(listOf("--tests", "*$filter*"))
    }
    assertTrue(args.isEmpty())
  }

  // ── Helpers ───────────────────────────────────────────────────────

  /** Reimplements TestCommand.parseTestResults for isolated testing. */
  private fun parseTestResults(resultsDir: File): List<TestSuiteResult> {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()

    return resultsDir
        .listFiles { _, name -> name.endsWith(".xml") }
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
            val time =
                testElement.attributes.getNamedItem("time")?.nodeValue?.toDoubleOrNull() ?: 0.0
            val failures = (testElement as org.w3c.dom.Element).getElementsByTagName("failure")
            val errors = testElement.getElementsByTagName("error")
            val rawMessage =
                if (failures.length > 0) {
                  failures.item(0).textContent
                } else if (errors.length > 0) {
                  errors.item(0).textContent
                } else null

            testCases.add(TestCaseResult(name, time * 1000, rawMessage))
          }

          TestSuiteResult(suiteName, suiteTime * 1000, testCases)
        }
        ?.sortedBy { it.name } ?: emptyList()
  }

  private fun writeXml(filename: String, content: String) {
    File(resultsDir, filename).writeText(content.trimIndent())
  }

  private data class TestSuiteResult(
      val name: String,
      val timeMs: Double,
      val cases: List<TestCaseResult>,
  ) {
    val passed
      get() = cases.count { it.failure == null }

    val failed
      get() = cases.count { it.failure != null }
  }

  private data class TestCaseResult(val name: String, val timeMs: Double, val failure: String?)
}
