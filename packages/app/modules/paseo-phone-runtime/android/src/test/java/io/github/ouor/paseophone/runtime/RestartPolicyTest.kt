package io.github.ouor.paseophone.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {
  @Test
  fun backoffDoublesAndCapsAtSixtySeconds() {
    assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), (1..5).map { ProcessSupervisor.restartDelayMs(it) })
    assertEquals(60_000L, ProcessSupervisor.restartDelayMs(7))
    assertEquals(60_000L, ProcessSupervisor.restartDelayMs(100))
  }

  @Test
  fun fiveFailuresWithinFiveMinutesHitTheLimit() {
    val failures = ArrayDeque<Long>()
    (0 until 4).forEach { assertFalse(ProcessSupervisor.recordFailure(failures, it * 60_000L)) }
    assertTrue(ProcessSupervisor.recordFailure(failures, 4 * 60_000L))
  }

  @Test
  fun failuresOlderThanFiveMinutesAreForgotten() {
    val failures = ArrayDeque<Long>()
    (0 until 4).forEach { ProcessSupervisor.recordFailure(failures, it * 60_000L) }
    // Just past 5 min: the failure at 0 min leaves the window, so only 4 remain.
    assertFalse(ProcessSupervisor.recordFailure(failures, 5 * 60_000L + 1))
    assertEquals(4, failures.size)
  }
}
