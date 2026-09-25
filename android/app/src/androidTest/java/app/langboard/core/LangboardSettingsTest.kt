package app.langboard.core

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.langboard.core.LangboardSettings.AfterInsert
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LangboardSettingsTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val prefs = context.getSharedPreferences("langboard_settings", Context.MODE_PRIVATE)
  private lateinit var saved: Map<String, *>

  // Tests run inside the installed app; keep the real user's settings intact.
  @Before fun backup() {
    saved = prefs.all.toMap()
    prefs.edit().clear().commit()
  }

  @After fun restore() {
    prefs.edit().clear().apply {
      for ((k, v) in saved) when (v) {
        is String -> putString(k, v)
        is Boolean -> putBoolean(k, v)
      }
    }.commit()
  }

  @Test fun defaults() {
    val s = LangboardSettings(context)
    assertEquals(AfterInsert.STAY, s.afterInsert)
    assertFalse(s.keyboardUsed)
  }

  @Test fun valuesPersistAcrossInstances() {
    LangboardSettings(context).apply {
      afterInsert = AfterInsert.RETURN
      keyboardUsed = true
    }
    val again = LangboardSettings(context)
    assertEquals(AfterInsert.RETURN, again.afterInsert)
    assertTrue(again.keyboardUsed)
  }

  @Test fun unknownStoredValueFallsBackToDefault() {
    prefs.edit().putString("after_insert", "SOMETHING_NEW").commit()
    assertEquals(AfterInsert.STAY, LangboardSettings(context).afterInsert)
  }
}
