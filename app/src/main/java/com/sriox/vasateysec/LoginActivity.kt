package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sriox.vasateysec.databinding.ActivityLoginBinding
import com.sriox.vasateysec.utils.SessionManager
import java.util.UUID

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill existing local info if available
        SessionManager.getUserEmail()?.let { binding.emailInput.setText(it) }

        binding.loginButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (validateInputs(email, password)) {
                loginLocal(email)
            }
        }

        binding.signupPrompt.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
            finish()
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Name or email is required"
            return false
        }
        binding.emailInputLayout.error = null
        return true
    }

    private fun loginLocal(email: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.loginButton.isEnabled = false

        try {
            val userId = SessionManager.getUserId() ?: UUID.randomUUID().toString()
            val userName = SessionManager.getUserName() ?: email.substringBefore("@").ifBlank { "User" }
            val userPhone = SessionManager.getUserPhone() ?: ""

            SessionManager.saveSession(
                userId = userId,
                email = email,
                name = userName,
                phone = userPhone
            )

            Toast.makeText(this, "Welcome back, $userName!", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Login error: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            binding.progressBar.visibility = View.GONE
            binding.loginButton.isEnabled = true
        }
    }
}
