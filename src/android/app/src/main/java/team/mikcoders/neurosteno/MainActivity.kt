package team.mikcoders.neurosteno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import team.mikcoders.neurosteno.ui.StenoApp
import team.mikcoders.neurosteno.ui.theme.NeuroStenoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeuroStenoTheme {
                StenoApp()
            }
        }
    }
}
