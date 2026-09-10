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

/** شاشة القتال: مزامنة الحركة والصحة بين جهازي اللاعبين عبر Firestore */
class BattleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPPONENT = "opponent"
        const val EXTRA_WEAPON = "weapon"
        private const val MOVE_SPEED = 12f
        private const val ATTACK_COOLDOWN_MS = 500L
        private const val UPDATE_INTERVAL_MS = 150L
        private const val MAX_RECORD_MS = 15000L
    }

    private val db = FirebaseFirestore.getInstance()
    private lateinit var prefs: SharedPreferences
    private lateinit var battleView: BattleView
    private lateinit var matchId: String
    private lateinit var myId: String
    private lateinit var opponentId: String
    private var amP1 = true

    private var lastAttackTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var movingLeft = false
    private var movingRight = false

    private var recorder: MediaRecorder? = null
    private var micHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battle)
        prefs = getSharedPreferences("fight_arena_prefs", MODE_PRIVATE)
        myId = prefs.getString("my_username", "") ?: ""
        opponentId = intent.getStringExtra(EXTRA_OPPONENT) ?: ""
        val weaponName = intent.getStringExtra(EXTRA_WEAPON) ?: WeaponType.SWORD.name
        val myWeapon = WeaponType.valueOf(weaponName)

        amP1 = myId < opponentId
        matchId = if (amP1) "${myId}_${opponentId}" else "${opponentId}_${myId}"

        battleView = findViewById(R.id.battleView)
        battleView.myWeapon = myWeapon
        battleView.myX = if (amP1) 150f else 800f
        battleView.myFacingRight = amP1

        setupControls()
        requestAudioPermission()
        initMatch(myWeapon)
        listenToMatch()
        startMovementLoop()
    }

    private fun setupControls() {
        val btnLeft = findViewById<Button>(R.id.btnLeft)
        val btnRight = findViewById<Button>(R.id.btnRight)
        val btnAttack = findViewById<Button>(R.id.btnAttack)
        val btnMic = findViewById<Button>(R.id.btnMic)

        btnLeft.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> movingLeft = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> movingLeft = false
            }
            true
        }
        btnRight.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> movingRight = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> movingRight = false
            }
            true
        }
        btnAttack.setOnClickListener { performAttack() }

        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startVoiceLoop()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopVoiceLoop()
            }
            true
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 30)
        }
    }

    private fun initMatch(myWeapon: WeaponType) {
        val prefix = if (amP1) "p1" else "p2"
        val data = hashMapOf<String, Any>(
            "${prefix}Name" to myId,
            "${prefix}X" to battleView.myX,
            "${prefix}Health" to 100,
            "${prefix}Weapon" to myWeapon.name,
            "${prefix}Facing" to amP1,
            "${prefix}Attacking" to false,
            "status" to "active"
        )
        db.collection("battles").document(matchId).set(data, SetOptions.merge())
    }

    private fun listenToMatch() {
        db.collection("battles").document(matchId).addSnapshotListener { doc, error ->
            if (error != null || doc == null || !doc.exists()) return@addSnapshotListener

            val oppPrefix = if (amP1) "p2" else "p1"
            val oppX = doc.getDouble("${oppPrefix}X")
            if (oppX != null) {
                battleView.opponentConnected = true
                battleView.oppX = oppX.toFloat()
                battleView.oppHealth = (doc.getLong("${oppPrefix}Health") ?: 100).toInt()
                battleView.oppFacingRight = doc.getBoolean("${oppPrefix}Facing") ?: false
                battleView.oppAttacking = doc.getBoolean("${oppPrefix}Attacking") ?: false
                val oppWeaponName = doc.getString("${oppPrefix}Weapon")
                if (oppWeaponName != null) battleView.oppWeapon = WeaponType.valueOf(oppWeaponName)
            }

            val myPrefix = if (amP1) "p1" else "p2"
            battleView.myHealth = (doc.getLong("${myPrefix}Health") ?: 100).toInt()

            battleView.invalidate()

            val status = doc.getString("status")
            if (status == "finished") {
                val winner = doc.getString("winner") ?: ""
                val msg = if (winner == myId) "فزت! 🏆" else "خسرت 😔"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        db.collection("battles").document(matchId).collection("voice")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                for (change in snapshots.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    val sender = doc.getString("sender") ?: continue
                    if (sender == myId) continue
                    val audioEnc = doc.getString("audio") ?: continue
                    val decrypted = CryptoUtils.decrypt(audioEnc)
                    if (decrypted.isNotEmpty()) playVoiceChunk(decrypted)
                }
            }
    }

    private fun startMovementLoop() {
        val loop = object : Runnable {
            override fun run() {
                var moved = false
                if (movingLeft) {
                    battleView.myX -= MOVE_SPEED
                    battleView.myFacingRight = false
                    moved = true
                }
                if (movingRight) {
                    battleView.myX += MOVE_SPEED
                    battleView.myFacingRight = true
                    moved = true
                }
                val maxX = (battleView.width - 50).toFloat()
                if (maxX > 50f) {
                    battleView.myX = battleView.myX.coerceIn(50f, maxX)
                }
                if (moved) {
                    battleView.invalidate()
                    pushMyState()
                }
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.postDelayed(loop, UPDATE_INTERVAL_MS)
    }

    private fun pushMyState() {
        val prefix = if (amP1) "p1" else "p2"
        val data = mapOf(
            "${prefix}X" to battleView.myX,
            "${prefix}Facing" to battleView.myFacingRight
        )
        db.collection("battles").document(matchId).set(data, SetOptions.merge())
    }

    private fun performAttack() {
        val now = System.currentTimeMillis()
        if (now - lastAttackTime < ATTACK_COOLDOWN_MS) return
        lastAttackTime = now

        battleView.myAttacking = true
        battleView.invalidate()

        val myPrefix = if (amP1) "p1" else "p2"
        db.collection("battles").document(matchId).set(
            mapOf("${myPrefix}Attacking" to true), SetOptions.merge()
        )
        handler.postDelayed({
            battleView.myAttacking = false
            battleView.invalidate()
            db.collection("battles").document(matchId).set(
                mapOf("${myPrefix}Attacking" to false), SetOptions.merge()
            )
        }, 200)

        if (!battleView.opponentConnected) return
        val distance = Math.abs(battleView.myX - battleView.oppX)
        if (distance <= battleView.myWeapon.range) {
            val newHealth = (battleView.oppHealth - battleView.myWeapon.damage).coerceAtLeast(0)
            val oppPrefix = if (amP1) "p2" else "p1"
            val updates = hashMapOf<String, Any>("${oppPrefix}Health" to newHealth)
            if (newHealth <= 0) {
                updates["status"] = "finished"
                updates["winner"] = myId
            }
            db.collection("battles").document(matchId).set(updates, SetOptions.merge())
        }
    }

    private fun startVoiceLoop() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        micHeld = true
        recordOneChunk()
    }

    private fun stopVoiceLoop() {
        if (!micHeld) return
        finishRecordingAndSend()
    }

    private var recordAutoStop: Runnable? = null
    private var currentRecordFile: File? = null

    private fun recordOneChunk() {
        if (!micHeld) return
        try {
            val file = File.createTempFile("chunk_", ".3gp", cacheDir)
            currentRecordFile = file
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            val stop = Runnable { finishRecordingAndSend() }
            recordAutoStop = stop
            handler.postDelayed(stop, MAX_RECORD_MS)
        } catch (e: Exception) {
            micHeld = false
        }
    }

    private fun finishRecordingAndSend() {
        recordAutoStop?.let { handler.removeCallbacks(it) }
        recordAutoStop = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        recorder?.release()
        recorder = null
        micHeld = false
        val file = currentRecordFile ?: return
        currentRecordFile = null
        sendVoiceChunk(file)
    }

    private fun sendVoiceChunk(file: File) {
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
            db.collection("battles").document(matchId).collection("voice").add(data)
        } catch (e: Exception) {
        }
    }

    private fun playVoiceChunk(base64Audio: String) {
        try {
            val bytes = Base64.decode(base64Audio, Base64.NO_WRAP)
            val tempFile = File.createTempFile("play_", ".3gp", cacheDir)
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
        stopVoiceLoop()
    }
}
