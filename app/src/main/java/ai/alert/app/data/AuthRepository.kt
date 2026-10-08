package ai.alert.app.data

import android.app.Activity
import android.content.Context
import ai.alert.app.BuildConfig
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthRepository(private val context: Context) {
    private val supabase = SupabaseClient.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun initialize(): Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    fun currentUser(): UserInfo? = supabase.auth.currentUserOrNull()

    fun signIn(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val e = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            onError("Enter a valid email address"); return
        }
        if (password.isBlank()) { onError("Enter your password"); return }

        scope.launch {
            try {
                supabase.auth.signInWith(Email) {
                    email = e
                    this.password = password
                }
                onSuccess()
            } catch (t: Throwable) { onError(message(t)) }
        }
    }

    fun signUp(name: String, email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val n = name.trim()
        val e = email.trim()
        if (n.length !in 2..50) { onError("Name must be 2–50 characters"); return }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) { onError("Enter a valid email address"); return }
        if (password.length < 8) { onError("Password must be at least 8 characters"); return }

        scope.launch {
            try {
                supabase.auth.signUpWith(Email) {
                    email = e
                    this.password = password
                    data = buildJsonObject { put("display_name", n) }
                }
                val user = supabase.auth.currentUserOrNull()
                if (user != null) {
                    upsertProfile(user.id, n, e)
                    onSuccess()
                } else {
                    onError("Account created. Confirm your email, then sign in.")
                }
            } catch (t: Throwable) { onError(message(t)) }
        }
    }

    fun signInWithGoogle(activity: Activity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)
                val option = GetGoogleIdOption.Builder()
                    .setServerClientId(activity.getString(ai.alert.app.R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val result = credentialManager.getCredential(
                    activity,
                    GetCredentialRequest.Builder().addCredentialOption(option).build()
                )
                val credential = result.credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    onError("Google did not return a valid account credential"); return@launch
                }

                val google = try {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } catch (_: GoogleIdTokenParsingException) {
                    onError("Could not read the Google account credential"); return@launch
                }

                supabase.auth.signInWith(IDToken) {
                    idToken = google.idToken
                    provider = Google
                }

                val user = supabase.auth.currentUserOrNull()
                if (user == null) { onError("Google sign-in failed"); return@launch }
                val name = google.displayName?.trim().orEmpty().ifBlank {
                    user.email?.substringBefore("@") ?: "User"
                }
                upsertProfile(user.id, name, user.email.orEmpty())
                onSuccess()
            } catch (t: Throwable) {
                onError(message(t))
            }
        }
    }

    fun sendPasswordReset(email: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val e = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            onError("Enter a valid email address"); return
        }
        scope.launch {
            try {
                supabase.auth.resetPasswordForEmail(e)
                onSuccess()
            } catch (t: Throwable) { onError(message(t)) }
        }
    }

    fun setPassword(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (password.length < 8) { onError("Password must be at least 8 characters"); return }
        scope.launch {
            try {
                supabase.auth.updateUser { this.password = password }
                onSuccess()
            } catch (t: Throwable) { onError(message(t)) }
        }
    }

    fun changePassword(current: String, newPassword: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (newPassword.length < 8) { onError("New password must be at least 8 characters"); return }
        scope.launch {
            try {
                supabase.auth.updateUser {
                    this.password = newPassword
                }
                onSuccess()
            } catch (t: Throwable) { onError(message(t)) }
        }
    }

    fun updateDisplayName(name: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val n = name.trim()
        if (n.length !in 2..50) { onError("Name must be 2–50 characters"); return }
        scope.launch {
            try {
                supabase.auth.updateUser {
                    data {
                        put("display_name", n)
                    }
                }
                currentUser()?.let { upsertProfile(it.id, n, it.email.orEmpty()) }
                onSuccess()
            } catch (t: Throwable) { onError(message(t)) }
        }
    }

    fun signOut(onDone: () -> Unit = {}) {
        scope.launch {
            try { supabase.auth.signOut() } catch (_: Throwable) {}
            onDone()
        }
    }

    fun deleteAccount(onSuccess: () -> Unit, onError: (String) -> Unit) {
        onError("For safety, account deletion will be enabled through a secured server function in the next backend deployment.")
    }

    fun hasPasswordProvider(): Boolean {
        val identities = currentUser()?.identities ?: return false
        return identities.any { it.provider == "email" }
    }

    private suspend fun upsertProfile(uid: String, name: String, email: String) {
        try {
            supabase.from("profiles").upsert(
                ProfileRow(id = uid, display_name = name, email = email)
            )
        } catch (_: Throwable) {
            // Profile creation is best-effort; RLS/backend remains the source of truth.
        }
    }

    private fun message(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("Invalid login credentials", true) -> "Email or password is incorrect"
            raw.contains("User already registered", true) -> "An account already exists with this email"
            raw.contains("Email not confirmed", true) -> "Please confirm your email before signing in"
            raw.contains("Network", true) -> "Network error. Check your connection"
            else -> raw.substringAfter(": ").ifBlank { "Authentication request failed" }
        }
    }
}
