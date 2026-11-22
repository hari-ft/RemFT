package com.ftapps.remft.data.onedrive

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

object OneDriveConfig {
    const val CLIENT_ID = "ba03908f-a139-4723-85d9-f45e5b412974"
    const val AUTHORITY = "https://login.microsoftonline.com/common"
    const val AUTHORIZE_ENDPOINT = "$AUTHORITY/oauth2/v2.0/authorize"
    const val TOKEN_ENDPOINT = "$AUTHORITY/oauth2/v2.0/token"
    const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
    const val REDIRECT_SCHEME = "msauth"
    const val DESTINATION_ID = "onedrive"
    const val PROMPT_SELECT_ACCOUNT = "select_account"
    val scopes: List<String> = listOf("Files.ReadWrite.AppFolder", "User.Read", "offline_access")
    val scope: String get() = scopes.joinToString(" ")

    fun signatureHash(certificate: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(certificate))

    fun redirectUri(applicationId: String, signatureHash: String): String =
        "$REDIRECT_SCHEME://$applicationId/${URLEncoder.encode(signatureHash, "UTF-8")}"
}

object OneDriveErrors {
    const val OFFLINE = "OFFLINE"
    const val RECONNECT = "ONEDRIVE_RECONNECT"
    const val SIGNED_OUT = "ONEDRIVE_SIGNED_OUT"
    const val ERROR = "ONEDRIVE_ERROR"
    const val DENIED = "ONEDRIVE_DENIED"
    const val FULL = "ONEDRIVE_FULL"
    const val CANCELLED = "ONEDRIVE_CANCELLED"
    const val SIGN_IN_FAILED = "ONEDRIVE_SIGN_IN_FAILED"
    const val NO_BROWSER = "ONEDRIVE_NO_BROWSER"
    const val OTHER_ACCOUNT = "ONEDRIVE_OTHER_ACCOUNT"
    const val MIRROR_MISSING = "MIRROR_MISSING"
    const val MIRROR_DAMAGED = "MIRROR_DAMAGED"
    const val MIRROR_NEEDS_ONEDRIVE = "MIRROR_NEEDS_ONEDRIVE"
    const val MIRROR_CHECK = "MIRROR_CHECK"
    const val BUILD = "BUILD"
    const val UPLOAD = "UPLOAD"
    const val UNKNOWN = "UNKNOWN"
    const val LOCAL_GONE = "LOCAL_GONE"
    const val MISMATCH = "MISMATCH"

    val reconnectCodes: Set<String> = setOf(RECONNECT, SIGNED_OUT)
}

class OneDriveException(val code: String) : Exception(code)

class LocalFileGone : Exception("The local file went away")
