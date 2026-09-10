package com.ishhf.fightarena

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("fight_arena_prefs", MODE_PRIVATE)
        auth = FirebaseAuth.getInstance()

        val savedUsername = prefs.getString("my_username", null)
        if (savedUsername != null) {
            ensureAnonymousAuth { goToLobby() }
            return
        }

        setContentView(R.layout.activity_main)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (name.isEmpty() || password.length < 4) {
                Toast.makeText(this, "اكتب اسمك، وكلمة السر لازم 4 محارف عالأقل", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ensureAnonymousAuth { register(name, password) }
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (name.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "اكتب اسمك وكلمة السر", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ensureAnonymousAuth { login(name, password) }
        }
    }

    private fun ensureAnonymousAuth(callback: () -> Unit) {
        if (auth.currentUser != null) {
            callback()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { callback() }
            .addOnFailureListener {
                Toast.makeText(this, "تأكد من الإنترنت وحاول كمان مرة", Toast.LENGTH_LONG).show()
            }
    }

    private fun register(name: String, password: String) {
        db.collection("fighter_accounts").document(name).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    Toast.makeText(this, "الاسم مستخدم، اختار اسم تاني أو سجل دخول", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                val salt = randomSalt()
                val hash = hashPassword(password, salt)
                val accountData = hashMapOf("passwordHash" to hash, "salt" to salt)
                db.collection("fighter_accounts").document(name).set(accountData)
                    .addOnSuccessListener {
                        db.collection("fighters").document(name)
                            .set(mapOf("online" to false), SetOptions.merge())
                            .addOnSuccessListener {
                                prefs.edit().putString("my_username", name).apply()
                                goToLobby()
                            }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "صار خطأ، جرب كمان مرة", Toast.LENGTH_LONG).show()
                    }
            }
    }

    private fun login(name: String, password: String) {
        db.collection("fighter_accounts").document(name).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(this, "ما في حساب بهالاسم", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                val salt = doc.getString("salt") ?: ""
                val storedHash = doc.getString("passwordHash") ?: ""
                val hash = hashPassword(password, salt)
                if (hash == storedHash) {
                    prefs.edit().putString("my_username", name).apply()
                    goToLobby()
                } else {
                    Toast.makeText(this, "كلمة السر غلط", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((salt + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun goToLobby() {
        startActivity(Intent(this, LobbyActivity::class.java))
        finish()
    }
}
