package com.ishhf.fightarena

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File

/** خريطة كبيرة، نزول بالمظلة، ٩٠+ بوت محلي، ولاعبين حقيقيين متزامنين */
class RoyaleActivity : AppCompatActivity() {

    companion object {
        private const val MOVE_SPEED = 10f
        private const val ATTACK_RANGE_BOT = 90f
        private const val ATTACK_COOLDOWN_MS = 450L
        private const val UPDATE_INTERVAL_MS = 400L
        private const val ZONE_SHRINK_DURATION_MS = 10 * 60 * 1000L // 10 دقايق
        private const val MIN_ZONE_RADIUS = 300f
        private const val MAX_RECORD_MS = 15000L
        private const val PLAYER_STALE_MS = 15000L
        private const val BOT_COUNT = 92
    }

    private val db = FirebaseFirestore.getInstance()
    private lateinit var prefs: SharedPreferences
    private lateinit var royaleView: RoyaleView
    private lateinit var myId: String
    private val handler = Handler(Looper.getMainLooper())

    private var movingUp = false
    private var movingDown = false
    private var movingLeft = false
    private var movingRight = false
    private var lastAttackTime = 0L
    private var lastPush = 0L

    private val remoteRaw = HashMap<String, Pair<RemotePlayer, Long>>()

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartTime = 0L
    private var recordAutoStop: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_royale)
        prefs = getSharedPreferences("fight_arena_prefs", MODE_PRIVATE)
        myId = prefs.getString("my_username", "") ?: ""

        val weaponName = intent.getStringExtra(BattleActivity.EXTRA_WEAPON) ?: WeaponType.SWORD.name
        val myWeapon = WeaponType.valueOf(weaponName)

        royaleView = findViewById(R.id.royaleView)
        royaleView.myWeapon = myWeapon
        royaleView.myX = (Math.random() * RoyaleView.WORLD_SIZE).toFloat()
        royaleView.myY = (Math.random() * RoyaleView.WORLD_SIZE).toFloat()
        royaleView.bots = Bot.spawnHerd(BOT_COUNT, RoyaleView.WORLD_SIZE)

        setupControls()
        requestAudioPermission()
        ensureZoneState()
        startFallIntro()
        listenRemotePlayers()
        listenVoice()
        startGameLoop()
    }

    private fun setupControls() {
        fun hold(id: Int, setFlag: (Boolean) -> Unit) {
            findViewById<Button>(id).setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> setFlag(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setFlag(false)
                }
                true
            }
        }
        hold(R.id.btnUp) { movingUp = it }
        hold(R.id.btnDown) { movingDown = it }
        hold(R.id.btnLeft) { movingLeft = it }
        hold(R.id.btnRight) { movingRight = it }

        findViewById<Button>(R.id.btnAttack).setOnClickListener { performAttack() }

        findViewById<Button>(R.id.btnMic).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startRecording()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRecordingAndSend()
            }
            true
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 40)
        }
    }

    // ===== حالة الدائرة المشتركة =====

    private fun ensureZoneState() {
        val ref = db.collection("royale_state").document("current")
        ref.get().addOnSuccessListener { doc ->
            val startTime = doc.getLong("startTime")
            val now = System.currentTimeMillis()
            if (startTime == null || now - startTime > ZONE_SHRINK_DURATION_MS + 60000L) {
                ref.set(mapOf("startTime" to now))
            }
        }
        ref.addSnapshotListener { doc, _ ->
            val startTime = doc?.getLong("startTime") ?: return@addSnapshotListener
            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0)
            val progress = (elapsed.toFloat() / ZONE_SHRINK_DURATION_MS).coerceIn(0f, 1f)
            royaleView.zoneRadius = RoyaleView.WORLD_SIZE / 2 - progress * (RoyaleView.WORLD_SIZE / 2 - MIN_ZONE_RADIUS)
        }
    }

    // ===== المظلة =====

    private fun startFallIntro() {
        royaleView.falling = true
        royaleView.fallProgressY = -400f
        val step = object : Runnable {
            override fun run() {
                royaleView.fallProgressY += 25f
                if (royaleView.fallProgressY >= royaleView.height / 2f) {
                    royaleView.falling = false
                    Toast.makeText(this@RoyaleActivity, "هبطت! ابدأ القتال 🪂", Toast.LENGTH_SHORT).show()
                } else {
                    handler.postDelayed(this, 40)
                }
                royaleView.invalidate()
            }
        }
        handler.postDelayed(step, 300)
    }

    // ===== الحلقة الرئيسية =====

    private fun startGameLoop() {
        val loop = object : Runnable {
            override fun run() {
                if (!royaleView.falling) {
                    updateMyMovement()
                    updateBots()
                    applyZoneDamage()
                }
                royaleView.invalidate()
                handler.postDelayed(this, 60)
            }
        }
        handler.postDelayed(loop, 60)
    }

    private fun updateMyMovement() {
        var moved = false
        if (movingUp) { royaleView.myY -= MOVE_SPEED; moved = true }
        if (movingDown) { royaleView.myY += MOVE_SPEED; moved = true }
        if (movingLeft) { royaleView.myX -= MOVE_SPEED; royaleView.myFacingRight = false; moved = true }
        if (movingRight) { royaleView.myX += MOVE_SPEED; royaleView.myFacingRight = true; moved = true }

        royaleView.myX = royaleView.myX.coerceIn(0f, RoyaleView.WORLD_SIZE)
        royaleView.myY = royaleView.myY.coerceIn(0f, RoyaleView.WORLD_SIZE)

        val now = System.currentTimeMillis()
        if (moved && now - lastPush > UPDATE_INTERVAL_MS) {
            lastPush = now
            pushMyState()
        }
    }

    private fun updateBots() {
        val now = System.currentTimeMillis()
        for (bot in royaleView.bots) bot.update(now)
    }

    private fun applyZoneDamage() {
        val dx = royaleView.myX - royaleView.zoneCenterX
        val dy = royaleView.myY - royaleView.zoneCenterY
        val dist = kotlin.math.hypot(dx, dy)
        if (dist > royaleView.zoneRadius) {
            royaleView.myHealth = (royaleView.myHealth - 1).coerceAtLeast(0)
        }
    }

    // ===== مزامنة اللاعبين الحقيقيين =====

    private fun pushMyState() {
        if (myId.isEmpty()) return
        val data = hashMapOf<String, Any>(
            "x" to royaleView.myX,
            "y" to royaleView.myY,
            "health" to royaleView.myHealth,
            "weapon" to royaleView.myWeapon.name,
            "facing" to royaleView.myFacingRight,
            "alive" to (royaleView.myHealth > 0),
            "lastUpdate" to System.currentTimeMillis()
        )
        db.collection("royale_players").document(myId).set(data, SetOptions.merge())
    }

    private fun listenRemotePlayers() {
        db.collection("royale_players").addSnapshotListener { snapshots, error ->
            if (error != null || snapshots == null) return@addSnapshotListener
            val now = System.currentTimeMillis()
            for (doc in snapshots.documents) {
                if (doc.id == myId) continue
                val lastUpdate = doc.getLong("lastUpdate") ?: 0
                val alive = doc.getBoolean("alive") ?: true
                if (!alive || now - lastUpdate > PLAYER_STALE_MS) {
                    remoteRaw.remove(doc.id)
                    continue
                }
                val x = doc.getDouble("x")?.toFloat() ?: continue
                val y = doc.getDouble("y")?.toFloat() ?: continue
                val health = (doc.getLong("health") ?: 100).toInt()
                val facing = doc.getBoolean("facing") ?: true
                val weaponName = doc.getString("weapon") ?: WeaponType.SWORD.name
                val weapon = try { WeaponType.valueOf(weaponName) } catch (e: Exception) { WeaponType.SWORD }
                remoteRaw[doc.id] = RemotePlayer(x, y, health, weapon, facing) to lastUpdate
            }
            royaleView.remotePlayers = remoteRaw.mapValues { it.value.first }
        }
    }

    // ===== الهجوم =====

    private fun performAttack() {
        val now = System.currentTimeMillis()
        if (now - lastAttackTime < ATTACK_COOLDOWN_MS) return
        lastAttackTime = now
        royaleView.myAttacking = true
        handler.postDelayed({ royaleView.myAttacking = false; royaleView.invalidate() }, 150)

        // ضرب بوت قريب
        var closestBot: Bot? = null
        var closestDist = Float.MAX_VALUE
        for (bot in royaleView.bots) {
            if (!bot.alive) continue
            val d = kotlin.math.hypot(bot.x - royaleView.myX, bot.y - royaleView.myY)
            if (d <= ATTACK_RANGE_BOT && d < closestDist) {
                closestDist = d
                closestBot = bot
            }
        }
        if (closestBot != null) {
            closestBot.takeDamage(royaleView.myWeapon.damage)
            if (!closestBot.alive) {
                royaleView.killCount++
                Toast.makeText(this, "قتلت بوت! 🎯", Toast.LENGTH_SHORT).show()
            }
        }

        // ضرب لاعب حقيقي قريب
        for ((uid, entry) in remoteRaw) {
            val rp = entry.first
            val d = kotlin.math.hypot(rp.x - royaleView.myX, rp.y - royaleView.myY)
            if (d <= royaleView.myWeapon.range) {
                val newHealth = (rp.health - royaleView.myWeapon.damage).coerceAtLeast(0)
                val updates = hashMapOf<String, Any>("health" to newHealth)
                if (newHealth <= 0) updates["alive"] = false
                db.collection("royale_players").document(uid).set(updates, SetOptions.merge())
                if (newHealth <= 0) {
                    royaleView.killCount++
                    Toast.makeText(this, "قضيت على $uid! 🏆", Toast.LENGTH_SHORT).show()
                }
                break
            }
        }
    }

    // ===== الميكروفون (تسجيل واحد حتى ١٥ ثانية، متل الدردشة) =====

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val file = File.createTempFile("royale_voice_", ".3gp", cacheDir)
            recordFile = file
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordStartTime = System.currentTimeMillis()
            Toast.makeText(this, "🎤 جاري التسجيل...", Toast.LENGTH_SHORT).show()
            val stop = Runnable { stopRecordingAndSend() }
            recordAutoStop = stop
            handler.postDelayed(stop, MAX_RECORD_MS)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر بدء التسجيل", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndSend() {
        val activeRecorder = recorder ?: return
        recordAutoStop?.let { handler.removeCallbacks(it) }
        recordAutoStop = null
        try {
            activeRecorder.stop()
        } catch (e: Exception) {
        }
        activeRecorder.release()
        recorder = null

        val file = recordFile ?: return
        recordFile = null
        try {
            val bytes = file.readBytes()
            file.delete()
            if (bytes.isEmpty()) return
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val data = hashMapOf(
                "sender" to myId,
                "audio" to CryptoUtils.encrypt(base64),
                "timestamp" to System.currentTimeMillis()
            )
            db.collection("royale_state").document("current").collection("voice").add(data)
        } catch (e: Exception) {
        }
    }

    private fun listenVoice() {
        db.collection("royale_state").document("current").collection("voice")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                for (change in snapshots.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    val sender = doc.getString("sender") ?: continue
                    if (sender == myId) continue
                    val audioEnc = doc.getString("audio") ?: continue
                    val decrypted = CryptoUtils.decrypt(audioEnc)
                    if (decrypted.isNotEmpty()) playVoice(decrypted)
                }
            }
    }

    private fun playVoice(base64Audio: String) {
        try {
            val bytes = Base64.decode(base64Audio, Base64.NO_WRAP)
            val tempFile = File.createTempFile("royale_play_", ".3gp", cacheDir)
            tempFile.writeBytes(bytes)
            val player = MediaPlayer()
            player.setDataSource(tempFile.absolutePath)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                it.release()
                tempFile.delete()
            }
            player.prepareAsync()
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        db.collection("royale_players").document(myId)
            .set(mapOf("alive" to false), SetOptions.merge())
    }
}
