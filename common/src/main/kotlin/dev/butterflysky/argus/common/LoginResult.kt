package dev.butterflysky.argus.common

sealed class LoginResult {
    object Allow : LoginResult()
    data class AllowWithKick(val message: String) : LoginResult()
    data class Deny(val message: String) : LoginResult()
}
