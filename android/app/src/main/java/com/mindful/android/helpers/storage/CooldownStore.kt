package com.mindful.android.helpers.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores per-app cooldown settings and their runtime state.
 *
 * A cooldown locks an app for [Config.lockSec] seconds after it has been used for
 * [Config.useSec] seconds. The use time is counted cumulatively, starting again from zero
 * each time a lock ends and at midnight.
 *
 * Settings are kept in their own preferences file, separate from the Flutter database.
 */
object CooldownStore {
    private const val PREFS_BOX = "CooldownPrefs"
    private const val KEY_CONFIGS = "configs"
    private const val KEY_STATES = "states"

    data class Config(val useSec: Int, val lockSec: Int)

    /**
     * @param baselineSec Today's usage of the app when the current use window started.
     * @param lockedUntilMs Epoch millis until which the app is locked, 0 when not locked.
     */
    data class State(val baselineSec: Long = 0L, val lockedUntilMs: Long = 0L)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_BOX, Context.MODE_PRIVATE)

    private fun readConfigs(context: Context): Map<String, Config> {
        val json = prefs(context).getString(KEY_CONFIGS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith {
                val c = obj.getJSONObject(it)
                Config(c.optInt("useSec", 0), c.optInt("lockSec", 0))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun readStates(context: Context): Map<String, State> {
        val json = prefs(context).getString(KEY_STATES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith {
                val st = obj.getJSONObject(it)
                State(st.optLong("baselineSec", 0L), st.optLong("lockedUntilMs", 0L))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeStates(context: Context, states: Map<String, State>) {
        val obj = JSONObject()
        states.forEach { (pkg, st) ->
            obj.put(
                pkg,
                JSONObject().put("baselineSec", st.baselineSec).put("lockedUntilMs", st.lockedUntilMs)
            )
        }
        prefs(context).edit().putString(KEY_STATES, obj.toString()).apply()
    }

    fun getConfig(context: Context, packageName: String): Config? =
        readConfigs(context)[packageName]?.takeIf { it.useSec > 0 && it.lockSec > 0 }

    fun hasConfigs(context: Context): Boolean =
        readConfigs(context).values.any { it.useSec > 0 && it.lockSec > 0 }

    /**
     * Sets the cooldown of an app. A null config or a config with a zero value removes it.
     */
    @Synchronized
    fun setConfig(context: Context, packageName: String, config: Config?) {
        val configs = readConfigs(context).toMutableMap()
        val states = readStates(context).toMutableMap()

        if (config == null || config.useSec <= 0 || config.lockSec <= 0) {
            configs.remove(packageName)
            states.remove(packageName)
        } else {
            configs[packageName] = config
            // A changed config starts a fresh window and clears any running lock
            states.remove(packageName)
        }

        val obj = JSONObject()
        configs.forEach { (pkg, c) ->
            obj.put(pkg, JSONObject().put("useSec", c.useSec).put("lockSec", c.lockSec))
        }
        prefs(context).edit().putString(KEY_CONFIGS, obj.toString()).apply()
        writeStates(context, states)
    }

    fun getState(context: Context, packageName: String): State =
        readStates(context)[packageName] ?: State()

    @Synchronized
    fun setState(context: Context, packageName: String, state: State) {
        writeStates(context, readStates(context).toMutableMap().also { it[packageName] = state })
    }

    /**
     * Starts a fresh use window for every app at midnight, when screen time resets to zero.
     * Running locks are kept.
     */
    @Synchronized
    fun resetBaselines(context: Context) {
        writeStates(
            context,
            readStates(context).mapValues { it.value.copy(baselineSec = 0L) }
        )
    }

    /**
     * All cooldown settings as a JSON array for the Flutter side.
     */
    fun configsAsJson(context: Context): String {
        val arr = JSONArray()
        readConfigs(context).forEach { (pkg, c) ->
            arr.put(
                JSONObject().put("appPackage", pkg).put("useSec", c.useSec).put("lockSec", c.lockSec)
            )
        }
        return arr.toString()
    }
}
