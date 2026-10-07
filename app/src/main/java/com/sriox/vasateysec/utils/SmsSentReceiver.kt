package com.sriox.vasateysec.utils

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val phone = intent?.getStringExtra("phone") ?: "unknown"
        val resultCode = resultCode
        
        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d("SmsSentReceiver", "✅ SUCCESS: SMS actually sent by system to $phone")
            }
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                // Radio dead (airplane mode / no SIM / out of coverage):
                // the send path thought it dispatched, so re-queue here.
                Log.e("SmsSentReceiver", "❌ No radio for $phone — re-queueing alert for retry")
                try {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            AlertQueueManager.enqueueAlert(
                                context = ctx.applicationContext,
                                latitude = if (intent?.hasExtra("latitude") == true) intent.getDoubleExtra("latitude", 0.0) else null,
                                longitude = if (intent?.hasExtra("longitude") == true) intent.getDoubleExtra("longitude", 0.0) else null,
                                situationSummary = intent?.getStringExtra("situationSummary"),
                                isHardware = intent?.getBooleanExtra("isHardware", false) == true
                            )
                        } catch (e: Exception) {
                            Log.w("SmsSentReceiver", "Re-queue failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SmsSentReceiver", "Re-queue launch failed: ${e.message}")
                }
            }
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Generic failure for $phone. (Check balance/SIM)")
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Null PDU for $phone")
            }
            else -> {
                Log.e("SmsSentReceiver", "❌ FAILURE: Error code $resultCode for $phone")
            }
        }
    }
}
