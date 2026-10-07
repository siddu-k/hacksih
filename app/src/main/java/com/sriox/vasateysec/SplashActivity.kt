package com.sriox.vasateysec

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sriox.vasateysec.utils.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val TAG = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Guest-only mode: no login screen. Ensure a guest session exists,
        // then land on Home.
        ensureGuestSession()
        navigateToHome()
    }

    private fun ensureGuestSession() {
        try {
            if (!SessionManager.isLoggedIn()) {
                Log.d(TAG, "No session — creating a guest session")
                SessionManager.saveSession(
                    userId = java.util.UUID.randomUUID().toString(),
                    email = "",
                    name = "Guest",
                    phone = ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Guest session setup failed: ${e.message}")
        }
    }

    private fun navigateToHome() {
        lifecycleScope.launch {
            delay(800)
            val intent = Intent(this@SplashActivity, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
