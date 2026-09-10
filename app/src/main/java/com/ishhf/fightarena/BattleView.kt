package com.ishhf.fightarena

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** يرسم الحلبة والمقاتلين (شخصيات مرسومة بدون ملامح وجه) وأسلحتهم بيدهم */
class BattleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var myX = 150f
    var myHealth = 100
    var myWeapon: WeaponType = WeaponType.SWORD
    var myFacingRight = true
    var myAttacking = false

    var oppX = 800f
    var oppHealth = 100
    var oppWeapon: WeaponType = WeaponType.SWORD
    var oppFacingRight = false
    var oppAttacking = false
    var opponentConnected = false

    private val groundY = 700f
    private val bodyPaint = Paint().apply { style = Paint.Style.FILL }
    private val headPaint = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#FFE0B2") }
    private val weaponPaint = Paint().apply { style = Paint.Style.FILL }
    private val barBgPaint = Paint().apply { color = Color.parseColor("#33000000") }
    private val barFgPaint = Paint().apply { color = Color.parseColor("#4CAF50") }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true }
    private val groundPaint = Paint().apply { color = Color.parseColor("#3E2723") }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#87CEEB"))
        canvas.drawRect(0f, groundY, width.toFloat(), height.toFloat(), groundPaint)

        drawFighter(canvas, myX, "#2196F3", myFacingRight, myWeapon, myAttacking)
        if (opponentConnected) {
            drawFighter(canvas, oppX, "#F44336", oppFacingRight, oppWeapon, oppAttacking)
        }

        drawHealthBar(canvas, 40f, 60f, myHealth, "أنت")
        if (opponentConnected) {
            drawHealthBar(canvas, width - 340f, 60f, oppHealth, "الخصم")
        } else {
            canvas.drawText("بانتظار الخصم...", width / 2f - 150f, height / 2f, textPaint)
        }
    }

    private fun drawFighter(
        canvas: Canvas, x: Float, colorHex: String,
        facingRight: Boolean, weapon: WeaponType, attacking: Boolean
    ) {
        bodyPaint.color = Color.parseColor(colorHex)

        // الرأس - بدون أي ملامح وجه إطلاقاً
        canvas.drawCircle(x, groundY - 200f, 30f, headPaint)

        // الجسم
        canvas.drawRoundRect(RectF(x - 20f, groundY - 165f, x + 20f, groundY - 60f), 8f, 8f, bodyPaint)

        // الأرجل
        canvas.drawRoundRect(RectF(x - 18f, groundY - 60f, x - 4f, groundY), 6f, 6f, bodyPaint)
        canvas.drawRoundRect(RectF(x + 4f, groundY - 60f, x + 18f, groundY), 6f, 6f, bodyPaint)

        // الذراع الحاملة السلاح
        val armX = if (facingRight) x + 22f else x - 22f
        canvas.drawRoundRect(RectF(armX - 8f, groundY - 150f, armX + 8f, groundY - 100f), 6f, 6f, bodyPaint)

        // السلاح جوا اليد
        weaponPaint.color = Color.parseColor(weapon.color)
        val weaponLength = weapon.range / 3.2f
        val extraReach = if (attacking) 25f else 0f
        val finalStartX = if (facingRight) armX + extraReach else armX - weaponLength - extraReach
        canvas.drawRoundRect(
            RectF(finalStartX, groundY - 135f, finalStartX + weaponLength, groundY - 120f),
            4f, 4f, weaponPaint
        )
    }

    private fun drawHealthBar(canvas: Canvas, x: Float, y: Float, health: Int, label: String) {
        canvas.drawText(label, x, y - 15f, textPaint)
        canvas.drawRect(x, y, x + 300f, y + 24f, barBgPaint)
        val ratio = (health.coerceIn(0, 100) / 100f)
        canvas.drawRect(x, y, x + 300f * ratio, y + 24f, barFgPaint)
    }
}
