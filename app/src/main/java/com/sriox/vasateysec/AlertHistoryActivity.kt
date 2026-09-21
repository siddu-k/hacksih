package com.sriox.vasateysec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sriox.vasateysec.databinding.ActivityAlertHistoryBinding
import com.sriox.vasateysec.databinding.ItemAlertBinding
import com.sriox.vasateysec.models.AlertHistory
import com.sriox.vasateysec.utils.AlertManager
import com.sriox.vasateysec.utils.BottomNavHelper
import com.sriox.vasateysec.utils.SessionManager
import java.io.File
import java.time.OffsetDateTime

class AlertHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertHistoryBinding
    private lateinit var alertAdapter: LocalAlertAdapter
    private val alertList = mutableListOf<AlertHistory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupBottomNavigation()

        BottomNavHelper.highlightActiveItem(this, BottomNavHelper.NavItem.HISTORY)

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.btnDeleteAll.setOnClickListener {
            showDeleteAllConfirmation()
        }

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to delete all alert history?")
            .setPositiveButton("Clear All") { _, _ ->
                AlertManager.clearLocalAlertHistory(this)
                loadHistory()
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupRecyclerView() {
        val currentUserId = SessionManager.getUserId() ?: "local_user"
        alertAdapter = LocalAlertAdapter(alertList, currentUserId) { alert ->
            openAlertDetails(alert)
        }
        binding.alertsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AlertHistoryActivity)
            adapter = alertAdapter
        }
    }

    private fun loadHistory() {
        binding.loadingProgress.visibility = View.VISIBLE
        val localAlerts = AlertManager.getLocalAlertHistory(this)
        alertList.clear()
        alertList.addAll(localAlerts)
        alertAdapter.notifyDataSetChanged()

        binding.loadingProgress.visibility = View.GONE
        if (alertList.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.alertsRecyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.alertsRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun openAlertDetails(alert: AlertHistory) {
        val intent = Intent(this, EmergencyAlertViewerActivity::class.java).apply {
            putExtra("fullName", alert.user_name)
            putExtra("phoneNumber", alert.user_phone)
            putExtra("email", alert.user_email)
            putExtra("latitude", alert.latitude?.toString() ?: "")
            putExtra("longitude", alert.longitude?.toString() ?: "")
            putExtra("timestamp", alert.created_at)
            putExtra("frontPhotoUrl", alert.front_photo_url)
            putExtra("backPhotoUrl", alert.back_photo_url)
            putExtra("isSmsAlert", true)
        }
        startActivity(intent)
    }

    private fun setupBottomNavigation() {
        val navGuardians = findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val sosButton = findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)
        val navGhistory = findViewById<android.widget.LinearLayout>(R.id.navGhistory)
        val navProfile = findViewById<android.widget.LinearLayout>(R.id.navProfile)

        navGuardians?.setOnClickListener {
            startActivity(Intent(this, AddGuardianActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
        sosButton?.setOnClickListener {
            com.sriox.vasateysec.utils.SOSHelper.showSOSConfirmation(this)
        }
        navGhistory?.setOnClickListener {
            startActivity(Intent(this, GuardianMapActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
        navProfile?.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
    }

    class LocalAlertAdapter(
        private val items: List<AlertHistory>,
        private val currentUserId: String,
        private val onClick: (AlertHistory) -> Unit
    ) : RecyclerView.Adapter<LocalAlertAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemAlertBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val alert = items[position]
            val context = holder.itemView.context

            holder.binding.alertUserName.text = alert.user_name
            holder.binding.alertEmail.text = alert.user_email
            holder.binding.alertPhone.text = alert.user_phone
            holder.binding.alertStatus.text = "EMERGENCY"
            holder.binding.alertType.text = "SOS"
            holder.binding.alertType.setTextColor(context.getColor(R.color.pink))
            holder.binding.alertLocation.text = if (alert.latitude != null) "📍 Lat: %.4f, Long: %.4f".format(alert.latitude, alert.longitude) else "📍 Location unavailable"

            holder.binding.alertTime.text = alert.created_at ?: "Recently"

            val frontFile = alert.front_photo_url?.let { File(it) }
            val backFile = alert.back_photo_url?.let { File(it) }
            val hasFront = frontFile?.exists() == true
            val hasBack = backFile?.exists() == true

            if (hasFront || hasBack) {
                holder.binding.photosContainer.visibility = View.VISIBLE
                if (hasFront) {
                    holder.binding.frontPhotoThumb.visibility = View.VISIBLE
                    com.bumptech.glide.Glide.with(context).load(frontFile).into(holder.binding.frontPhotoThumb)
                } else {
                    holder.binding.frontPhotoThumb.visibility = View.GONE
                }
                if (hasBack) {
                    holder.binding.backPhotoThumb.visibility = View.VISIBLE
                    com.bumptech.glide.Glide.with(context).load(backFile).into(holder.binding.backPhotoThumb)
                } else {
                    holder.binding.backPhotoThumb.visibility = View.GONE
                }
            } else {
                holder.binding.photosContainer.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(alert) }
        }

        override fun getItemCount() = items.size
    }
}
