package com.flurai.app.core.auth

/** Turns a Firebase Auth error code (FirebaseAuthException.errorCode) into a message for the user. */
fun authErrorMessage(errorCode: String?): String = when (errorCode) {
    "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
    "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Try signing in."
    "ERROR_WEAK_PASSWORD" -> "Choose a stronger password (at least 8 characters)."
    "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" ->
        "Wrong email or password."
    "ERROR_USER_DISABLED" -> "This account has been disabled."
    "ERROR_REQUIRES_RECENT_LOGIN" -> "Please sign in again first."
    "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a few minutes and try again."
    "ERROR_OPERATION_NOT_ALLOWED" ->
        "Email sign-in isn't turned on for this app yet (enable Email/Password in the Firebase console)."
    else -> "Something went wrong. Please try again."
}

/** Firebase limits how often it sends emails or accepts attempts. */
const val TOO_MANY_REQUESTS_MESSAGE = "An email was sent a moment ago. Wait a few minutes, then check your inbox (and spam) before asking again."

const val NETWORK_ERROR_MESSAGE = "No internet connection. Check your connection and try again."
