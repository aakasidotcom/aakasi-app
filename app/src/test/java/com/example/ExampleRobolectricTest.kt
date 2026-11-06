package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AAKASI", appName)
  }

  @Test
  fun `check photo picker availability`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val available = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
    org.junit.Assert.assertTrue(available)
  }

  @Test
  fun `check touch sound setting reading`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    android.provider.Settings.System.putInt(
        context.contentResolver,
        android.provider.Settings.System.SOUND_EFFECTS_ENABLED,
        1
    )
    val enabled = android.provider.Settings.System.getInt(
        context.contentResolver,
        android.provider.Settings.System.SOUND_EFFECTS_ENABLED,
        0
    ) != 0
    org.junit.Assert.assertTrue(enabled)

    android.provider.Settings.System.putInt(
        context.contentResolver,
        android.provider.Settings.System.SOUND_EFFECTS_ENABLED,
        0
    )
    val disabled = android.provider.Settings.System.getInt(
        context.contentResolver,
        android.provider.Settings.System.SOUND_EFFECTS_ENABLED,
        0
    ) != 0
    org.junit.Assert.assertFalse(disabled)
  }
}
