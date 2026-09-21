package com.sriox.vasateysec

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAddSmsContactBinding
import com.sriox.vasateysec.models.SmsContact
import com.sriox.vasateysec.utils.SessionManager
import com.sriox.vasateysec.utils.SmsHelper
import java.util.UUID

class AddSmsContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddSmsContactBinding
    private lateinit var smsAdapter: SmsContactAdapter
    private val contactList = mutableListOf<SmsContact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddSmsContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadSmsContacts()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAddSmsContact.setOnClickListener {
            val name = binding.etSmsName.text.toString().trim()
            val phone = binding.etSmsPhone.text.toString().trim()

            if (name.isNotEmpty() && phone.isNotEmpty()) {
                saveSmsContact(name, phone)
            } else {
                Toast.makeText(this, "Please enter name and phone", Toast.LENGTH_SHORT).show()
            }
        }

        setupBottomNavigation()
    }

    private fun setupRecyclerView() {
        smsAdapter = SmsContactAdapter(contactList) { contact ->
            deleteSmsContact(contact)
        }
        binding.rvSmsContacts.apply {
            layoutManager = LinearLayoutManager(this@AddSmsContactActivity)
            adapter = smsAdapter
        }
    }

    private fun loadSmsContacts() {
        contactList.clear()
        contactList.addAll(SmsHelper.getFromLocalStorage(this))
        smsAdapter.notifyDataSetChanged()
    }

    private fun saveSmsContact(name: String, phone: String) {
        val userId = SessionManager.getUserId() ?: "local_user"
        val contact = SmsContact(
            id = UUID.randomUUID().toString(),
            user_id = userId,
            name = name,
            phone = phone
        )
        contactList.add(contact)
        SmsHelper.saveToLocalStorage(this, contactList)
        smsAdapter.notifyDataSetChanged()
        
        binding.etSmsName.text?.clear()
        binding.etSmsPhone.text?.clear()
        Toast.makeText(this, "Contact Added", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSmsContact(contact: SmsContact) {
        contactList.removeAll { it.id == contact.id || (it.phone == contact.phone && it.name == contact.name) }
        SmsHelper.saveToLocalStorage(this, contactList)
        smsAdapter.notifyDataSetChanged()
        Toast.makeText(this, "Contact Deleted", Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener {
            val intent = android.content.Intent(this, AddGuardianActivity::class.java)
            startActivity(intent)
            finish()
        }
        navHistory?.setOnClickListener {
            val intent = android.content.Intent(this, AlertHistoryActivity::class.java)
            startActivity(intent)
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            val intent = android.content.Intent(this, GuardianMapActivity::class.java)
            startActivity(intent)
            finish()
        }
        navProfile?.setOnClickListener {
            val intent = android.content.Intent(this, EditProfileActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    class SmsContactAdapter(private val list: List<SmsContact>, private val onDelete: (SmsContact) -> Unit) :
        RecyclerView.Adapter<SmsContactAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val phone: TextView = view.findViewById(android.R.id.text2)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.phone.text = item.phone
            holder.itemView.setOnLongClickListener { onDelete(item); true }
        }
        override fun getItemCount() = list.size
    }
}
