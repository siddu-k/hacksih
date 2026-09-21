package com.sriox.vasateysec.models

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val name: String,
    val email: String,
    val phone: String
)

@Serializable
data class Guardian(
    val id: String? = null,
    val user_id: String,
    val guardian_email: String,
    val guardian_user_id: String? = null,
    val status: String = "active"
)

@Serializable
data class AlertHistory(
    val id: String? = null,
    val user_id: String,
    val user_name: String,
    val user_email: String,
    val user_phone: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val location_accuracy: Float? = null,
    val alert_type: String = "voice_help",
    val status: String = "sent",
    val created_at: String? = null,
    val front_photo_url: String? = null,
    val back_photo_url: String? = null
)

@Serializable
data class SmsContact(
    val id: String? = null,
    val user_id: String,
    val name: String,
    val phone: String,
    val created_at: String? = null
)
