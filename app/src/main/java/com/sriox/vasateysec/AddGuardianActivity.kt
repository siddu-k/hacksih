package com.sriox.vasateysec

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAddGuardianBinding
import com.sriox.vasateysec.databinding.ItemSmsContactBinding
import com.sriox.vasateysec.models.SmsContact
import com.sriox.vasateysec.utils.SessionManager
import com.sriox.vasateysec.utils.SmsHelper
import java.util.UUID

class AddGuardianActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddGuardianBinding
    private lateinit var smsAdapter: SmsContactAdapter
    private val smsContacts = mutableListOf<SmsContact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupBottomNavigation()
        setupTabs()
        
        loadSmsContacts()

        binding.backButton.setOnClickListener { finish() }

        // Add SMS Contact Logic
        binding.btnAddSmsContact.setOnClickListener {
            val name = binding.etSmsName.text.toString().trim()
            val phone = binding.etSmsPhone.text.toString().trim()
            if (name.isNotEmpty() && phone.isNotEmpty()) {
                saveSmsContact(name, phone)
            } else {
                Toast.makeText(this, "Enter name and phone number", Toast.LENGTH_SHORT).show()
            }
        }

        // Email guardian add redirects to SMS or adds as contact
        binding.addButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            if (email.isNotEmpty()) {
                val name = email.substringBefore("@")
                saveSmsContact(name, email)
                binding.emailInput.text?.clear()
            }
        }
    }

    private fun setupTabs() {
        // Show SMS tab by default as primary offline contacts
        showTab(isGuardian = false)

        binding.btnGuardianTab.setOnClickListener {
            showTab(isGuardian = true)
        }
        binding.btnSmsTab.setOnClickListener {
            showTab(isGuardian = false)
        }
    }

    private fun showTab(isGuardian: Boolean) {
        binding.layoutGuardians.visibility = if (isGuardian) View.VISIBLE else View.GONE
        binding.layoutSmsContacts.visibility = if (isGuardian) View.GONE else View.VISIBLE
        
        if (isGuardian) {
            binding.btnGuardianTab.setBackgroundColor(getColor(R.color.violet))
            binding.btnGuardianTab.setTextColor(getColor(android.R.color.white))
            binding.btnSmsTab.setBackgroundColor(getColor(android.R.color.transparent))
            binding.btnSmsTab.setTextColor(getColor(R.color.text_secondary))
            updateEmptyState(smsContacts.isEmpty(), "No emergency contacts yet")
        } else {
            binding.btnSmsTab.setBackgroundColor(getColor(R.color.golden))
            binding.btnSmsTab.setTextColor(getColor(R.color.background_dark))
            binding.btnGuardianTab.setBackgroundColor(getColor(android.R.color.transparent))
            binding.btnGuardianTab.setTextColor(getColor(R.color.text_secondary))
            updateEmptyState(smsContacts.isEmpty(), "No SMS contacts yet")
        }
    }

    private fun setupRecyclerViews() {
        smsAdapter = SmsContactAdapter(smsContacts) { removeSmsContact(it) }
        binding.rvSmsContacts.layoutManager = LinearLayoutManager(this)
        binding.rvSmsContacts.adapter = smsAdapter

        binding.guardiansRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.guardiansRecyclerView.adapter = smsAdapter
    }

    private fun updateEmptyState(isEmpty: Boolean, title: String) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.tvEmptyTitle.text = title
    }

    private fun loadSmsContacts() {
        val cached = SmsHelper.getFromLocalStorage(this)
        smsContacts.clear()
        smsContacts.addAll(cached)
        smsAdapter.notifyDataSetChanged()
        updateEmptyState(smsContacts.isEmpty(), "No SMS contacts yet")
    }

    private fun saveSmsContact(name: String, phone: String) {
        val userId = SessionManager.getUserId() ?: "local_user"
        val newContact = SmsContact(
            id = UUID.randomUUID().toString(),
            user_id = userId,
            name = name,
            phone = phone
        )
        smsContacts.add(newContact)
        SmsHelper.saveToLocalStorage(this, smsContacts)
        smsAdapter.notifyDataSetChanged()
        
        binding.etSmsName.text?.clear()
        binding.etSmsPhone.text?.clear()
        updateEmptyState(false, "")
        Toast.makeText(this, "Emergency contact added!", Toast.LENGTH_SHORT).show()
    }

    private fun removeSmsContact(contact: SmsContact) {
        smsContacts.removeAll { it.id == contact.id || (it.phone == contact.phone && it.name == contact.name) }
        SmsHelper.saveToLocalStorage(this, smsContacts)
        smsAdapter.notifyDataSetChanged()
        updateEmptyState(smsContacts.isEmpty(), "No SMS contacts yet")
        Toast.makeText(this, "Contact removed", Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomNavigation() {
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        sosButton?.setOnClickListener { com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this) }
    }

    class SmsContactAdapter(private val list: List<SmsContact>, private val onDelete: (SmsContact) -> Unit) : RecyclerView.Adapter<SmsContactAdapter.ViewHolder>() {
        class ViewHolder(val binding: ItemSmsContactBinding) : RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(ItemSmsContactBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val item = list[p]
            h.binding.smsContactName.text = item.name
            h.binding.smsContactPhone.text = item.phone
            h.binding.btnDeleteSms.setOnClickListener { onDelete(item) }
        }
        override fun getItemCount() = list.size
    }
}
