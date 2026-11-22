package com.armsx.flashpoint.ui

import androidx.compose.ui.graphics.Color
import java.io.File

/**
 * Desktop Flashpoint's themes (Data/Themes/<name>/theme.css) read for their colors: the CSS
 * custom properties the launcher's layout uses, following @import, var() and calc(), so the
 * Colorized family (a hue, saturation, lightness and accent fed into Colorized/root.css) and the
 * themes that set their own colors both come out as the launcher draws them.
 */
object ThemeCss {
    data class Parsed(val name: String, val vars: Map<String, String>)

    fun read(themeDir: File): Parsed? {
        val css = themeDir.resolve("theme.css").takeIf { it.isFile } ?: return null
        val vars = LinkedHashMap<String, String>()
        load(css, vars, 0)
        val name = Regex("""@name\s+(.+)""").find(css.readText())?.groupValues?.get(1)?.trim() ?: themeDir.name
        return Parsed(name, vars)
    }

    private fun load(f: File, vars: MutableMap<String, String>, depth: Int) {
        if (depth > 4 || !f.isFile) return
        val text = f.readText().replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        Regex("""@import\s+url\(\s*["']?([^"')]+)["']?\s*\)""").findAll(text).forEach { m ->
            load(File(f.parentFile, m.groupValues[1]), vars, depth + 1)
        }
        // Custom properties from every block, in order (most themes keep them in :root; some
        // set them on body or html).
        Regex("""\{([^{}]*)\}""").findAll(text).forEach { block ->
            Regex("""(--[\w-]+)\s*:\s*([^;]+);""").findAll(block.groupValues[1]).forEach { v ->
                vars[v.groupValues[1]] = v.groupValues[2].trim()
            }
        }
    }

    /** The color a variable resolves to, or null. */
    fun color(p: Parsed, name: String): Color? = runCatching { parseColor(resolve(p, p.vars[name] ?: return null, 0)) }.getOrNull()

    private fun resolve(p: Parsed, value: String, depth: Int): String {
        if (depth > 12) return value
        val r = Regex("""var\(\s*(--[\w-]+)\s*(?:,\s*([^)]*))?\)""")
        if (!r.containsMatchIn(value)) return value
        return resolve(p, r.replace(value) { m -> p.vars[m.groupValues[1]] ?: m.groupValues[2] }, depth + 1)
    }

    private val NAMED = mapOf(
        "white" to 0xFFFFFF, "black" to 0x000000, "maroon" to 0x800000, "lightgrey" to 0xD3D3D3, "lightgray" to 0xD3D3D3,
        "grey" to 0x808080, "gray" to 0x808080, "silver" to 0xC0C0C0, "red" to 0xFF0000, "green" to 0x008000,
        "blue" to 0x0000FF, "navy" to 0x000080, "darkgrey" to 0xA9A9A9, "darkgray" to 0xA9A9A9, "dimgray" to 0x696969,
        "whitesmoke" to 0xF5F5F5, "gainsboro" to 0xDCDCDC, "orange" to 0xFFA500, "gold" to 0xFFD700, "teal" to 0x008080,
        "purple" to 0x800080, "pink" to 0xFFC0CB, "yellow" to 0xFFFF00,
    )

    private fun parseColor(raw: String): Color? {
        val v = raw.trim().lowercase()
        if (v.startsWith("#")) {
            val h = v.drop(1)
            val full = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 6, 8 -> h; else -> return null }
            val rgb = full.take(6).toLong(16)
            val a = if (full.length == 8) full.substring(6).toLong(16) else 0xFF
            return Color(((a shl 24) or rgb).toInt())
        }
        NAMED[v]?.let { return Color(0xFF000000.toInt() or it) }
        val fn = Regex("""^(rgba?|hsla?)\((.*)\)$""").find(v) ?: return null
        val args = splitArgs(fn.groupValues[2]).map { eval(it) }
        return when (fn.groupValues[1]) {
            "rgb", "rgba" -> Color(args[0].toFloat() / 255f, args[1].toFloat() / 255f, args[2].toFloat() / 255f, if (args.size > 3) args[3].toFloat().let { if (it > 1) it / 100f else it } else 1f)
            else -> hsl(args[0], args[1] / 100.0, args[2] / 100.0, if (args.size > 3) args[3] else 1.0)
        }
    }

    /** Arguments split on top-level commas (or spaces, for the modern syntax). */
    private fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>(); var depth = 0; val cur = StringBuilder()
        val sep = if (',' in s) ',' else ' '
        for (c in s) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                c == sep && depth == 0 -> { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
                c == '/' && depth == 0 && sep == ' ' -> { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /** A number, percentage or calc() expression; percentages count as their number. */
    private fun eval(expr: String): Double {
        val tokens = Regex("""\d*\.?\d+|[-+*/()]""").findAll(expr.replace("calc", "").replace("%", "").replace("deg", "")).map { it.value }.toList()
        var i = 0
        fun parseSum(): Double {
            fun parseFactor(): Double {
                val t = tokens.getOrNull(i++) ?: return 0.0
                return when (t) {
                    "(" -> parseSum().also { i++ }
                    "-" -> -parseFactor()
                    else -> t.toDouble()
                }
            }
            fun parseProduct(): Double {
                var v = parseFactor()
                while (i < tokens.size && (tokens[i] == "*" || tokens[i] == "/")) {
                    val op = tokens[i++]; val r = parseFactor()
                    v = if (op == "*") v * r else v / r
                }
                return v
            }
            var v = parseProduct()
            while (i < tokens.size && (tokens[i] == "+" || tokens[i] == "-")) {
                val op = tokens[i++]; val r = parseProduct()
                v = if (op == "+") v + r else v - r
            }
            return v
        }
        return parseSum()
    }

    private fun hsl(hDeg: Double, s: Double, l: Double, a: Double): Color {
        val h = ((hDeg % 360) + 360) % 360 / 360.0
        val sc = s.coerceIn(0.0, 1.0); val lc = l.coerceIn(0.0, 1.0)
        fun hue(p: Double, q: Double, tt: Double): Double {
            var t = tt; if (t < 0) t += 1.0; if (t > 1) t -= 1.0
            return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
        }
        if (sc == 0.0) return Color(lc.toFloat(), lc.toFloat(), lc.toFloat(), a.toFloat().coerceIn(0f, 1f))
        val q = if (lc < 0.5) lc * (1 + sc) else lc + sc - lc * sc
        val p = 2 * lc - q
        return Color(hue(p, q, h + 1.0 / 3).toFloat(), hue(p, q, h).toFloat(), hue(p, q, h - 1.0 / 3).toFloat(), a.toFloat().coerceIn(0f, 1f))
    }
}
