package com.kitchendesigner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

/** Shell activity. The Unity view will be hosted in a dedicated fragment/activity
 *  once :unityLibrary is integrated; the Compose editor below is the 2D/logic UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { EditorScreen() }
            }
        }
    }
}
