package com.sriox.vasateysec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.sriox.vasateysec.databinding.ActivityGuardianMapBinding
import com.sriox.vasateysec.utils.BottomNavHelper
import com.sriox.vasateysec.utils.SOSHelper

class GuardianMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityGuardianMapBinding
    private var googleMap: GoogleMap? = null
    private var currentLocationMarker: Marker? = null

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupMap()
        setupBottomNavigation()

        BottomNavHelper.highlightActiveItem(this, BottomNavHelper.NavItem.GHISTORY)

        binding.getLocationButton.setOnClickListener {
            refreshLocation()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "GPS Tracker"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapView) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = true
        googleMap?.uiSettings?.isCompassEnabled = true

        if (checkLocationPermission()) {
            try {
                googleMap?.isMyLocationEnabled = true
            } catch (e: SecurityException) { }
            refreshLocation()
        } else {
            requestLocationPermission()
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (checkLocationPermission()) {
                try {
                    googleMap?.isMyLocationEnabled = true
                } catch (e: SecurityException) { }
                refreshLocation()
            }
        }
    }

    private fun refreshLocation() {
        if (!checkLocationPermission()) {
            requestLocationPermission()
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    currentLocationMarker?.remove()
                    currentLocationMarker = googleMap?.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Current Location")
                            .snippet("Lat: %.4f, Lon: %.4f".format(location.latitude, location.longitude))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                    Toast.makeText(this, "Location updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Getting GPS fix...", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) { }
    }

    private fun setupBottomNavigation() {
        findViewById<android.widget.LinearLayout>(R.id.navGuardians)?.setOnClickListener {
            startActivity(Intent(this, AddGuardianActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
        findViewById<android.widget.LinearLayout>(R.id.navHistory)?.setOnClickListener {
            startActivity(Intent(this, AlertHistoryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.sosButton)?.setOnClickListener {
            SOSHelper.showSOSConfirmation(this)
        }
        findViewById<android.widget.LinearLayout>(R.id.navProfile)?.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
    }
}
