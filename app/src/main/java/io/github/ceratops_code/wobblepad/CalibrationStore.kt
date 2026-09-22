package io.github.ceratops_code.wobblepad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Calibration is private to this installation and bound to a board address. */
class CalibrationStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("wobblepad", Context.MODE_PRIVATE)
    fun load(address: String): Map<Pose, List<DoubleArray>>? {
        val text = prefs.getString("calibration:$address", null) ?: return null
        val json = JSONObject(text)
        if (!address.equals(json.getString("device"), ignoreCase = true)) return null
        val data = json.getJSONObject("samples")
        val samples = Pose.entries.associateWith { pose ->
            val list = data.getJSONArray(pose.name)
            require(list.length() in 10..500) { "Saved calibration has an invalid sample count." }
            List(list.length()) { row ->
                val vector = list.getJSONArray(row)
                require(vector.length() == 9) { "Saved calibration has an invalid sensor vector." }
                DoubleArray(9) { vector.getDouble(it) }
            }
        }
        JoystickMapper.calibrate(samples, controlsFor(0))
        return samples
    }
    fun save(address: String, samples: Map<Pose, List<DoubleArray>>) {
        JoystickMapper.calibrate(samples, controlsFor(0))
        val data = JSONObject()
        samples.forEach { (pose, vectors) -> data.put(pose.name, JSONArray().apply {
            vectors.forEach { vector -> put(JSONArray().apply { vector.forEach { put(it) } }) }
        }) }
        val text = JSONObject().put("device", address).put("samples", data).toString()
        prefs.edit().putString("calibration:$address", text).apply()
    }
    var mode: Int
        get() = prefs.getInt("mode", 0).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("mode", value.coerceIn(0, 2)).apply() }
    fun controlsFor(mode: Int): ControlSettings {
        val prefix = "controls:${mode.coerceIn(0, 1)}"
        fun float(name: String, legacy: String, default: Float) =
            if (prefs.contains("$prefix:$name")) prefs.getFloat("$prefix:$name", default)
            else prefs.getFloat(legacy, default)
        fun int(name: String, legacy: String, default: Int) =
            if (prefs.contains("$prefix:$name")) prefs.getInt("$prefix:$name", default)
            else prefs.getInt(legacy, default)
        return ControlSettings(
            left = float("left", "sensitivity:left", 1f).toDouble(),
            right = float("right", "sensitivity:right", 1f).toDouble(),
            up = float("up", "sensitivity:up", 1f).toDouble(),
            down = float("down", "sensitivity:down", 1f).toDouble(),
            deadZone = float("dead-zone", "dead-zone", 0.08f).toDouble(),
            repeatIntervalMs = int("key-repeat-ms", "key-repeat-ms", DEFAULT_KEY_REPEAT_MS),
        ).normalized()
    }
    fun saveControls(mode: Int, value: ControlSettings) {
        val prefix = "controls:${mode.coerceIn(0, 1)}"
        val normalized = value.normalized()
        prefs.edit()
            .putFloat("$prefix:left", normalized.left.toFloat())
            .putFloat("$prefix:right", normalized.right.toFloat())
            .putFloat("$prefix:up", normalized.up.toFloat())
            .putFloat("$prefix:down", normalized.down.toFloat())
            .putFloat("$prefix:dead-zone", normalized.deadZone.toFloat())
            .putInt("$prefix:key-repeat-ms", normalized.repeatIntervalMs)
            .apply()
    }
}
