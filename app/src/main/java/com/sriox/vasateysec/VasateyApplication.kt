package com.sriox.vasateysec

import android.app.Application
import android.util.Log
import com.google.android.gms.common.GoogleApiAvailability
import com.sriox.vasateysec.utils.SessionManager

class VasateyApplication : Application() {
    
    companion object {
        lateinit var instance: VasateyApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        try {
            // Initialize Google Play Services
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
            
            if (resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS) {
                Log.d("VasateyApplication", "Google Play Services is available")
            } else {
                Log.e("VasateyApplication", "Google Play Services not available: ${googleApiAvailability.getErrorString(resultCode)}")
            }
            
            // Initialize SessionManager
            SessionManager.initialize(this)
            
        } catch (e: Exception) {
            Log.e("VasateyApplication", "Error during initialization", e)
        }
    }
}
