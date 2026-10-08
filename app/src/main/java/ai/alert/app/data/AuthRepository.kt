package ai.alert.app.data

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AuthRepository(private val context: Context) {

    private val auth: FirebaseAuth
        get() = Firebase.auth

    private val googleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    fun initialize(): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun currentUser() = auth.currentUser

    /**
     * Starts the modern Android Credential Manager Google sign-in flow.
     *
     * Firebase's Google provider uses the web OAuth client ID generated from
     * google-services.json, not the Android client ID.
     */
    fun signInWithGoogle(
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        googleScope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(
                        activity.getString(
                            ai.alert.app.R.string.default_web_client_id
                        )
                    )
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    context = activity,
                    request = request
                )

                val credential = result.credential
                if (credential !is CustomCredential ||
                    credential.type != TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    onError("Google sign-in returned an unsupported credential")
                    return@launch
                }

                val googleIdTokenCredential = try {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } catch (_: GoogleIdTokenParsingException) {
                    onError("Could not read the Google account credential")
                    return@launch
                }

                val firebaseCredential = GoogleAuthProvider.getCredential(
                    googleIdTokenCredential.idToken,
                    null
                )

                auth.signInWithCredential(firebaseCredential)
                    .addOnSuccessListener { result ->
                        val user = result.user
                        if (user == null) {
                            onError("Google sign-in failed")
                            return@addOnSuccessListener
                        }

                        val displayName = user.displayName?.trim().orEmpty().ifBlank {
                            user.email?.substringBefore("@") ?: "Google User"
                        }
                        val email = user.email.orEmpty()

                        saveProfile(user.uid, displayName, email) { profileError ->
                            if (profileError != null) {
                                onError(profileError)
                            } else {
                                if (result.additionalUserInfo?.isNewUser == true) {
                                    sendWelcomeEmail()
                                }
                                onSuccess()
                            }
                        }
                    }
                    .addOnFailureListener { onError(authError(it)) }
            } catch (error: Exception) {
                onError(googleError(error))
            }
        }
    }

    fun signUp(
        displayName: String,
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val name = displayName.trim()
        val normalizedEmail = email.trim()
        if (name.length < 2) {
            onError("Enter your name")
            return
        }
        if (name.length > 50) {
            onError("Name must be 50 characters or less")
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            onError("Enter a valid email address")
            return
        }
        if (password.length < 8) {
            onError("Password must be at least 8 characters")
            return
        }

        auth.createUserWithEmailAndPassword(normalizedEmail, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    onError("Account creation failed")
                    return@addOnSuccessListener
                }

                val profile = UserProfileChangeRequest.Builder()
                    .setDisplayName(name)
                    .build()

                user.updateProfile(profile)
                    .addOnSuccessListener {
                        saveProfile(user.uid, name, normalizedEmail) { profileError ->
                            if (profileError != null) {
                                onError(profileError)
                            } else {
                                requestEmailOtp(
                                    onSuccess = { onSuccess() },
                                    onError = { onError(it) }
                                )
                            }
                        }
                    }
                    .addOnFailureListener {
                        onError("Account created, but profile setup failed")
                    }
            }
            .addOnFailureListener { onError(authError(it)) }
    }

    fun requestEmailOtp(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        functions.getHttpsCallable("requestEmailOtp")
            .call()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(functionError(it)) }
    }

    fun verifyEmailOtp(
        code: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val normalized = code.trim()
        if (!Regex("\\d{6}").matches(normalized)) {
            onError("Enter the 6-digit code")
            return
        }
        functions.getHttpsCallable("verifyEmailOtp")
            .call(mapOf("code" to normalized))
            .addOnSuccessListener {
                auth.currentUser?.reload()?.addOnCompleteListener { onSuccess() } ?: onSuccess()
            }
            .addOnFailureListener { onError(functionError(it)) }
    }

    fun setPassword(
        newPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        if (newPassword.length < 8) {
            onError("Password must be at least 8 characters")
            return
        }
        val email = user.email
        if (email.isNullOrBlank()) {
            onError("This account has no email address")
            return
        }

        val credential = EmailAuthProvider.getCredential(email, newPassword)
        if (user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }) {
            user.updatePassword(newPassword)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { onError(authError(it)) }
        } else {
            user.linkWithCredential(credential)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { onError(authError(it)) }
        }
    }

    fun hasPasswordProvider(): Boolean {
        return auth.currentUser?.providerData
            ?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true
    }

    private fun sendWelcomeEmail() {
        functions.getHttpsCallable("sendWelcomeEmail")
            .call()
            .addOnFailureListener { /* Sign-in must not fail because an email provider is temporarily unavailable. */ }
    }

    private fun functionError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("resource-exhausted", ignoreCase = true) ->
                "Too many attempts. Please wait and try again."
            message.contains("deadline-exceeded", ignoreCase = true) ->
                "Verification code expired. Request a new code."
            message.contains("permission-denied", ignoreCase = true) ->
                "Incorrect verification code."
            message.contains("failed-precondition", ignoreCase = true) ->
                "No active verification code. Request a new one."
            else -> message.substringAfter(": ").ifBlank { "Request failed. Please try again." }
        }
    }

    fun signIn(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val normalizedEmail = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            onError("Enter a valid email address")
            return
        }
        if (password.isEmpty()) {
            onError("Enter your password")
            return
        }

        auth.signInWithEmailAndPassword(normalizedEmail, password)
            .addOnSuccessListener {
                syncProfile()
                onSuccess()
            }
            .addOnFailureListener { onError(authError(it)) }
    }

    fun sendPasswordReset(
        email: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val normalizedEmail = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            onError("Enter a valid email address")
            return
        }

        auth.sendPasswordResetEmail(normalizedEmail)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(authError(it)) }
    }

    fun sendVerification(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        user.sendEmailVerification()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(authError(it)) }
    }

    fun refreshUser(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        user.reload()
            .addOnSuccessListener {
                syncProfile()
                onSuccess()
            }
            .addOnFailureListener { onError(authError(it)) }
    }

    fun updateDisplayName(
        displayName: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        val name = displayName.trim()
        if (name.length < 2 || name.length > 50) {
            onError("Name must be between 2 and 50 characters")
            return
        }

        user.updateProfile(
            UserProfileChangeRequest.Builder()
                .setDisplayName(name)
                .build()
        )
            .addOnSuccessListener {
                syncProfile()
                onSuccess()
            }
            .addOnFailureListener { onError(authError(it)) }
    }

    fun updateEmail(
        newEmail: String,
        currentPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        val email = newEmail.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            onError("Enter a valid email address")
            return
        }
        if (currentPassword.isEmpty()) {
            onError("Enter your current password")
            return
        }

        reauthenticate(user, currentPassword, {
            user.updateEmail(email)
                .addOnSuccessListener {
                    user.sendEmailVerification()
                    syncProfile()
                    onSuccess()
                }
                .addOnFailureListener { onError(authError(it)) }
        }, onError)
    }

    fun changePassword(
        currentPassword: String,
        newPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        if (currentPassword.isEmpty()) {
            onError("Enter your current password")
            return
        }
        if (newPassword.length < 8) {
            onError("New password must be at least 8 characters")
            return
        }

        reauthenticate(user, currentPassword, {
            user.updatePassword(newPassword)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { onError(authError(it)) }
        }, onError)
    }

    fun deleteAccount(
        currentPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = auth.currentUser ?: run {
            onError("You are not signed in")
            return
        }
        if (currentPassword.isEmpty()) {
            onError("Enter your current password")
            return
        }

        reauthenticate(user, currentPassword, {
            Firebase.firestore.collection("users")
                .document(user.uid)
                .delete()
                .addOnSuccessListener {
                    user.delete()
                        .addOnSuccessListener {
                            onSuccess()
                        }
                        .addOnFailureListener { onError(authError(it)) }
                }
                .addOnFailureListener { onError("Could not remove your account data") }
        }, onError)
    }

    fun signOut() {
        auth.signOut()
    }

    private fun reauthenticate(
        user: com.google.firebase.auth.FirebaseUser,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val email = user.email
        if (email.isNullOrBlank()) {
            onError("This account cannot use password re-authentication")
            return
        }

        val credential = EmailAuthProvider.getCredential(email, password)
        user.reauthenticate(credential)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener {
                onError("Current password is incorrect or sign-in is required again")
            }
    }

    private fun syncProfile() {
        val user = auth.currentUser ?: return
        val name = user.displayName ?: ""
        val email = user.email ?: ""
        saveProfile(user.uid, name, email, null)
    }

    private fun saveProfile(
        uid: String,
        displayName: String,
        email: String,
        callback: ((String?) -> Unit)?
    ) {
        Firebase.firestore.collection("users")
            .document(uid)
            .set(
                mapOf(
                    "displayName" to displayName,
                    "email" to email,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { callback?.invoke(null) }
            .addOnFailureListener {
                callback?.invoke("Could not save your account profile")
            }
    }

    private fun authError(error: Throwable): String {
        val code = (error as? FirebaseAuthException)?.errorCode ?: ""
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "Enter a valid email address"
            "ERROR_WEAK_PASSWORD" -> "Password is too weak"
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already exists with this email"
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                "An account already exists with this email. Sign in using that method first."
            "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL" ->
                "Email or password is incorrect"
            "ERROR_WRONG_PASSWORD" -> "Email or password is incorrect"
            "ERROR_USER_DISABLED" -> "This account has been disabled"
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Try again later"
            "ERROR_REQUIRES_RECENT_LOGIN" ->
                "Please sign in again and retry this action"
            "ERROR_NETWORK_REQUEST_FAILED" ->
                "Network error. Check your internet connection"
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "This sign-in method is not enabled in Firebase"
            "ERROR_INVALID_API_KEY" ->
                "Firebase configuration is invalid"
            "ERROR_APP_NOT_AUTHORIZED" ->
                "This Android app is not authorized in Firebase"
            else -> error.message ?: "Authentication request failed"
        }
    }

    private fun googleError(error: Throwable): String {
        val name = error::class.java.simpleName
        if (name.contains("NoCredential", ignoreCase = true)) {
            return "No Google account was selected"
        }
        if (name.contains("Cancellation", ignoreCase = true)) {
            return "Google sign-in was cancelled"
        }
        return error.message ?: "Google sign-in failed"
    }
}
