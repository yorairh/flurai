package com.flurai.app.core.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
) : AuthRepository {

    private val user = MutableStateFlow(auth.currentUser?.toAuthUser())
    override val currentUser: StateFlow<AuthUser?> = user.asStateFlow()

    init {
        auth.addAuthStateListener { user.value = it.currentUser?.toAuthUser() }
    }

    override suspend fun signUp(name: String, email: String, password: String) = attempt {
        val created = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
            ?: throw AuthException(authErrorMessage(null))
        created.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
        // The auth-state listener fired before the name was set, so publish the user again.
        user.value = created.toAuthUser()
        created.sendEmailVerification().await()
    }

    override suspend fun sendVerificationEmail() = attempt {
        auth.currentUser?.sendEmailVerification()?.await() ?: throw AuthException(authErrorMessage(null))
    }

    override suspend fun refresh() = attempt {
        val current = auth.currentUser ?: return@attempt
        current.reload().await()
        user.value = auth.currentUser?.toAuthUser()
    }

    override suspend fun deleteUnverifiedAccount() = attempt {
        val current = auth.currentUser ?: return@attempt
        current.reload().await()
        if (current.isEmailVerified) throw AuthException("This email is already confirmed, so the account was kept.")
        try {
            current.delete().await()
        } catch (e: FirebaseAuthException) {
            // Firebase only deletes accounts that signed in recently; sign out so the user can start over.
            auth.signOut()
            throw AuthException("Signed out. Sign in again with this email, then choose 'Use a different email' to delete it.")
        }
    }

    override suspend fun signIn(email: String, password: String) = attempt {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    override suspend fun sendPasswordReset(email: String) = attempt {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    override fun signOut() = auth.signOut()

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseAuthException) {
        Result.failure(AuthException(authErrorMessage(e.errorCode)))
    } catch (e: FirebaseNetworkException) {
        Result.failure(AuthException(NETWORK_ERROR_MESSAGE))
    } catch (e: AuthException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AuthException(authErrorMessage(null)))
    }
}

private fun FirebaseUser.toAuthUser() = AuthUser(
    uid = uid,
    name = displayName.orEmpty(),
    email = email.orEmpty(),
    emailVerified = isEmailVerified,
)
