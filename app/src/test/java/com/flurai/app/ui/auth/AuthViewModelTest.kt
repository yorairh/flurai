package com.flurai.app.ui.auth

import com.flurai.app.core.auth.AuthException
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.AuthUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private class FakeAuth : AuthRepository {
        override val currentUser: StateFlow<AuthUser?> = MutableStateFlow(null)
        var result: Result<Unit> = Result.success(Unit)
        val calls = mutableListOf<String>()
        override suspend fun signUp(name: String, email: String, password: String) = result.also { calls += "signUp:$name" }
        override suspend fun signIn(email: String, password: String) = result.also { calls += "signIn:$email" }
        override suspend fun sendPasswordReset(email: String) = result.also { calls += "reset:$email" }
        override fun continueAsGuest() {}
        override fun signOut() {}
        override suspend fun sendVerificationEmail() = Result.success(Unit)
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun deleteUnverifiedAccount() = Result.success(Unit)
        override suspend fun reauthenticate(password: String) = Result.success(Unit)
        override suspend fun deleteAccount() = Result.success(Unit)
    }

    private val auth = FakeAuth()
    private lateinit var vm: AuthViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = AuthViewModel(auth)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun invalidSignUpShowsFieldErrorsWithoutCallingServer() {
        vm.show(AuthScreen.SignUp)
        vm.onEmail("not-an-email")
        vm.onPassword("short")
        vm.signUp()

        val s = vm.state.value
        assertNotNull(s.nameError)
        assertNotNull(s.emailError)
        assertNotNull(s.passwordError)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun validSignUpCallsServer() {
        vm.onName("Aki")
        vm.onEmail("aki@example.com")
        vm.onPassword("longenough")
        vm.signUp()

        assertEquals(listOf("signUp:Aki"), auth.calls)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.formError)
    }

    @Test
    fun serverErrorIsShown() {
        auth.result = Result.failure(AuthException("Wrong email or password."))
        vm.onEmail("aki@example.com")
        vm.onPassword("whatever")
        vm.signIn()

        assertEquals("Wrong email or password.", vm.state.value.formError)
    }

    @Test
    fun resetMarksEmailSentAndSwitchingScreensKeepsEmail() {
        vm.onEmail("aki@example.com")
        vm.show(AuthScreen.ResetPassword)
        assertEquals("aki@example.com", vm.state.value.email)

        vm.sendReset()
        assertTrue(vm.state.value.resetEmailSent)
        assertEquals(listOf("reset:aki@example.com"), auth.calls)
    }
}
