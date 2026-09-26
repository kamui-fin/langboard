package app.langboard.core

import org.json.JSONArray
import org.json.JSONObject

/** How much slang the user is happy with. lb1 has no "high" (handoff §10). */
enum class Slang(val lb1: String, val label: String) { Low("low", "Little"), Medium("medium", "Some") }

enum class Verbosity(val lb1: String, val label: String) {
  Concise("concise", "Short"), Balanced("balanced", "Balanced"), Expressive("expressive", "Fuller"),
}

enum class Directness(val lb1: String, val label: String) {
  Soft("soft", "Soft"), Balanced("balanced", "Balanced"), Direct("direct", "Direct"),
}

/**
 * One thing about how the user wants to sound, as a line the model can read (lb1 `profile`).
 * [Kind.Prefer] and [Kind.Avoid] name Chinese words, so the app can also act on them itself
 * ([Personalizer]); a [Kind.Note] is only ever read by the model.
 */
data class StyleRule(val kind: Kind, val text: String, val over: String? = null, val learned: Boolean = false) {
  enum class Kind { Prefer, Avoid, Note }

  val line: String
    get() = when (kind) {
      Kind.Prefer -> if (over != null) "prefer $text over $over" else "prefer $text"
      Kind.Avoid -> "avoid $text"
      Kind.Note -> text
    }

  /** Two rules that say the same thing, whoever added them. */
  fun sameAs(o: StyleRule) = kind == o.kind && text == o.text && over == o.over
}

/**
 * My Style: what the user told Langboard about how they want to sound, and the learned lines they
 * accepted. The register lives in [LangboardSettings.defaultRegister], which predates this.
 * Linguistic preferences only; nothing here describes who the user is.
 */
