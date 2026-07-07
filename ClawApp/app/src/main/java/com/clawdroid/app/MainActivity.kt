package com.clawdroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.clawdroid.app.ui.ClawdroidApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debugSeedLongOverview = intent?.getBooleanExtra(EXTRA_DEBUG_SEED_LONG_OVERVIEW, false) == true
        setContent {
            ClawdroidApp(debugSeedLongOverview = debugSeedLongOverview)
        }
    }

    companion object {
        const val EXTRA_DEBUG_SEED_LONG_OVERVIEW = "debug_seed_long_overview"
    }
}
