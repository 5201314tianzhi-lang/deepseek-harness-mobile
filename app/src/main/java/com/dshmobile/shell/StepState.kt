package com.dshmobile.shell

/** Render state of a boot-guide step card. */
enum class StepState { PENDING, ACTIVE, DONE }

/**
 * Maps the guide's (done, active) counters onto per-step states: steps
 * before [done] are done, the step at [active] is in progress, the rest
 * are pending. Pure logic — UI rendering lives in GuideWizard.renderSteps.
 */
class StepModel(
  private val done: Int,
  private val active: Int,
) {
  fun state(index: Int): StepState =
    when {
      index < done -> StepState.DONE
      index == active -> StepState.ACTIVE
      else -> StepState.PENDING
    }
}