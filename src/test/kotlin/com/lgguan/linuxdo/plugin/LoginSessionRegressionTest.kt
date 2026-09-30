package com.lgguan.linuxdo.plugin

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.model.CurrentUserResponse
import com.lgguan.linuxdo.plugin.model.UserInfo
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LoginSessionRegressionTest {
    @Test fun `pasted credentials replace existing browser account before confirming the new user`() {
        val jar = PersistentCookieJar(false)
        jar.injectCookie("_t", "old-synthetic")
        var browserToken = "old-synthetic"
        var imports = 0
        val auth = LinuxDoAuthService(jar, {
            assertEquals("new-synthetic", jar.getUserToken())
            assertEquals(jar.getUserToken(), browserToken)
            Result.success(Gson().fromJson(
                """{"current_user":{"id":42,"username":"new-account","trust_level":2}}""",
                CurrentUserResponse::class.java).currentUser)
        }, { it() }, { cookies, version ->
            SessionEpoch.requireCurrent(version)
            imports++
            browserToken = cookies.single { it.name == "_t" }.value
        })
        auth.setCurrentUserDirectly(UserInfo(7, "old-account"))
        val oldEpoch = SessionEpoch.current
        auth.replaceCredentials(LoginCookieSupport.parseHeader("Cookie: _t=new-synthetic; _forum_session=new-session"))
        assertTrue(SessionEpoch.current > oldEpoch)
        assertFalse(auth.isLoggedIn)
        assertEquals(LinuxDoAuthService.Status.CREDENTIALS_PENDING, auth.status)
        var success = false
        auth.refreshCurrentUser(force = true) { success = it }
        assertEquals(1, imports)
        assertTrue(success)
        assertTrue(auth.isLoggedIn)
        assertEquals(42, auth.currentUser?.id)
        assertNull(auth.lastVerificationFailure)
    }

    @Test fun `failed cookie verification keeps the reason available for retry without confirming login`() {
        val jar = PersistentCookieJar(false)
        val denied = HttpStatusException(404)
        val auth = LinuxDoAuthService(jar, { Result.failure(denied) }, { it() }, { _, _ -> })
        auth.replaceCredentials(LoginCookieSupport.parseHeader("_t=expired-synthetic"))
        auth.refreshCurrentUser(force = true)
        assertFalse(auth.isLoggedIn)
        assertSame(denied, auth.lastVerificationFailure)
        assertEquals("expired-synthetic", jar.getUserToken())
    }

    @Test fun `temporary verification failure retains a confirmed account but server rejection revokes it`() {
        val jar = PersistentCookieJar(false).apply { injectCookie("_t", "synthetic") }
        var failure: Throwable = RateLimitException(30)
        val auth = LinuxDoAuthService(jar, { Result.failure(failure) }, { it() })
        auth.setCurrentUserDirectly(UserInfo(42, "account"))
        auth.refreshCurrentUser(force = true)
        assertTrue(auth.isLoggedIn)
        failure = HttpStatusException(401)
        auth.refreshCurrentUser(force = true)
        assertFalse(auth.isLoggedIn)
    }

    @Test fun `late verification cannot restore the account replaced by a pasted cookie`() {
        val jar = PersistentCookieJar(false).apply { injectCookie("_t", "old") }
        val jobs = mutableListOf<() -> Unit>()
        val auth = LinuxDoAuthService(jar, { Result.success(UserInfo(7, "old-account")) }, { jobs += it }, { _, _ -> })
        auth.refreshCurrentUser(force = true)
        auth.replaceCredentials(LoginCookieSupport.parseHeader("_t=new"))
        jobs.single().invoke()
        assertFalse(auth.isLoggedIn)
        assertEquals("new", jar.getUserToken())
    }
}