data class MyStyle(
  val slang: Slang = Slang.Low,
  val verbosity: Verbosity = Verbosity.Balanced,
  val directness: Directness = Directness.Balanced,
  /** In the user's own words. Never sent to the model: it's compiled into the fields here. */
  val description: String = "",
  /** The description as it was when last compiled; differs from [description] after an edit. */
  val compiledFrom: String? = null,
  /**
   * The description's style statements, kept in the user's words but made safe ([StyleGuide]): what
   * the model reads first in `profile`. The specifics live here ("some slang but don't overdo it");
   * the controls and word rules are only what the app itself can act on.
   */
  val guide: List<String> = emptyList(),
  val rules: List<StyleRule> = emptyList(),
  /** Messages the user pasted as sounding like them. */
  val examples: List<String> = emptyList(),
  /** Learned suggestions the user said no to, by [StyleRule.line] (or [VERBOSITY_KEY]), so they aren't offered again. */
  val dismissed: Set<String> = emptySet(),
) {
  val told: List<StyleRule> get() = rules.filter { !it.learned }
  val learned: List<StyleRule> get() = rules.filter { it.learned }

  /**
   * lb1 `personalization.profile`: the guide in the user's words, then word rules the guide doesn't
   * already mention, then what they accepted from Learned.
   */
  val profile: List<String>
    get() {
      val mentioned = { r: StyleRule -> guide.any { g -> r.text in g && (r.over == null || r.over in g) } }
      return (guide.take(StyleGuide.MAX_LINES) + (told + learned).filterNot(mentioned).map { it.line }).distinct().take(MAX_PROFILE)
    }

  val isDefault: Boolean
    get() = slang == Slang.Low && verbosity == Verbosity.Balanced && directness == Directness.Balanced && guide.isEmpty() && rules.isEmpty() && examples.isEmpty()

  /** Adds [rule] unless an equal one is there; a stated rule replaces a learned one, never the reverse. */
  fun with(rule: StyleRule): MyStyle {
    val same = rules.firstOrNull { it.sameAs(rule) }
    if (same != null && (rule.learned || !same.learned)) return this
    // A rule and its opposite can't both stand: the newest wins.
    val kept = rules.filterNot { it.sameAs(rule) || contradicts(it, rule) }
    return copy(rules = kept + rule)
  }

  fun without(rule: StyleRule): MyStyle = copy(rules = rules.filterNot { it == rule })

  /** What [c] says, over what's here: controls it named, its rules in place of the old stated ones. Learned rules stay. */
  fun compiled(c: CompiledStyle, from: String): MyStyle {
    var s = copy(
      slang = c.slang ?: slang,
      verbosity = c.verbosity ?: verbosity,
      directness = c.directness ?: directness,
      description = from,
      compiledFrom = from,
      guide = c.guide,
      rules = learned,
      examples = c.examples,
    )
    for (r in c.rules) s = s.with(r)
    return s
  }

  fun toJson(): String = JSONObject().apply {
    put("slang", slang.name)
    put("verbosity", verbosity.name)
    put("directness", directness.name)
    put("description", description)
    compiledFrom?.let { put("compiled_from", it) }
    put("guide", JSONArray(guide))
    put("rules", JSONArray(rules.map { r ->
      JSONObject().put("kind", r.kind.name).put("text", r.text).put("learned", r.learned).apply { r.over?.let { put("over", it) } }
    }))
    put("examples", JSONArray(examples))
    put("dismissed", JSONArray(dismissed.toList()))
  }.toString()

  companion object {
    const val MAX_PROFILE = 6
    const val MAX_EXAMPLES = 3
    /** [dismissed] key for the learned "shorter" suggestion. */
    const val VERBOSITY_KEY = "verbosity:concise"

    /** Prefer 挺 against avoid 挺, or prefer 挺 over 很 against prefer 很 over 挺. */
    private fun contradicts(a: StyleRule, b: StyleRule): Boolean = when {
      a.kind == StyleRule.Kind.Note || b.kind == StyleRule.Kind.Note -> false
      a.kind != b.kind -> a.text == b.text
      a.kind == StyleRule.Kind.Prefer -> a.text == b.over && a.over == b.text
      else -> false
    }

    fun fromJson(json: String?): MyStyle {
      if (json.isNullOrBlank()) return MyStyle()
      return runCatching {
        val o = JSONObject(json)
        fun <E : Enum<E>> enum(key: String, values: Array<E>, default: E) =
          values.firstOrNull { it.name == o.optString(key) } ?: default
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        MyStyle(
          slang = enum("slang", Slang.entries.toTypedArray(), Slang.Low),
          verbosity = enum("verbosity", Verbosity.entries.toTypedArray(), Verbosity.Balanced),
          directness = enum("directness", Directness.entries.toTypedArray(), Directness.Balanced),
          description = o.optString("description"),
          compiledFrom = if (o.has("compiled_from")) o.getString("compiled_from") else null,
          guide = strings("guide"),
          rules = o.optJSONArray("rules")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
              val r = a.getJSONObject(i)
              val kind = StyleRule.Kind.entries.firstOrNull { it.name == r.optString("kind") } ?: return@mapNotNull null
              StyleRule(kind, r.getString("text"), if (r.has("over")) r.getString("over") else null, r.optBoolean("learned"))
            }
          }.orEmpty(),
          examples = strings("examples"),
          dismissed = strings("dismissed").toSet(),
        )
      }.getOrElse { MyStyle() }
    }
  }
}

/**
 * What one request carries of the user's style: the controls, profile lines and a few of their own
 * sentences. The register is separate, because the chat on screen can override it.
 */
data class PersonalStyle(
  val slang: Slang = Slang.Low,
  val verbosity: Verbosity = Verbosity.Balanced,
  val directness: Directness = Directness.Balanced,
  val rules: List<StyleRule> = emptyList(),
  val profile: List<String> = emptyList(),
  val examples: List<String> = emptyList(),
) {
  companion object {
    val NONE = PersonalStyle()

    fun of(s: MyStyle, examples: List<String> = emptyList()) = PersonalStyle(
      s.slang, s.verbosity, s.directness, s.rules, s.profile, examples.take(MyStyle.MAX_EXAMPLES),
    )
  }
}
