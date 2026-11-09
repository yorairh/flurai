package com.flurai.app.core.auth

import kotlinx.coroutines.flow.StateFlow

data class AuthUser(
    val uid: String,
    val name: String,
    val email: String,
    /** The email link has been opened. Unverified accounts only see the "check your email" screen. */
    val emailVerified: Boolean = true,
)

/** Sign-in with name + email + password. Failures carry a message that is safe to show the user. */
interface AuthRepository {
    /** The signed-in user, or null when signed out. */
    val currentUser: StateFlow<AuthUser?>

    suspend fun signUp(name: String, email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    suspend fun sendVerificationEmail(): Result<Unit>

    /** Reloads the signed-in account from Firebase (e.g. to see whether the email was verified). */
    suspend fun refresh(): Result<Unit>

    /** Deletes the signed-in account if its email was never verified (a mistyped address). */
    suspend fun deleteUnverifiedAccount(): Result<Unit>

    /** Confirms the signed-in user's password (Firebase needs a recent sign-in before deleting). */
    suspend fun reauthenticate(password: String): Result<Unit>

    /** Permanently deletes the signed-in account. Call [reauthenticate] first. */
    suspend fun deleteAccount(): Result<Unit>
    fun signOut()
}

class AuthException(message: String) : Exception(message)
