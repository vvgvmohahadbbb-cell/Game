package com.ishhf.fightarena

import kotlin.random.Random

/** بوت محلي (مش متزامن عبر الشبكة) - كل لاعب عنده قطيع بوتات خاص فيه */
class Bot(var x: Float, var y: Float, val worldSize: Float) {
    var health = 30
    var alive = true
    private var targetX = x
    private var targetY = y
    private var lastTargetChange = 0L

    fun update(now: Long) {
        if (!alive) return
        if (now - lastTargetChange > 3000L) {
            targetX = Random.nextFloat() * worldSize
            targetY = Random.nextFloat() * worldSize
            lastTargetChange = now
        }
        val dx = targetX - x
        val dy = targetY - y
        val dist = kotlin.math.hypot(dx, dy)
        if (dist > 5f) {
            x += (dx / dist) * 2.5f
            y += (dy / dist) * 2.5f
        }
    }

    fun takeDamage(amount: Int) {
        health -= amount
        if (health <= 0) alive = false
    }

    companion object {
        fun spawnHerd(count: Int, worldSize: Float): MutableList<Bot> {
            val list = mutableListOf<Bot>()
            repeat(count) {
                list.add(Bot(Random.nextFloat() * worldSize, Random.nextFloat() * worldSize, worldSize))
            }
            return list
        }
    }
}
