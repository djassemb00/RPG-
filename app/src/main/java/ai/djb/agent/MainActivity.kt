package ai.djb.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.djb.agent.core.AgentNotifications
import ai.djb.agent.ui.AgentRoot
import ai.djb.agent.ui.theme.DjbTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentNotifications.ensureChannel(this)
        val app = application as AgentApp
        setContent {
            DjbTheme {
                AgentRoot(app)
            }
        }
    }
}
