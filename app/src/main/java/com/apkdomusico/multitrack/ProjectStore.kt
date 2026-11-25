package com.apkdomusico.multitrack

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Guarda cada projeto numa pasta: project.json + audio/. */
class ProjectStore(context: Context) {
    val root: File = File(context.filesDir, "projetos").also { it.mkdirs() }

    fun folder(projectId: String) = File(root, projectId)
    fun audioFolder(projectId: String) = File(folder(projectId), "audio").also { it.mkdirs() }
    fun audioFile(projectId: String, name: String) = File(audioFolder(projectId), name)

    fun list(): List<Project> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it.name) }
            ?.sortedByDescending { File(folder(it.id), "project.json").lastModified() }
            ?: emptyList()

    fun create(name: String, bpm: Int = 100): Project {
        val p = Edits.addTrack(Edits.addTrack(Project(name = name, bpm = bpm)))
        save(p)
        return p
    }

    fun load(id: String): Project? = try {
        val f = File(folder(id), "project.json")
        if (f.exists()) fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) {
        null
    }

    fun save(p: Project) {
        val dir = folder(p.id).also { it.mkdirs() }
        audioFolder(p.id)
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(toJson(p).toString(2))
        tmp.renameTo(File(dir, "project.json"))
    }

    fun delete(id: String) {
        folder(id).deleteRecursively()
    }

    companion object {
        fun toJson(p: Project): JSONObject = JSONObject().apply {
            put("versao", 1)
            put("id", p.id)
            put("nome", p.name)
            put("bpm", p.bpm)
            put("tempos", p.beatsPerBar)
            put("pistas", JSONArray().apply {
                p.tracks.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("nome", t.name)
                        put("volume", t.volume.toDouble())
                        put("pan", t.pan.toDouble())
                        put("mudo", t.mute)
                        put("solo", t.solo)
                        put("clipes", JSONArray().apply {
                            t.clips.forEach { c ->
                                put(JSONObject().apply {
                                    put("id", c.id)
                                    put("arquivo", c.file)
                                    put("inicio", c.start)
                                    put("deslocamento", c.offset)
                                    put("duracao", c.length)
                                    put("fadeEntrada", c.fadeIn)
                                    put("fadeSaida", c.fadeOut)
                                    put("ganho", c.gain.toDouble())
                                })
                            }
                        })
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): Project {
            val tracks = mutableListOf<Track>()
            val arr = o.optJSONArray("pistas") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val clips = mutableListOf<Clip>()
                val ca = t.optJSONArray("clipes") ?: JSONArray()
                for (j in 0 until ca.length()) {
                    val c = ca.getJSONObject(j)
                    clips += Clip(
                        id = c.optString("id", newId()),
                        file = c.getString("arquivo"),
                        start = c.getLong("inicio"),
                        offset = c.optLong("deslocamento", 0),
                        length = c.getLong("duracao"),
                        fadeIn = c.optLong("fadeEntrada", 0),
                        fadeOut = c.optLong("fadeSaida", 0),
                        gain = c.optDouble("ganho", 1.0).toFloat(),
                    )
                }
                tracks += Track(
                    id = t.optString("id", newId()),
                    name = t.optString("nome", "Pista ${i + 1}"),
                    volume = t.optDouble("volume", 1.0).toFloat(),
                    pan = t.optDouble("pan", 0.0).toFloat(),
                    mute = t.optBoolean("mudo", false),
                    solo = t.optBoolean("solo", false),
                    clips = clips,
                )
            }
            return Project(
                id = o.getString("id"),
                name = o.optString("nome", "Projeto"),
                bpm = o.optInt("bpm", 100),
                beatsPerBar = o.optInt("tempos", 4),
                tracks = tracks,
            )
        }
    }
}
