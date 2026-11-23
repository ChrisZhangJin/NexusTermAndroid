package com.nexusterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPromptTest {
    @Test fun recognisesOneTimeCodePrompts() {
        listOf("[OTP Code]: ", "Verification code:", "Passcode: ", "Token:", "请输入验证码：", "One-time password:")
            .forEach { assertTrue(it, isOneTimeCode(it)) }
    }

    @Test fun plainPasswordIsNotOneTimeCode() {
        listOf("Password: ", "user@host's password:", "密码：", "Enter passphrase for key")
            .forEach { assertFalse(it, isOneTimeCode(it)) }
    }

    @Test fun joinsFieldsWithNewline() {
        assertEquals("secret\n123456", joinAuthAnswers(listOf("Password:", "OTP:"), listOf("secret", "123456")))
    }

    @Test fun stripsWhitespaceFromCodesOnly() {
        // 从短信里复制来的「123 456」去掉空格；密码里的空格是密码的一部分，保留。
        assertEquals("my pass\n123456", joinAuthAnswers(listOf("Password:", "OTP:"), listOf("my pass", " 123 456\n")))
    }

    @Test fun newlinesNeverLeakIntoAField() {
        assertEquals("ab", joinAuthAnswers(listOf("Answer:"), listOf("a\r\nb")))
    }

    @Test fun confirmedEmptyAnswerIsNotCancel() {
        // 空串在协议里 = 取消；用户点了「确定」就不能变成取消。
        assertEquals(" ", joinAuthAnswers(listOf("Answer:"), listOf("")))
        assertEquals("\n", joinAuthAnswers(listOf("A:", "B:"), listOf("", "")))
    }
}
