package com.redial.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Calls a number again and again until the callee answers.
 *
 * One attempt:
 *  1. place the call through Telecom;
 *  2. wait for the line to go OFFHOOK (dialing) and back to IDLE (call over);
 *  3. read the call duration from the call log: Android counts an outgoing
 *     call's duration from the moment it is answered, so 0 s means nobody
 *     picked up (no answer, rejected, busy, unreachable);
 *  4. answered -> stop; not answered -> pause and go to 1.
 */
class RedialService : Service() {

    private enum class Phase { DIALING, IN_CALL, CHECKING, WAITING }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telephony: TelephonyManager
    private lateinit var telecom: TelecomManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var number = ""
    private var maxAttempts = 0
    private var pauseMs = 5_000L

    private var phase = Phase.WAITING
    private var attempt = 0
    private var attemptStartedAt = 0L
    private var lineState = TelephonyManager.CALL_STATE_IDLE
    private var listening = false

    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(TelephonyManager::class.java)
        telecom = getSystemService(TelecomManager::class.java)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                intent.getStringExtra(EXTRA_NUMBER).orEmpty(),
                intent.getIntExtra(EXTRA_MAX_ATTEMPTS, 0),
                intent.getIntExtra(EXTRA_PAUSE_SEC, 5),
            )
            ACTION_STOP -> finish("Остановлено вручную", success = null)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (RedialState.current.running) RedialState.update { it.copy(running = false) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- flow

    private fun start(number: String, maxAttempts: Int, pauseSec: Int) {
        if (number.isBlank() || !hasPermissions(this)) {
            stopSelf()
            return
        }
        handler.removeCallbacksAndMessages(null)
        this.number = number
        this.maxAttempts = maxAttempts.coerceAtLeast(0)
        this.pauseMs = pauseSec.coerceIn(1, 3600) * 1000L
        attempt = 0

        RedialState.update {
            RedialState.Snapshot(running = true, number = number, maxAttempts = this.maxAttempts)
        }
        RedialState.log("Старт: $number")

        ServiceCompat.startForeground(
            this, NOTIFICATION_PROGRESS, progressNotification("Начинаю дозвон…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        acquireWakeLock()
        lineState = try {
            @Suppress("DEPRECATION")
            telephony.callState
        } catch (e: SecurityException) {
            TelephonyManager.CALL_STATE_IDLE
        }
        startListening()
        nextAttempt()
    }

    private fun nextAttempt() {
        if (lineState != TelephonyManager.CALL_STATE_IDLE) {
            // The phone is busy with another call: wait until it is free.
            setStatus("Линия занята другим звонком, жду…")
            phase = Phase.WAITING
            handler.postDelayed(::nextAttempt, 3_000)
            return
        }
        attempt++
        phase = Phase.DIALING
        attemptStartedAt = System.currentTimeMillis()
        RedialState.update { it.copy(attempt = attempt) }
        setStatus("Звоню… попытка ${attemptLabel()}")
        RedialState.log("Попытка ${attemptLabel()}")

        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: SecurityException) {
            finish("Нет разрешения на звонки", success = false)
            return
        }
        handler.postDelayed(dialTimeout, DIAL_TIMEOUT_MS)
    }

    private val dialTimeout = Runnable {
        if (phase == Phase.DIALING) {
            RedialState.log("Звонок не начался")
            afterUnanswered()
        }
    }

    private fun onLineState(state: Int) {
        lineState = state
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (phase == Phase.DIALING) {
                handler.removeCallbacks(dialTimeout)
                phase = Phase.IN_CALL
                setStatus("Идёт вызов… попытка ${attemptLabel()}")
            }
            TelephonyManager.CALL_STATE_IDLE -> if (phase == Phase.IN_CALL) {
                phase = Phase.CHECKING
                setStatus("Проверяю, ответили ли…")
                // The call log entry is written a moment after the call ends.
                handler.postDelayed({ checkResult(retriesLeft = 4) }, 1_500)
            }
        }
    }

    private fun checkResult(retriesLeft: Int) {
        if (phase != Phase.CHECKING) return
        val duration = lastOutgoingDuration()
        when {
            duration == null && retriesLeft > 0 ->
                handler.postDelayed({ checkResult(retriesLeft - 1) }, 1_500)
            duration != null && duration > 0 -> {
                RedialState.log("Ответили! Разговор $duration с")
                finish("Дозвонились с попытки $attempt (разговор ${formatDuration(duration)})", success = true)
            }
            else -> {
                RedialState.log(if (duration == null) "Нет записи в журнале вызовов" else "Не ответили")
                afterUnanswered()
            }
        }
    }

    private fun afterUnanswered() {
        if (maxAttempts in 1..attempt) {
            finish("Не дозвонились за $attempt попыток", success = false)
            return
        }
        phase = Phase.WAITING
        setStatus("Не ответили. Следующий звонок через ${pauseMs / 1000} с…")
        handler.postDelayed(::nextAttempt, pauseMs)
    }

    /** Duration in seconds of the newest outgoing call made in this attempt, or null if not logged yet. */
    @SuppressLint("MissingPermission")
    private fun lastOutgoingDuration(): Long? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        val projection = arrayOf(CallLog.Calls.DURATION)
        val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), (attemptStartedAt - 5_000).toString())
        return try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args, "${CallLog.Calls.DATE} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun finish(message: String, success: Boolean?) {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        RedialState.log(message)
        RedialState.update { it.copy(running = false, status = message) }
        if (success != null) showResult(message, success)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------- phone state

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        listening = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onLineState(state)
            }
            telephonyCallback = cb
            telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onLineState(state)
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let { telephony.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
            phoneStateListener = null
        }
    }

    // ---------------------------------------------------- notifications

    private fun setStatus(text: String) {
        RedialState.update { it.copy(status = text) }
        if (RedialState.current.running) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_PROGRESS, progressNotification(text))
        }
    }

    private fun progressNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RedialService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Дозвон: $number")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun showResult(message: String, success: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(if (success) "Дозвонились: $number" else "Не дозвонились: $number")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_RESULT, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ------------------------------------------------------------ misc

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Redial::session")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun attemptLabel() = if (maxAttempts > 0) "$attempt из $maxAttempts" else "$attempt"

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun formatDuration(sec: Long) = if (sec < 60) "$sec с" else "${sec / 60} мин ${sec % 60} с"

    companion object {
        const val ACTION_START = "com.redial.app.START"
        const val ACTION_STOP = "com.redial.app.STOP"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_MAX_ATTEMPTS = "max_attempts"
        const val EXTRA_PAUSE_SEC = "pause_sec"

        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_RESULT = "result"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_RESULT = 2
        private const val DIAL_TIMEOUT_MS = 20_000L

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
        )

        fun hasPermissions(context: Context) = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS, context.getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT, context.getString(R.string.channel_result),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }
}
