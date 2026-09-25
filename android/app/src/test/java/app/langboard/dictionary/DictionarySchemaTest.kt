package app.langboard.dictionary

import app.langboard.cedict.HanziRef
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionarySchemaTest {
  @Test fun sensesRoundTrip() {
    val senses = listOf("person; people", "to go/come", "(slang) cool")
    assertEquals(senses, DictionarySchema.decodeSenses(DictionarySchema.encodeSenses(senses)))
    assertEquals(emptyList<String>(), DictionarySchema.decodeSenses(DictionarySchema.encodeSenses(emptyList())))
  }

  @Test fun refsRoundTripIncludingMissingPinyin() {
    val refs = listOf(HanziRef("個", "个", "ge4"), HanziRef("家具", "家具", null), HanziRef("前邊", "前边", "qian2 bian5"))
    assertEquals(refs, DictionarySchema.decodeRefs(DictionarySchema.encodeRefs(refs)))
    assertEquals(emptyList<HanziRef>(), DictionarySchema.decodeRefs(""))
  }
}
