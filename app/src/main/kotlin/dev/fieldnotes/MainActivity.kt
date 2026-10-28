package dev.fieldnotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.fieldnotes.feature.FieldNotesApp
import dev.fieldnotes.legacy.LegacyActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FieldNotesApp(
                environmentName = if (BuildConfig.DEMO) "Demo · on-device server" else "Local server · 10.0.2.2",
                onOpenLegacy = { startActivity(Intent(this, LegacyActivity::class.java)) },
                localServer = !BuildConfig.DEMO,
            )
        }
    }
}
