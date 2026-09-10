package com.ishhf.fightarena

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class LobbyActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var prefs: SharedPreferences
    private lateinit var myId: String
    private lateinit var weaponSpinner: Spinner
    private val players = mutableListOf<String>()
    private lateinit var adapter: PlayersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby)
        prefs = getSharedPreferences("fight_arena_prefs", MODE_PRIVATE)
        myId = prefs.getString("my_username", "") ?: ""

        weaponSpinner = findViewById(R.id.weaponSpinner)
        weaponSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            WeaponType.values().map { it.label }
        )

        val recyclerView = findViewById<RecyclerView>(R.id.playersRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PlayersAdapter(players) { opponent ->
            val weapon = WeaponType.values()[weaponSpinner.selectedItemPosition]
            val intent = Intent(this, BattleActivity::class.java)
            intent.putExtra(BattleActivity.EXTRA_OPPONENT, opponent)
            intent.putExtra(BattleActivity.EXTRA_WEAPON, weapon.name)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.btnRoyale).setOnClickListener {
            val weapon = WeaponType.values()[weaponSpinner.selectedItemPosition]
            val intent = Intent(this, RoyaleActivity::class.java)
            intent.putExtra(BattleActivity.EXTRA_WEAPON, weapon.name)
            startActivity(intent)
        }

        db.collection("fighters").whereEqualTo("online", true)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                players.clear()
                for (doc in snapshots.documents) {
                    if (doc.id != myId) players.add(doc.id)
                }
                adapter.notifyDataSetChanged()
            }
    }

    override fun onResume() {
        super.onResume()
        setOnline(true)
    }

    override fun onPause() {
        super.onPause()
        setOnline(false)
    }

    private fun setOnline(online: Boolean) {
        if (myId.isEmpty()) return
        db.collection("fighters").document(myId)
            .set(mapOf("online" to online), SetOptions.merge())
    }
}

class PlayersAdapter(
    private val items: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<PlayersAdapter.PlayerViewHolder>() {

    class PlayerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.memberName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.name.text = "⚔️ " + items[position]
        holder.itemView.setOnClickListener { onClick(items[position]) }
    }

    override fun getItemCount(): Int = items.size
}
