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
        
        validateSessionAndNavigate()
    }
    
    private fun validateSessionAndNavigate() {
        lifecycleScope.launch {
            delay(1000)
            Log.d(TAG, "Checking local session...")
            
            val hasLocalSession = SessionManager.isLoggedIn()
            Log.d(TAG, "Local session exists: $hasLocalSession")
            
            if (hasLocalSession) {
                navigateToHome()
            } else {
                navigateToLogin()
            }
        }
    }
    
    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
