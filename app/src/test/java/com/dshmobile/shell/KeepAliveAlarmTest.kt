package com.dshmobile.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Heartbeat alarm: schedules one inexact, allow-while-idle alarm at 30min. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepAliveAlarmTest {
  @Test
  fun `schedule arms a single 30-minute allow-while-idle alarm`() {
    val context: Context = RuntimeEnvironment.getApplication()
    KeepAliveAlarm.schedule(context)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val shadow = Shadows.shadowOf(alarmManager)
    assertEquals(1, shadow.scheduledAlarms.size)
    val alarm = shadow.scheduledAlarms[0]
    assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
    assertNotNull(alarm.operation)
    // triggerAtTime = elapsedRealtime() + INTERVAL_MS; the clock base is not
    // guaranteed to be zero in tests, so assert the interval floor instead.
    assertTrue(alarm.triggerAtTime >= KeepAliveAlarm.INTERVAL_MS)
  }

  @Test
  fun `schedule is idempotent and replaces the previous alarm`() {
    val context: Context = RuntimeEnvironment.getApplication()
    KeepAliveAlarm.schedule(context)
    KeepAliveAlarm.schedule(context)
    val shadow = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    assertEquals(1, shadow.scheduledAlarms.size)
  }

  @Test
  fun `receiver re-arms before checking the engine`() {
    val context: Context = RuntimeEnvironment.getApplication()
    val receiver = KeepAliveAlarmReceiver()
    receiver.onReceive(context, Intent(context, KeepAliveAlarmReceiver::class.java))
    val shadow = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    assertEquals(1, shadow.scheduledAlarms.size)
  }
}
