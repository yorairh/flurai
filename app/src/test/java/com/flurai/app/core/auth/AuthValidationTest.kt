package com.flurai.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthValidationTest {
    @Test
    fun name() {
        assertNotNull(validateName("   "))
        assertNotNull(validateName("a".repeat(MAX_NAME_LENGTH + 1)))
        assertNull(validateName("  Aki  "))
    }

    @Test
    fun email() {
        assertNotNull(validateEmail(""))
        assertNotNull(validateEmail("aki"))
        assertNotNull(validateEmail("aki@example"))
        assertNotNull(validateEmail("a ki@example.com"))
        assertNull(validateEmail(" aki@example.com "))
    }

    @Test
    fun newPasswordNeedsMinimumLength() {
        assertNotNull(validateNewPassword("a".repeat(MIN_PASSWORD_LENGTH - 1)))
        assertNull(validateNewPassword("a".repeat(MIN_PASSWORD_LENGTH)))
    }

    @Test
    fun existingPasswordOnlyNeedsToBePresent() {
        assertNotNull(validateExistingPassword(""))
        assertNull(validateExistingPassword("x"))
    }

    @Test
    fun errorCodesMapToFriendlyMessages() {
        assertEquals("Wrong email or password.", authErrorMessage("ERROR_INVALID_CREDENTIAL"))
        assertEquals(authErrorMessage("ERROR_WRONG_PASSWORD"), authErrorMessage("ERROR_USER_NOT_FOUND"))
        assertEquals("Something went wrong. Please try again.", authErrorMessage("SOMETHING_NEW"))
        assertEquals(authErrorMessage(null), authErrorMessage("SOMETHING_NEW"))
    }
}
