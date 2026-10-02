package com.riffbuddy.desktop

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthUnit
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Makes the installers' icons from the app's master SVG: a PNG for Linux, an
 * ICO for Windows and an ICNS for macOS. Not a test: run it when the icon
 * changes, and commit what it writes.
 *
 *   IconTool <master.svg> <out-dir> [<foreground.svg> <splash-dir> [<window-icon-dir>]]
 *
 * The master is a full square, as Android's adaptive icons are. Desktops
 * expect a rounded square with a little margin, the shape macOS draws its
 * own icons in, so the art is clipped to that: 824 of 1024 units, corners of
 * 185, the proportions of Apple's icon grid.
 */
fun main(args: Array<String>) {
    val svg = File(args[0]).readBytes()
    val out = File(args[1]).apply { mkdirs() }
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512, 1024)
    val png = sizes.associateWith { render(svg, it) }

    File(out, "riffbuddy.png").writeBytes(png.getValue(512))
    File(out, "riffbuddy.ico").writeBytes(ico(listOf(16, 24, 32, 48, 64, 128, 256).map { it to png.getValue(it) }))
    File(out, "riffbuddy.icns").writeBytes(
        icns(listOf("icp4" to 16, "icp5" to 32, "icp6" to 64, "ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024)
            .map { (type, size) -> type to png.getValue(size) }),
    )
    println("icons written to $out")
    if (args.size >= 4) splash(File(args[2]).readBytes(), File(args[3]))
    // The running window's own icons, one per size: the system picks the one
    // it needs for the title bar, the taskbar and the window switcher.
    if (args.size >= 5) {
        val window = File(args[4]).apply { mkdirs() }
        for (size in WINDOW_SIZES) File(window, "riffbuddy-$size.png").writeBytes(png.getValue(size))
        println("window icons written to $window")
    }
}

/**
 * The launch splash: the guitar on the app's charcoal, as Android's system
 * splash shows it. The window opens centered over it, and the opening
 * animation's first frame is this same picture at the same size, so the
 * handover doesn't jump. A @2x copy is used on high-density screens.
 */
private fun splash(foreground: ByteArray, out: File) {
    out.mkdirs()
    for ((name, scale) in listOf("splash.png" to 1, "splash@2x.png" to 2)) {
        val side = SPLASH_SIDE * scale
        val icon = SPLASH_ICON * scale.toFloat()
        val surface = Surface.makeRasterN32Premul(side, side)
        val canvas = surface.canvas
        canvas.clear(0xFF241F1B.toInt())
        canvas.save()
        canvas.translate((side - icon) / 2f, (side - icon) / 2f)
        val dom = SVGDOM(Data.makeFromBytes(foreground))
        dom.root!!.width = SVGLength(icon, SVGLengthUnit.PX)
        dom.root!!.height = SVGLength(icon, SVGLengthUnit.PX)
        dom.setContainerSize(icon, icon)
        dom.render(canvas)
        canvas.restore()
        File(out, name).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    println("splash written to $out")
}

/** The sizes the running window offers the system. */
val WINDOW_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)

/** Splash window and icon sizes, in dp; the icon matches the opening animation's. */
private const val SPLASH_SIDE = 420
private const val SPLASH_ICON = 220

private fun render(svg: ByteArray, size: Int): ByteArray {
    val surface = Surface.makeRasterN32Premul(size, size)
    val canvas = surface.canvas
    val scale = size / 1024f
    val inset = 100f * scale
    val side = 824f * scale
    canvas.save()
    canvas.clipRRect(RRect.makeXYWH(inset, inset, side, side, 185f * scale), true)
    canvas.translate(inset, inset)
    val dom = SVGDOM(Data.makeFromBytes(svg))
    dom.root!!.width = SVGLength(side, SVGLengthUnit.PX)
    dom.root!!.height = SVGLength(side, SVGLengthUnit.PX)
    dom.setContainerSize(side, side)
    dom.render(canvas)
    canvas.restore()
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}

/** Windows icon: a directory of PNG images, one per size. */
private fun ico(images: List<Pair<Int, ByteArray>>): ByteArray {
    val bytes = ByteArrayOutputStream()
    val le = LittleEndian(bytes)
    le.short(0); le.short(1); le.short(images.size)
    var offset = 6 + 16 * images.size
    for ((size, data) in images) {
        bytes.write(if (size >= 256) 0 else size) // 0 means 256
        bytes.write(if (size >= 256) 0 else size)
        bytes.write(0); bytes.write(0)
        le.short(1); le.short(32)
        le.int(data.size); le.int(offset)
        offset += data.size
    }
    images.forEach { bytes.write(it.second) }
    return bytes.toByteArray()
}

/** macOS icon: typed PNG entries in a big-endian container. */
private fun icns(images: List<Pair<String, ByteArray>>): ByteArray {
    val body = ByteArrayOutputStream()
    DataOutputStream(body).use { out ->
        for ((type, data) in images) {
            out.writeBytes(type)
            out.writeInt(8 + data.size)
            out.write(data)
        }
    }
    val all = ByteArrayOutputStream()
    DataOutputStream(all).use { out ->
        out.writeBytes("icns")
        out.writeInt(8 + body.size())
        out.write(body.toByteArray())
    }
    return all.toByteArray()
}

private class LittleEndian(private val out: ByteArrayOutputStream) {
    fun short(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
    fun int(v: Int) { short(v and 0xffff); short((v shr 16) and 0xffff) }
}
