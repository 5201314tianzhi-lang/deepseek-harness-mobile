package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class StepModelTest {
  @Test
  fun allPendingAtStart() {
    val m = StepModel(0, 0)
    assertEquals(StepState.PENDING, m.state(0))
    assertEquals(StepState.PENDING, m.state(1))
    assertEquals(StepState.PENDING, m.state(2))
  }

  @Test
  fun firstDoneSecondActive() {
    val m = StepModel(1, 1)
    assertEquals(StepState.DONE, m.state(0))
    assertEquals(StepState.ACTIVE, m.state(1))
    assertEquals(StepState.PENDING, m.state(2))
  }

  @Test
  fun allDone() {
    val m = StepModel(3, 3)
    assertEquals(StepState.DONE, m.state(0))
    assertEquals(StepState.DONE, m.state(1))
    assertEquals(StepState.DONE, m.state(2))
  }

  @Test
  fun activeCannotPrecedeDone() {
    val m = StepModel(2, 2)
    assertEquals(StepState.DONE, m.state(1))
    assertEquals(StepState.ACTIVE, m.state(2))
  }
}