package me.seasonyuu.displaylauncher

/** Pure parsers deliberately fail closed on unfamiliar OEM output. */
object ShellProtocol {
    fun physicalId(dump: String, logicalId: Int, surfaceDump: String): String? {
        val ids = dump.lineSequence().filter {
            it.contains("mBaseDisplayInfo=DisplayInfo{") &&
                Regex("displayId $logicalId(?:,|\\s)").containsMatchIn(it)
        }.mapNotNull { Regex("uniqueId \\\"local:(\\d+)\\\"").find(it)?.groupValues?.get(1) }.toSet()
        val id = ids.singleOrNull() ?: return null
        return id.takeIf { Regex("(?m)^Display $id \\(").containsMatchIn(surfaceDump) }
    }

    fun displayIdentity(dump: String, logicalId: Int): String? = dump.lineSequence().filter {
        it.contains("mBaseDisplayInfo=DisplayInfo{") && Regex("displayId $logicalId(?:,|\\s)").containsMatchIn(it)
    }.map { Regex("uniqueId \"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet().singleOrNull()

    /** Virtual output IDs are unsigned 64-bit values: keep them as decimal strings. */
    fun virtualId(dump: String, logicalId: Int, surfaceDump: String, surfaceIds: String): String? {
        data class Logical(val id: Int, val stack: String, val unique: String, val name: String)
        val logical = dump.lineSequence().filter { it.contains("mBaseDisplayInfo=DisplayInfo{") }.mapNotNull { line ->
            val id = Regex("displayId (\\d+)(?:,|\\s)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val stack = Regex("(?:, )layerStack (\\d+)(?:,|\\s)").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val unique = Regex("uniqueId \"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = Regex("DisplayInfo\\{\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            Logical(id, stack, unique, name)
        }.toSet()
        val target = logical.filter { it.id == logicalId }.singleOrNull() ?: return null
        if (displayIdentity(dump, logicalId) != target.unique || !target.unique.startsWith("virtual:") || logical.any { it.id != logicalId && it.stack == target.stack }) return null
        data class Output(val id: String, val name: String, val body: String)
        val lines = surfaceDump.lines()
        val header = Regex("^Display (\\d+) \\(virtual, \"([^\"]*)\"\\)$")
        val outputs = lines.mapIndexedNotNull { index, line ->
            val match = header.matchEntire(line) ?: return@mapIndexedNotNull null
            val body = lines.drop(index + 1).takeWhile { it.isBlank() || it.first().isWhitespace() }.joinToString("\n")
            val stacks = Regex("layerFilter=\\{layerStack=(\\d+)(?: |\\})").findAll(body).map { it.groupValues[1] }.toList()
            if (stacks.singleOrNull() != target.stack) return@mapIndexedNotNull null
            Output(match.groupValues[1], match.groupValues[2], body)
        }
        // Never choose by name, resolution or enumeration order; duplicated layer stacks fail closed.
        val output = outputs.singleOrNull() ?: return null
        if (output.name != target.name || output.id.toULongOrNull() == null) return null
        if (!Regex("\\bisEnabled=true\\b").containsMatchIn(output.body) ||
            !Regex("layerFilter=\\{layerStack=${target.stack} toInternalDisplay=false skipScreenshot=false ?\\}").containsMatchIn(output.body)) return null
        val listed = Regex("(?m)^Display ${output.id} \\(Virtual display\\): displayName=\"${Regex.escape(output.name)}\"$")
        return output.id.takeIf { listed.findAll(surfaceIds).count() == 1 }
    }

    fun launchArgs(component: String, user: Int, display: Int): List<String> {
        require(user >= 0 && display >= 0)
        require(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+").matches(component))
        return listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--user", "$user", "--display", "$display",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component,
            "-f", "0x10200000")
    }

    fun launchAccepted(code: Int, output: String): Boolean = code == 0 &&
        !Regex("(?im)^\\s*(Error|Exception|SecurityException|java\\.)").containsMatchIn(output) &&
        Regex("(?m)^Status: ok\\s*$").containsMatchIn(output)

    /** Only a uniquely resumed exact component is evidence; aliases/trampolines remain unknown. */
    fun actualDisplay(dump: String, component: String, user: Int): Int? {
        val matches = mutableListOf<Int>()
        var display: Int? = null
        val parts = component.split('/')
        if (parts.size != 2) return null
        fun normalize(c: String): String {
            val p = c.split('/')
            return if (p.size == 2) p[0] + "/" + (if (p[1].startsWith('.')) p[0] + p[1] else p[1]) else c
        }
        for (line in dump.lineSequence()) {
            Regex("^Display #(\\d+)").find(line.trim())?.let { display = it.groupValues[1].toInt() }
            // The global "ResumedActivity:" summary is not scoped to the preceding display.
            val scoped = line.trimStart()
            if (scoped.startsWith("topResumedActivity=") || scoped.startsWith("mResumedActivity:")) {
                val found = Regex("\\bu$user ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)(?:\\s|\\})").find(line)
                if (found != null && normalize(found.groupValues[1]) == normalize(component)) display?.let(matches::add)
            }
        }
        return matches.toSet().singleOrNull()
    }
}
