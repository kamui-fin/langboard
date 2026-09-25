package app.langboard.core

import app.langboard.core.AssistPolicy.FieldFacts
import app.langboard.core.AssistPolicy.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistPolicyTest {
  private fun facts(
    pkg: String? = "com.whatsapp",
    password: Boolean = false,
    field: List<String> = listOf("Message"),
    screen: List<String> = listOf("Alice", "online"),
  ) = FieldFacts(pkg, password, field, screen)

  private fun skip(f: FieldFacts) = AssistPolicy.skipReason(f, ownPackage = "app.langboard")

  @Test fun chatAppIsAllowed() = assertNull(skip(facts()))
  @Test fun wechatIsAllowed() = assertNull(skip(facts(pkg = "com.tencent.mm")))
  @Test fun ownAppIsSkipped() = assertEquals(Skip.OwnApp, skip(facts(pkg = "app.langboard")))
  @Test fun unknownPackageIsSkipped() = assertEquals(Skip.DeniedApp, skip(facts(pkg = null)))
  @Test fun passwordFieldIsSkipped() = assertEquals(Skip.Password, skip(facts(password = true)))

  @Test fun passwordManagersAndWalletsAreDenied() {
    listOf("com.x8bit.bitwarden", "com.onepassword.android", "com.paypal.android.p2pmobile", "com.eg.android.AlipayGphone",
      "com.google.android.apps.walletnfcrel", "com.android.settings").forEach {
      assertEquals(it, Skip.DeniedApp, skip(facts(pkg = it)))
    }
  }

  @Test fun bankingAppsAreDeniedByName() {
    listOf("com.chase.sig.android.bank", "uk.co.hsbc.hsbcukmobilebanking", "com.icbc.mobilebank", "com.cmbchina.creditcard")
      .forEach { assertTrue(it, AssistPolicy.isDeniedPackage(it)) }
    assertFalse(AssistPolicy.isDeniedPackage("com.discord"))
    assertFalse(AssistPolicy.isDeniedPackage("org.telegram.messenger"))
  }

  @Test fun signInAndPaymentScreensAreSkipped() {
    assertEquals(Skip.SensitiveScreen, skip(facts(screen = listOf("Sign in", "Email"))))
    assertEquals(Skip.SensitiveScreen, skip(facts(field = listOf("Card number"))))
    assertEquals(Skip.SensitiveScreen, skip(facts(screen = listOf("请输入验证码"))))
    assertEquals(Skip.SensitiveScreen, skip(facts(field = listOf("com.shop:id/cvv_input"))))
    assertEquals(Skip.SensitiveScreen, skip(facts(field = listOf("otpField"))))
  }

  @Test fun longMessagesMentioningPasswordsDontCount() {
    assertNull(skip(facts(screen = listOf("I forgot my password again lol, can you send it to me?"))))
  }

  @Test fun wordsInsideOtherWordsDontCount() {
    assertFalse(AssistPolicy.isSensitiveLabel("Hotpot tonight?"))
    assertFalse(AssistPolicy.isSensitiveLabel("Catalogin"))
  }
}
