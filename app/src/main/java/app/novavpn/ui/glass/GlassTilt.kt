package app.novavpn.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberGlassTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val tiltState = remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(enabled, context) {
        if (!enabled) {
            tiltState.value = Offset.Zero
            return@DisposableEffect onDispose {}
        }
        val sensorManager = runCatching {
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }.getOrNull()
        val gravity = runCatching {
            sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }.getOrNull()

        if (sensorManager == null || gravity == null) {
            return@DisposableEffect onDispose {}
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 2) return
                val nx = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
                val ny = (event.values[1] / 9.81f).coerceIn(-1f, 1f)
                tiltState.value = Offset(nx, ny)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            sensorManager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
        }.getOrDefault(false)
        onDispose {
            if (registered) {
                runCatching { sensorManager.unregisterListener(listener) }
            }
        }
    }
    return tiltState
}
