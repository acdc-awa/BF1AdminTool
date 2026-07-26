package com.bf1.admin.tool.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatedCookiesTest {

    /**
     * 回归测试：一次认证流程会依次经过 getAccessToken 和 getAuthCode，
     * 若后一步只下发 sid 就把先前拿到的新 remid 抹掉，长期凭证就丢了。
     */
    @Test
    fun laterResponseWithOnlySidKeepsRemidFromEarlierResponse() {
        val afterAccessToken = accumulateRotatedCookies(
            RotatedCookies(),
            listOf("remid=NEW_REMID; Path=/; HttpOnly")
        )
        val afterAuthCode = accumulateRotatedCookies(
            afterAccessToken,
            listOf("sid=NEW_SID; Path=/; HttpOnly")
        )

        assertEquals("NEW_REMID", afterAuthCode.remid)
        assertEquals("NEW_SID", afterAuthCode.sid)
    }

    @Test
    fun laterResponseOverwritesWhenItCarriesANewValue() {
        val first = accumulateRotatedCookies(RotatedCookies(), listOf("remid=FIRST"))
        val second = accumulateRotatedCookies(first, listOf("remid=SECOND"))

        assertEquals("SECOND", second.remid)
    }

    @Test
    fun bothCookiesInASingleResponseAreCaptured() {
        val rotated = accumulateRotatedCookies(
            RotatedCookies(),
            listOf("remid=R; Path=/", "sid=S; Path=/; Secure")
        )

        assertEquals("R", rotated.remid)
        assertEquals("S", rotated.sid)
    }

    @Test
    fun responseWithoutSetCookieLeavesPreviousUntouched() {
        val previous = RotatedCookies(remid = "R", sid = "S")

        assertEquals(previous, accumulateRotatedCookies(previous, emptyList()))
    }

    @Test
    fun unrelatedCookiesAreIgnored() {
        val previous = RotatedCookies(remid = "R")
        val rotated = accumulateRotatedCookies(
            previous,
            listOf("_ga=GA1.2.3; Path=/", "JSESSIONID=abc")
        )

        assertEquals(previous, rotated)
    }

    /** `remid=; Max-Age=0` 是删除指令，不是新凭证 —— 不能用空串覆盖库里可用的值。 */
    @Test
    fun emptyValueIsTreatedAsDeletionAndDoesNotClobber() {
        val previous = RotatedCookies(remid = "GOOD_REMID", sid = "GOOD_SID")
        val rotated = accumulateRotatedCookies(
            previous,
            listOf("remid=; Max-Age=0; Path=/", "sid=   ; Path=/")
        )

        assertEquals("GOOD_REMID", rotated.remid)
        assertEquals("GOOD_SID", rotated.sid)
    }

    @Test
    fun malformedHeadersAreSkipped() {
        val rotated = accumulateRotatedCookies(
            RotatedCookies(),
            listOf("", "no-equals-sign", "; Path=/", "remid=SURVIVES")
        )

        assertEquals("SURVIVES", rotated.remid)
        assertEquals(null, rotated.sid)
    }

    @Test
    fun cookieNameMatchingIsCaseInsensitive() {
        val rotated = accumulateRotatedCookies(RotatedCookies(), listOf("REMID=R", "Sid=S"))

        assertEquals("R", rotated.remid)
        assertEquals("S", rotated.sid)
    }

    @Test
    fun hasAnyReflectsWhetherAnythingWasRotated() {
        assertFalse(RotatedCookies().hasAny)
        assertTrue(RotatedCookies(remid = "R").hasAny)
        assertTrue(RotatedCookies(sid = "S").hasAny)
    }
}
