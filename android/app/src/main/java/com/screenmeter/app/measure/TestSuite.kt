package com.screenmeter.app.measure

/** One full-screen colour the companion page must show. rgb are 8-bit sRGB code values. */
data class Patch(
    val id: String,
    val group: String,
    val r: Int, val g: Int, val b: Int,
    /** 0 = full screen; 1..9 = uniformity zone (row-major, top-left first). */
    val zone: Int = 0,
    /** Number of captures to average. */
    val repeats: Int = 1,
    val label: String = id,
)

object TestSuite {
    val greyLevels = listOf(0, 2, 4, 8, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 250, 253, 255)

    fun full(includeUniformity: Boolean): List<Patch> {
        val list = mutableListOf<Patch>()
        list += Patch("white", "reference", 255, 255, 255, repeats = 3, label = "White reference")
        list += Patch("black", "reference", 0, 0, 0, repeats = 3, label = "Black level")
        greyLevels.filter { it != 0 && it != 255 }.forEach { l ->
            list += Patch("grey_$l", "grey", l, l, l, label = "Grey $l")
        }
        listOf(
            Triple("red", 255 to 0, 0), Triple("green", 0 to 255, 0), Triple("blue", 0 to 0, 255),
            Triple("cyan", 0 to 255, 255), Triple("magenta", 255 to 0, 255), Triple("yellow", 255 to 255, 0),
        ).forEach { (name, rg, b) ->
            list += Patch(name, "primary", rg.first, rg.second, b, label = name.replaceFirstChar { it.uppercase() })
            list += Patch("${name}_50", "primary50", rg.first / 2 + (if (rg.first > 0) 1 else 0), rg.second / 2 + (if (rg.second > 0) 1 else 0), b / 2 + (if (b > 0) 1 else 0), label = "$name 50%")
        }
        if (includeUniformity) {
            for (z in 1..9) list += Patch("uniform_$z", "uniformity", 255, 255, 255, zone = z, label = "Uniformity zone $z")
        }
        list += Patch("flicker", "flicker", 255, 255, 255, repeats = 8, label = "Flicker (PWM)")
        return list
    }

    fun quick(): List<Patch> = full(false).filter {
        it.group == "reference" || (it.group == "grey" && it.r in listOf(16, 64, 128, 192, 240)) || it.group == "primary" || it.group == "flicker"
    }
}
