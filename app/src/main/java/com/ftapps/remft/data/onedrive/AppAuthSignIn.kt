package com.ftapps.remft.data.onedrive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface OneDriveSignIn {
    fun intent(loginHint: String?): Intent
    suspend fun complete(data: Intent?): OneDriveGrant?
}

class AppAuthSignIn(private val context: Context) : OneDriveSignIn {
    private val configuration = AuthorizationServiceConfiguration(
        OneDriveConfig.AUTHORIZE_ENDPOINT.toUri(),
        OneDriveConfig.TOKEN_ENDPOINT.toUri(),
    )

    val redirectUri: String by lazy { OneDriveConfig.redirectUri(context.packageName, signatureHash()) }

    private fun signatureHash(): String {
        val signatures = PackageInfoCompat.getSignatures(context.packageManager, context.packageName)
        val first = signatures.firstOrNull() ?: throw OneDriveException(OneDriveErrors.SIGN_IN_FAILED)
        return OneDriveConfig.signatureHash(first.toByteArray())
    }

    override fun intent(loginHint: String?): Intent {
        val request = AuthorizationRequest.Builder(
            configuration,
            OneDriveConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            redirectUri.toUri(),
        )
            .setScopes(OneDriveConfig.scopes)
            .setPrompt(OneDriveConfig.PROMPT_SELECT_ACCOUNT)
            .apply { if (!loginHint.isNullOrBlank()) setLoginHint(loginHint) }
            .build()
        val service = AuthorizationService(context)
        return try {
            service.getAuthorizationRequestIntent(request)
        } catch (_: ActivityNotFoundException) {
            throw OneDriveException(OneDriveErrors.NO_BROWSER)
        } finally {
            service.dispose()
        }
    }

    override suspend fun complete(data: Intent?): OneDriveGrant? {
        if (data == null) return null
        val failure = AuthorizationException.fromIntent(data)
        val response = AuthorizationResponse.fromIntent(data)
        if (response == null) {
            if (failure == null || failure == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW ||
                failure == AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW
            ) {
                return null
            }
            throw OneDriveException(OneDriveErrors.SIGN_IN_FAILED)
        }
        val service = AuthorizationService(context)
        try {
            val tokens = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { token, error ->
                    if (token != null) {
                        continuation.resume(token)
                    } else {
                        val code = if (error?.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                            error.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code
                        ) {
                            OneDriveErrors.OFFLINE
                        } else {
                            OneDriveErrors.SIGN_IN_FAILED
                        }
                        continuation.resumeWithException(OneDriveException(code))
                    }
                }
            }
            val access = tokens.accessToken ?: throw OneDriveException(OneDriveErrors.SIGN_IN_FAILED)
            val refresh = tokens.refreshToken ?: throw OneDriveException(OneDriveErrors.SIGN_IN_FAILED)
            val expires = tokens.accessTokenExpirationTime?.let { Instant.ofEpochMilli(it) } ?: Instant.now().plus(DEFAULT_LIFETIME)
            return OneDriveGrant(access, refresh, expires)
        } finally {
            service.dispose()
        }
    }

    companion object {
        private val DEFAULT_LIFETIME: Duration = Duration.ofMinutes(30)
    }
}
