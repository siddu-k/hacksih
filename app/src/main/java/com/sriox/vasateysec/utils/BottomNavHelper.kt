package com.sriox.vasateysec.utils

import android.app.Activity
import android.widget.ImageView
import android.widget.TextView
import com.sriox.vasateysec.R

object BottomNavHelper {
    
    enum class NavItem {
        GUARDIANS, HISTORY, PROFILE, NONE
    }
    
    fun highlightActiveItem(activity: Activity, activeItem: NavItem) {
        // Get all nav items
        val navGuardians = activity.findViewById<android.widget.LinearLayout>(R.id.navGuardians)
        val navHistory = activity.findViewById<android.widget.LinearLayout>(R.id.navHistory)
        val navProfile = activity.findViewById<android.widget.LinearLayout>(R.id.navProfile)
        
        // Reset all to inactive state
        resetNavItem(navGuardians, R.id.iconGuardians, R.id.labelGuardians, activity)
        resetNavItem(navHistory, R.id.iconHistory, R.id.labelHistory, activity)
        resetNavItem(navProfile, R.id.iconProfile, R.id.labelProfile, activity)
        
        // Highlight active item
        when (activeItem) {
            NavItem.GUARDIANS -> highlightNavItem(navGuardians, R.id.iconGuardians, R.id.labelGuardians, activity)
            NavItem.HISTORY -> highlightNavItem(navHistory, R.id.iconHistory, R.id.labelHistory, activity)
            NavItem.PROFILE -> highlightNavItem(navProfile, R.id.iconProfile, R.id.labelProfile, activity)
            NavItem.NONE -> {} // No highlight
        }
    }
    
    private fun resetNavItem(container: android.widget.LinearLayout?, iconId: Int, textId: Int, activity: Activity) {
        container?.let {
            val icon = it.findViewById<ImageView>(iconId)
            val text = it.findViewById<TextView>(textId)
            
            icon?.setColorFilter(activity.getColor(R.color.text_tertiary))
            text?.setTextColor(activity.getColor(R.color.text_tertiary))
            text?.alpha = 0.6f
        }
    }
    
    private fun highlightNavItem(container: android.widget.LinearLayout?, iconId: Int, textId: Int, activity: Activity) {
        container?.let {
            val icon = it.findViewById<ImageView>(iconId)
            val text = it.findViewById<TextView>(textId)
            
            icon?.setColorFilter(activity.getColor(R.color.violet))
            text?.setTextColor(activity.getColor(R.color.violet))
            text?.alpha = 1.0f
        }
    }
}
