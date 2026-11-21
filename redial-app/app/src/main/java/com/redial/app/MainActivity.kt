package com.redial.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var numberInput: EditText
    private lateinit var attemptsInput: EditText
    private lateinit var pauseInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val stateListener: (RedialState.Snapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        numberInput = findViewById(R.id.number)
        attemptsInput = findViewById(R.id.maxAttempts)
        pauseInput = findViewById(R.id.pause)
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)
        statusText = findViewById(R.id.status)
        logText = findViewById(R.id.log)

        if (savedInstanceState == null) {
            numberInput.setText(prefs.getString(KEY_NUMBER, ""))
            attemptsInput.setText(prefs.getInt(KEY_ATTEMPTS, 0).toString())
            pauseInput.setText(prefs.getInt(KEY_PAUSE, 5).toString())
            handleTelIntent(intent)
        }

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener {
            startService(Intent(this, RedialService::class.java).setAction(RedialService.ACTION_STOP))
        }
        findViewById<Button>(R.id.pickContact).setOnClickListener {
            val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            try {
                startActivityForResult(pick, REQUEST_CONTACT)
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть контакты", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTelIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        RedialState.addListener(stateListener)
    }

    override fun onStop() {
        RedialState.removeListener(stateListener)
        super.onStop()
    }

    private fun handleTelIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel") numberInput.setText(Uri.decode(data.schemeSpecificPart))
    }

    private fun onStartClicked() {
        val number = cleanNumber(numberInput.text.toString())
        if (number.isEmpty()) {
            numberInput.error = getString(R.string.enter_number)
            return
        }
        val attempts = attemptsInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val pause = pauseInput.text.toString().toIntOrNull()?.coerceIn(1, 3600) ?: 5
        prefs.edit()
            .putString(KEY_NUMBER, number)
            .putInt(KEY_ATTEMPTS, attempts)
            .putInt(KEY_PAUSE, pause)
            .apply()

        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startRedial(number, attempts, pause)
    }

    private fun startRedial(number: String, attempts: Int, pause: Int) {
        val intent = Intent(this, RedialService::class.java)
            .setAction(RedialService.ACTION_START)
            .putExtra(RedialService.EXTRA_NUMBER, number)
            .putExtra(RedialService.EXTRA_MAX_ATTEMPTS, attempts)
            .putExtra(RedialService.EXTRA_PAUSE_SEC, pause)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (RedialService.hasPermissions(this)) {
            // Notifications are optional: the redial works without them.
            startRedial(
                prefs.getString(KEY_NUMBER, "").orEmpty(),
                prefs.getInt(KEY_ATTEMPTS, 0),
                prefs.getInt(KEY_PAUSE, 5),
            )
        } else {
            AlertDialog.Builder(this)
                .setMessage(R.string.permissions_needed)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONTACT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
            )?.use { c -> if (c.moveToFirst()) numberInput.setText(c.getString(0)) }
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось прочитать номер", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(state: RedialState.Snapshot) {
        startButton.visibility = if (state.running) View.GONE else View.VISIBLE
        stopButton.visibility = if (state.running) View.VISIBLE else View.GONE
        numberInput.isEnabled = !state.running
        attemptsInput.isEnabled = !state.running
        pauseInput.isEnabled = !state.running
        statusText.text = state.status.ifEmpty { getString(R.string.status_idle) }
        logText.text = state.log.asReversed().joinToString("\n")
    }

    private fun neededPermissions(): List<String> = buildList {
        addAll(RedialService.REQUIRED_PERMISSIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun cleanNumber(raw: String): String =
        raw.trim().filterIndexed { i, ch -> ch.isDigit() || ch == '*' || ch == '#' || (ch == '+' && i == 0) }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_CONTACT = 2
        private const val KEY_NUMBER = "number"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_PAUSE = "pause"
    }
}
