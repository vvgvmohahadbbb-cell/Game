package com.ishhf.fightarena

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

data class RemotePlayer(
    var x: Float, var y: Float, var health: Int, var weapon: WeaponType, var facingRight: Boolean
)

/** يرسم خريطة كبيرة بكاميرا متبعة اللاعب، مع دائرة تضيق وبوتات ولاعبين حقيقيين */
class RoyaleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        const val WORLD_SIZE = 4000f
    }

    var myX = WORLD_SIZE / 2
    var myY = WORLD_SIZE / 2
    var myHealth = 100
    var myWeapon: WeaponType = WeaponType.SWORD
    var myFacingRight = true
    var myAttacking = false

    var falling = true
    var fallProgressY = -400f

    var zoneCenterX = WORLD_SIZE / 2
    var zoneCenterY = WORLD_SIZE / 2
    var zoneRadius = WORLD_SIZE / 2

    var bots: List<Bot> = emptyList()
    var remotePlayers: Map<String, RemotePlayer> = emptyMap()
    var killCount = 0

    private val terrain = mutableListOf<Triple<Float, Float, Boolean>>() // x, y, isTree

    init {
        val rnd = Random(42) // بذرة ثابتة عشان الخريطة تضل نفسها كل مرة
        repeat(60) {
            terrain.add(Triple(rnd.nextFloat() * WORLD_SIZE, rnd.nextFloat() * WORLD_SIZE, rnd.nextBoolean()))
        }
    }

    private val groundPaint = Paint().apply { color = Color.parseColor("#558B2F") }
    private val treePaint = Paint().apply { color = Color.parseColor("#2E7D32") }
    private val rockPaint = Paint().apply { color = Color.parseColor("#757575") }
    private val zoneBorderPaint = Paint().apply {
        color = Color.parseColor("#00BCD4")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val bodyPaint = Paint().apply { style = Paint.Style.FILL }
    private val headPaint = Paint().apply { color = Color.parseColor("#FFE0B2") }
    private val botPaint = Paint().apply { color = Color.parseColor("#FBC02D") }
    private val weaponPaint = Paint().apply { style = Paint.Style.FILL }
    private val barBgPaint = Paint().apply { color = Color.parseColor("#33000000") }
    private val barFgPaint = Paint().apply { color = Color.parseColor("#4CAF50") }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true }
    private val chutePaint = Paint().apply { color = Color.parseColor("#E53935") }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#8D6E63"))

        val camX = myX - width / 2f
        val camY = myY - height / 2f

        canvas.drawRect(0f - camX, 0f - camY, WORLD_SIZE - camX, WORLD_SIZE - camY, groundPaint)
        canvas.drawCircle(zoneCenterX - camX, zoneCenterY - camY, zoneRadius, zoneBorderPaint)

        for ((tx, ty, isTree) in terrain) {
            val sx = tx - camX
            val sy = ty - camY
            if (sx < -60 || sx > width + 60 || sy < -60 || sy > height + 60) continue
            if (isTree) canvas.drawCircle(sx, sy, 26f, treePaint)
            else canvas.drawCircle(sx, sy, 20f, rockPaint)
        }

        for (bot in bots) {
            if (!bot.alive) continue
            val sx = bot.x - camX
            val sy = bot.y - camY
            if (sx < -60 || sx > width + 60 || sy < -60 || sy > height + 60) continue
            canvas.drawCircle(sx, sy - 20f, 16f, headPaint)
            canvas.drawRoundRect(RectF(sx - 12f, sy - 5f, sx + 12f, sy + 40f), 6f, 6f, botPaint)
        }

        for (rp in remotePlayers.values) {
            val sx = rp.x - camX
            val sy = rp.y - camY
            if (sx < -80 || sx > width + 80 || sy < -80 || sy > height + 80) continue
            drawFighter(canvas, sx, sy, "#F44336", rp.facingRight, rp.weapon, false)
        }

        if (falling) {
            drawParachute(canvas, width / 2f, fallProgressY)
        } else {
            drawFighter(canvas, width / 2f, height / 2f, "#2196F3", myFacingRight, myWeapon, myAttacking)
        }

        canvas.drawText("صحتك: $myHealth", 30f, 50f, textPaint)
        canvas.drawRect(30f, 60f, 230f, 84f, barBgPaint)
        canvas.drawRect(30f, 60f, 30f + 200f * (myHealth.coerceIn(0, 100) / 100f), 84f, barFgPaint)
        canvas.drawText("قتلى: $killCount", 30f, 120f, textPaint)
        canvas.drawText("لاعبين حقيقيين قريبين: ${remotePlayers.size}", 30f, 155f, textPaint)
    }

    private fun drawParachute(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawArc(RectF(cx - 40f, cy - 60f, cx + 40f, cy), 180f, 180f, true, chutePaint)
        canvas.drawLine(cx - 35f, cy - 5f, cx, cy + 30f, textPaint)
        canvas.drawLine(cx + 35f, cy - 5f, cx, cy + 30f, textPaint)
        drawFighter(canvas, cx, cy + 60f, "#2196F3", true, myWeapon, false)
    }

    private fun drawFighter(
        canvas: Canvas, x: Float, groundY: Float, colorHex: String,
        facingRight: Boolean, weapon: WeaponType, attacking: Boolean
    ) {
        bodyPaint.color = Color.parseColor(colorHex)
        canvas.drawCircle(x, groundY - 70f, 22f, headPaint)
        canvas.drawRoundRect(RectF(x - 15f, groundY - 48f, x + 15f, groundY + 10f), 6f, 6f, bodyPaint)
        canvas.drawRoundRect(RectF(x - 14f, groundY + 10f, x - 4f, groundY + 45f), 5f, 5f, bodyPaint)
        canvas.drawRoundRect(RectF(x + 4f, groundY + 10f, x + 14f, groundY + 45f), 5f, 5f, bodyPaint)

        val armX = if (facingRight) x + 17f else x - 17f
        canvas.drawRoundRect(RectF(armX - 6f, groundY - 40f, armX + 6f, groundY - 5f), 5f, 5f, bodyPaint)

        weaponPaint.color = Color.parseColor(weapon.color)
        val weaponLength = weapon.range / 4f
        val extraReach = if (attacking) 18f else 0f
        val finalStartX = if (facingRight) armX + extraReach else armX - weaponLength - extraReach
        canvas.drawRoundRect(
            RectF(finalStartX, groundY - 30f, finalStartX + weaponLength, groundY - 20f),
            3f, 3f, weaponPaint
        )
    }
}
