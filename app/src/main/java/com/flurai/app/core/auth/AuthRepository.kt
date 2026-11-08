package com.flurai.app.core.auth

import kotlinx.coroutines.flow.StateFlow

data class AuthUser(
    val uid: String,
    val name: String,
    val email: String,
)

/** Sign-in with name + email + password. Failures carry a message that is safe to show the user. */
interface AuthRepository {
    /** The signed-in user, or null when signed out. */
    val currentUser: StateFlow<AuthUser?>

    suspend fun signUp(name: String, email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    fun signOut()
}

class AuthException(message: String) : Exception(message)
