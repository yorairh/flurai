package com.flurai.app.core.auth

const val MIN_PASSWORD_LENGTH = 8
const val MAX_NAME_LENGTH = 40

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun validateName(name: String): String? = when {
    name.isBlank() -> "Enter your name."
    name.trim().length > MAX_NAME_LENGTH -> "Keep your name under $MAX_NAME_LENGTH characters."
    else -> null
}

fun validateEmail(email: String): String? = when {
    email.isBlank() -> "Enter your email."
    !EMAIL_PATTERN.matches(email.trim()) -> "That email address doesn't look right."
    else -> null
}

fun validateNewPassword(password: String): String? = when {
    password.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters."
    else -> null
}

fun validateExistingPassword(password: String): String? =
    if (password.isEmpty()) "Enter your password." else null
