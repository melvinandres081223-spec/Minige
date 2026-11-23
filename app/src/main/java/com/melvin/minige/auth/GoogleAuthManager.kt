package com.melvin.minige.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.melvin.minige.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface DriveAuthResult {
    data class Authorized(val accessToken: String) : DriveAuthResult
    data class NeedsConsent(val intentSender: IntentSender) : DriveAuthResult
    data class NeedsConsentIntent(val intent: Intent) : DriveAuthResult
    data class Failure(val message: String) : DriveAuthResult
}

object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_APPDATA_SCOPE = DRIVE_FILE_SCOPE
    val DRIVE_SCOPE = Scope(DRIVE_FILE_SCOPE)

    fun createGoogleSignInOptions(clientId: String): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(clientId)
            .requestEmail()
            .requestScopes(DRIVE_SCOPE)
            .build()
    }

    fun hasDrivePermission(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)
    }

    suspend fun checkOrRequestDriveAuthorization(context: Context): DriveAuthResult =
        fetchFreshDriveAccessToken(context)

    /**
     * Clears a stale/unauthorized OAuth access token from Google Play Services cache so the next
     * token request fetches a fresh token instead of returning the rejected cached token.
     */
    suspend fun invalidateDriveAccessToken(context: Context, staleToken: String?) {
        val tokenToClear = staleToken?.trim().orEmpty()
        if (tokenToClear.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context.applicationContext, tokenToClear)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear stale Drive access token: ${e.message}")
            }
        }
    }

    /**
     * Always requests a fresh Google OAuth access token for [DRIVE_FILE_SCOPE] via Google Play
     * Services [Identity.getAuthorizationClient], falling back to [GoogleAuthUtil.getToken]
     * if needed and surfacing [DriveAuthResult.NeedsConsent] / [DriveAuthResult.NeedsConsentIntent]
     * when user interaction is required.
     */
    suspend fun fetchFreshDriveAccessToken(context: Context): DriveAuthResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val authClientError: Exception? = try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(DRIVE_SCOPE))
                .build()
            val result = Identity.getAuthorizationClient(appContext)
                .authorize(request)
                .await()

            val pendingIntent = result.pendingIntent
            val token = result.accessToken
            when {
                result.hasResolution() && pendingIntent != null -> {
                    return@withContext DriveAuthResult.NeedsConsent(pendingIntent.intentSender)
                }
                !token.isNullOrBlank() -> {
                    return@withContext DriveAuthResult.Authorized(token)
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AuthorizationClient token fetch failed, attempting GoogleAuthUtil fallback: ${e.message}")
            e
        }

        val account = resolveGoogleAccount(appContext)
        if (account != null) {
            try {
                val scopeString = "oauth2:$DRIVE_FILE_SCOPE"
                val token = GoogleAuthUtil.getToken(appContext, account, scopeString)
                if (!token.isNullOrBlank()) {
                    return@withContext DriveAuthResult.Authorized(token)
                }
            } catch (e: UserRecoverableAuthException) {
                val recoveryIntent = e.intent
                return@withContext if (recoveryIntent != null) {
                    DriveAuthResult.NeedsConsentIntent(recoveryIntent)
                } else {
                    DriveAuthResult.Failure(e.localizedMessage ?: "Google Drive re-authentication required.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GoogleAuthUtil.getToken fallback failed: ${e.message}")
                return@withContext DriveAuthResult.Failure(
                    e.localizedMessage ?: authClientError?.localizedMessage ?: "Failed to authorize Google Drive."
                )
            }
        }

        DriveAuthResult.Failure(
            authClientError?.localizedMessage ?: "No Google Drive access token returned."
        )
    }

    private fun resolveGoogleAccount(context: Context): Account? {
        val gsaAccount = runCatching { GoogleSignIn.getLastSignedInAccount(context)?.account }.getOrNull()
        if (gsaAccount != null) return gsaAccount
        val email = runCatching { Firebase.auth.currentUser?.email }.getOrNull()?.trim().orEmpty()
        return if (email.isNotEmpty()) Account(email, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE) else null
    }

    fun extractDriveTokenFromIntent(context: Context, data: Intent?): String? {
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            result.accessToken
        } catch (_: Exception) {
            null
        }
    }

    fun currentUser(): FirebaseUser? = Firebase.auth.currentUser

    fun authStateFlow(auth: FirebaseAuth = Firebase.auth): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope,
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(context, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun signInWithGoogle(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {},
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val activityContext = context as? Activity ?: context
                val cred = credentialManager.getCredential(activityContext, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type received.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Google Sign-In failed")
            }
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {},
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
