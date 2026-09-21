package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sriox.vasateysec.databinding.ActivitySignupBinding
import com.sriox.vasateysec.utils.SessionManager
import java.util.UUID

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.signupButton.setOnClickListener {
            val name = binding.nameInput.text.toString().trim()
            val email = binding.emailInput.text.toString().trim()
            val phone = binding.phoneInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (validateInputs(name, email, phone, password)) {
                signUpLocal(name, email, phone)
            }
        }

        binding.loginPrompt.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun validateInputs(name: String, email: String, phone: String, password: String): Boolean {
        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Name is required"
            return false
        }
        binding.nameInputLayout.error = null

        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Email or Username is required"
            return false
        }
        binding.emailInputLayout.error = null

        if (phone.isEmpty()) {
            binding.phoneInputLayout.error = "Phone number is required"
            return false
        }
        binding.phoneInputLayout.error = null

        return true
    }

    private fun signUpLocal(name: String, email: String, phone: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.signupButton.isEnabled = false

        try {
            val localUserId = UUID.randomUUID().toString()
            SessionManager.saveSession(
                userId = localUserId,
                email = email,
                name = name,
                phone = phone
            )

            Toast.makeText(this, "Welcome, $name!", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Setup error: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            binding.progressBar.visibility = View.GONE
            binding.signupButton.isEnabled = true
        }
    }
}
