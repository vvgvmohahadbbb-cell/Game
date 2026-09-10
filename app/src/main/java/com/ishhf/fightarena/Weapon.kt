package com.ishhf.fightarena

enum class WeaponType(val label: String, val damage: Int, val range: Float, val color: String) {
    SWORD("سيف", 12, 90f, "#B0BEC5"),
    KNIFE("سكين", 8, 60f, "#CFD8DC"),
    PISTOL("مسدس", 16, 220f, "#37474F"),
    RIFLE("بندقية", 20, 320f, "#263238"),
    BOW("قوس", 14, 280f, "#6D4C41"),
    AXE("فأس", 18, 100f, "#8D6E63"),
    SPEAR("رمح", 15, 150f, "#A1887F")
}
