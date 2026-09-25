package app.langboard.ime

import android.text.InputType.TYPE_CLASS_DATETIME
import android.text.InputType.TYPE_CLASS_NUMBER
import android.text.InputType.TYPE_CLASS_PHONE
import android.text.InputType.TYPE_CLASS_TEXT
import android.text.InputType.TYPE_NULL
import android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
import android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
import android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
import android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
import android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
import android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
import android.text.InputType.TYPE_TEXT_VARIATION_URI
import android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
import android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
import app.langboard.ime.FieldPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldPolicyTest {
  private fun kind(type: Int) = FieldPolicy.classify(type)

  @Test fun ordinaryTextFields() {
    assertEquals(Kind.Text, kind(TYPE_CLASS_TEXT))
    assertEquals(Kind.Text, kind(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_SHORT_MESSAGE or TYPE_TEXT_FLAG_MULTI_LINE))
    assertEquals(Kind.Text, kind(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_SENTENCES))
    assertEquals(Kind.Text, kind(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI))
    assertEquals(Kind.Text, kind(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
  }

  @Test fun passwordsAreSensitive() {
    assertEquals(Kind.Sensitive, kind(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD))
    assertEquals(Kind.Sensitive, kind(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
    assertEquals(Kind.Sensitive, kind(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD))
    assertEquals(Kind.Sensitive, kind(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD))
  }

  @Test fun nonTextKeepsSystemBehavior() {
    assertEquals(Kind.NonText, kind(TYPE_CLASS_NUMBER))
    assertEquals(Kind.NonText, kind(TYPE_CLASS_PHONE))
    assertEquals(Kind.NonText, kind(TYPE_CLASS_DATETIME))
    assertEquals(Kind.NonText, kind(TYPE_NULL))
  }
}
