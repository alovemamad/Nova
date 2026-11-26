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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

@Composable
fun rememberGlassTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tiltState = remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(enabled, context, lifecycleOwner) {
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

        var lastCommittedX = 0f
        var lastCommittedY = 0f
        var isRegistered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 2) return
                val nx = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
                val ny = (event.values[1] / 9.81f).coerceIn(-1f, 1f)
                if (abs(nx - lastCommittedX) >= 0.025f || abs(ny - lastCommittedY) >= 0.025f) {
                    lastCommittedX = nx
                    lastCommittedY = ny
                    tiltState.value = Offset(nx, ny)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun register() {
            if (!isRegistered) {
                isRegistered = runCatching {
                    sensorManager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
                }.getOrDefault(false)
            }
        }

        fun unregister() {
            if (isRegistered) {
                runCatching { sensorManager.unregisterListener(listener) }
                isRegistered = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return tiltState
}

fun glassTiltAngleDegrees(tilt: Offset): Float =
    45f + (tilt.x * 35f - tilt.y * 20f).coerceIn(-55f, 55f)

