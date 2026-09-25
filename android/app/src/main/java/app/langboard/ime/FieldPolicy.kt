package app.langboard.ime

import android.text.InputType

/** Which editors Langboard works in. Pure function of `EditorInfo.inputType`. */
object FieldPolicy {
  enum class Kind { Text, Sensitive, NonText }

  fun classify(inputType: Int): Kind {
    val cls = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when {
      cls == InputType.TYPE_CLASS_TEXT && variation in TEXT_PASSWORDS -> Kind.Sensitive
      cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> Kind.Sensitive
      cls == InputType.TYPE_CLASS_TEXT -> Kind.Text
      else -> Kind.NonText // numbers, phone, date, and TYPE_NULL editors keep system behavior
    }
  }

  private val TEXT_PASSWORDS = setOf(
    InputType.TYPE_TEXT_VARIATION_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
  )
}
