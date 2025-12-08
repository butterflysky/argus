package dev.butterflysky.argus.common

sealed class LoginResult {
    object Allow : LoginResult()

    data class Deny(val message: String) : LoginResult()
}
