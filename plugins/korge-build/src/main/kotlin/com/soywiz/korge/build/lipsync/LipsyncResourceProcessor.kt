package com.soywiz.korge.build.lipsync

import com.soywiz.korau.format.*
import com.soywiz.korau.sound.*
import com.soywiz.korge.build.*
import com.soywiz.korio.*
import com.soywiz.korio.dynamic.mapper.*
import com.soywiz.korio.dynamic.serialization.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.serialization.json.*
import com.soywiz.korio.util.*
import java.io.*
import java.net.*
import java.nio.file.*

open class LipsyncResourceProcessor : ResourceProcessor("voice.wav", "voice.mp3", "voice.ogg") {
    companion object : LipsyncResourceProcessor()

    override val version: Int = 0
    override val outputExtension: String = "lipsync"

    override suspend fun processInternal(inputFile: VfsFile, outputFile: VfsFile) {
        inputFile.copyTo(outputFile.parent[inputFile.baseName])
        outputFile.writeString(processAudioData(inputFile.readAudioData()).toLipString())
    }

    data class Config(val url: URL, val folder: String, val exe: String)

    val config by lazy {
        when {
            OS.isMac -> Config(
                URL("https://github.com/korlibs/korge-tools/releases/download/binaries-1.9/rhubarb-lip-sync-1.9.0-osx.zip"),
                "rhubarb-lip-sync-1.9.0-osx",
                "rhubarb"
            )
            OS.isLinux -> Config(
                URL("https://github.com/korlibs/korge-tools/releases/download/binaries-1.9/rhubarb-lip-sync-1.9.0-linux.zip"),
                "rhubarb-lip-sync-1.9.0-linux",
                "rhubarb"
            )
            OS.isWindows -> Config(
                URL("https://github.com/korlibs/korge-tools/releases/download/binaries-1.9/rhubarb-lip-sync-1.9.0-win32.zip"),
                "rhubarb-lip-sync-1.9.0-win32",
                "rhubarb.exe"
            )
            else -> error("Operating system '${OS.rawName}', '${OS.platformName}' not supported")
        }
    }


    data class Tool(val rhubarb: VfsFile)

    private val toolCache = AsyncOnce<Tool>()

    suspend fun getRhubarbTool(): Tool = toolCache {
        val toolsRoot = KorgeBuildTools.BINARY_ROOT()
        val rootOutputFolder = toolsRoot
        val outputFolder = toolsRoot[config.folder]
        val localZipFile = toolsRoot[config.url.basename]

        if (!outputFolder.exists()) {
            if (!localZipFile.exists()) {
                println("Downloading ${config.url} ...")
                localZipFile.writeBytes(config.url.openStream().use { it.readBytes() })
            }
            //val mem = MemoryVfs()
            val zip = localZipFile.openAsZip()
            //localZipFile.openAsZip().copyToTree(rootOutputFolder)
            zip.copyToTree(rootOutputFolder)

            //val executableFile = File(rootOutputFolder[config.exe].absolutePath)

            //println("Making executable $executableFile ...")
            //executableFile.setExecutable(true, false)
        }



        //val zip = LocalVfs("c:/temp/rhubarb-lip-sync-1.4.2-win32.zip").openAsZip()
        //println(zip["rhubarb-lip-sync-1.4.2-osx"].exists())
        //println(zip.list().toList())
        //zip.copyToTree(KorgeBuildTools.BINARY_ROOT())

        return@toolCache Tool(outputFolder[config.exe])
    }

    fun VfsFile.toJvmFile() = File(this.absolutePath)

    suspend fun processWav(wavFile: VfsFile): String {
        val rhubarb = getRhubarbTool().rhubarb

        rhubarb.toJvmFile().setExecutable(true, false)
        val result = rhubarb.parent.execToString(rhubarb.absolutePath, wavFile.absolutePath)
        return result
    }

    suspend fun processAudioData(data: AudioData): RhubarbFile {
        val rhubarb = getRhubarbTool().rhubarb
        val tempFile = tempVfs["rhubarb-file.wav"]
        try {
            tempFile.write(data.toWav())
            rhubarb.toJvmFile().setExecutable(true, false)
            val result = tempVfs.execToString(listOf(rhubarb.absolutePath, "-f", "json", tempFile.absolutePath))
            Mapper.jvmFallback()
            return Json.parseTyped<RhubarbFile>(result, Mapper)
        } finally {
            tempFile.delete()
        }
    }

    data class Metadata(val soundFile: String, val duration: Double)
    data class MouthCue(val start: Double, var end: Double, var value: String)
    //data class MouthCue(val start: Double, var end: Double, var value: Char)

    data class RhubarbFile(val metadata: Metadata, val mouthCues: List<MouthCue>) {
        val totalTime: Double by lazy { mouthCues.map { it.end }.max() ?: 0.0 }
        fun findCue(time: Double): MouthCue? =
            mouthCues.getOrNull(mouthCues.binarySearch { if (time < it.start) +1 else if (time >= it.end) -1 else 0 })

        fun toLipString(): String {
            var out = ""
            val totalMs = (totalTime * 1000).toInt()
            for (ms in 0 until totalMs step 16) {
                val s = findCue(ms.toDouble() / 1000)
                out += s?.value ?: "X"
            }
            return out
        }
    }
}
