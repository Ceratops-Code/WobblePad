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
        JoystickMapper.calibrate(samples, controls)
        return samples
    }
    fun save(address: String, samples: Map<Pose, List<DoubleArray>>) {
        JoystickMapper.calibrate(samples, controls)
        val data = JSONObject()
        samples.forEach { (pose, vectors) -> data.put(pose.name, JSONArray().apply {
            vectors.forEach { vector -> put(JSONArray().apply { vector.forEach { put(it) } }) }
        }) }
        val text = JSONObject().put("device", address).put("samples", data).toString()
        prefs.edit().putString("calibration:$address", text).apply()
    }
    var mode: Int
        get() = prefs.getInt("mode", 0).coerceIn(0, 1)
        set(value) { prefs.edit().putInt("mode", value.coerceIn(0, 1)).apply() }
    var controls: ControlSettings
        get() = ControlSettings(
            left = prefs.getFloat("sensitivity:left", 1f).toDouble(),
            right = prefs.getFloat("sensitivity:right", 1f).toDouble(),
            up = prefs.getFloat("sensitivity:up", 1f).toDouble(),
            down = prefs.getFloat("sensitivity:down", 1f).toDouble(),
            deadZone = prefs.getFloat("dead-zone", 0.08f).toDouble(),
            repeatIntervalMs = prefs.getInt("key-repeat-ms", DEFAULT_KEY_REPEAT_MS),
        ).normalized()
        set(value) {
            val normalized = value.normalized()
            prefs.edit()
                .putFloat("sensitivity:left", normalized.left.toFloat())
                .putFloat("sensitivity:right", normalized.right.toFloat())
                .putFloat("sensitivity:up", normalized.up.toFloat())
                .putFloat("sensitivity:down", normalized.down.toFloat())
                .putFloat("dead-zone", normalized.deadZone.toFloat())
                .putInt("key-repeat-ms", normalized.repeatIntervalMs)
                .apply()
        }
}
