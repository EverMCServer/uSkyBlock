package us.talabrek.ultimateskyblock.util

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.lang.reflect.Type
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

object TranslationUtil {
    var dictionary = HashMap<String, String>()

    fun getItemLocalizedName(itemStack: ItemStack): String {
        val name: String = itemStack.type.name.lowercase(Locale.getDefault())
        if (dictionary.contains("item.minecraft.$name")) {
            return dictionary.get("item.minecraft.$name").toString()
        }
        if (dictionary.contains("block.minecraft.$name")) {
            return dictionary.get("block.minecraft.$name").toString()
        }
        return name
    }

    fun loadItemLang(plugin: JavaPlugin): Boolean {
        val folder: File = File(plugin.getDataFolder(), "i18n/items")
        if (!folder.exists()) {
            fetchLanguageAssets(plugin)
        }

        for (file in folder.listFiles()!!) {
            var tr: String? = null
            try {
                tr = file.readText()
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                plugin.logger.severe("TranslationUtil: error in loading " + file.name)
                return false
            }

            val type: Type = object: TypeToken<HashMap<String, String>>() {}.type
            dictionary = Gson().fromJson(JsonParser().parse(tr).asJsonObject, type)

        }
        plugin.logger.info("TranslationUtil: Loaded " + dictionary.toList().size + " item translations. ")
        return true
    }

    private fun fetchLanguageAssets(plugin: JavaPlugin) {
        try {
            val parser = JsonParser()
            val version = Bukkit.getBukkitVersion().split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0]
            val versionsManifest: JsonArray = parser.parse(
                URL("https://launchermeta.mojang.com/mc/game/version_manifest.json").readText()
            ).asJsonObject.getAsJsonArray("versions") // like a array with: {"id": "1.21.1", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/c4c37a8abcbab7fb31031acfb19b9a3a34efb1ec/1.21.1.json", "time": "2025-07-17T06:36:54+00:00", "releaseTime": "2024-08-08T12:24:45+00:00"}
            var versionUrl: URL? = null
            versionsManifest.forEach {
                if (it is JsonObject && it.has("id") && it.get("id").asString.equals(version)) {
                    versionUrl = URL(it.get("url").asString)
                }
            }
            if (versionUrl == null) {
                plugin.logger.severe("TranslationUtil: Unable to find version information: $version")
                throw Exception()
            }
            // versionUrl like: https://piston-meta.mojang.com/v1/packages/c4c37a8abcbab7fb31031acfb19b9a3a34efb1ec/1.21.1.json
            plugin.logger.info("TranslationUtil: Fetching metadata of $version...")
            val assetsURL = parser.parse(versionUrl!!.readText()).asJsonObject.getAsJsonObject("assetIndex").get("url").asString // "assetIndex": {"id": "26", "sha1": "7eb8873392fc365779dbfea6e2c28fca30a6c6cd", "size": 490976, "totalSize": 432035882, "url": "https://piston-meta.mojang.com/v1/packages/7eb8873392fc365779dbfea6e2c28fca30a6c6cd/26.json"}
            if (assetsURL == null) { // https://piston-meta.mojang.com/v1/packages/7eb8873392fc365779dbfea6e2c28fca30a6c6cd/26.json
                plugin.logger.severe("TranslationUtil: Unable to get assets information.")
                throw Exception()
            }
            plugin.logger.info("TranslationUtil: Fetching assets information...") // like array of "minecraft/lang/zh_cn.json": {"hash": "6a829093df0c8f7d3942e26655290334925a9b23", "size": 475421}
            val objects = parser.parse(URL(assetsURL).readText()).asJsonObject.getAsJsonObject("objects")

            val folder = File(plugin.dataFolder, "i18n/items")
            if (!folder.exists()) {
                folder.mkdirs()
            }
            val pattern: Pattern = Pattern.compile("minecraft/lang/([^\\.]+)\\.json") // minecraft/lang/zh_cn.json -> zh_cn

            objects.entrySet().forEach { (key) ->
                val match: Matcher = pattern.matcher(key)
                if (match.matches() && match.groupCount() == 1) {
                    val lang = match.group(1)

                    if (lang != "zh_cn") return@forEach

                    val langJsonObject = objects.getAsJsonObject("minecraft/lang/$lang.json") // minecraft/lang/zh_cn.json
                    if (langJsonObject == null) {
                        plugin.logger.severe("TranslationUtil: Failed to fetch: $lang, internal error.")
                        return@forEach
                    }
                    plugin.logger.info("TranslationUtil: Fetching $lang...")
                    var langJsonDownloadUrl: String? = null
                    try {
                        val hash: String = langJsonObject.get("hash").asString
                        langJsonDownloadUrl = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash // 6a/6a829093df0c8f7d3942e26655290334925a9b23
                        val json = URL(langJsonDownloadUrl)
                        val file = File(folder, "$lang.json")
                        Files.copy(json.openStream(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: Exception) {
                        plugin.logger.severe("Cannot load $lang from mojang, url=$langJsonDownloadUrl")
                        e.printStackTrace()
                        return
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("TranslationUtil: Error when get item translation files from mojang. Please check your config or add these files manually.")
            plugin.server.pluginManager.disablePlugin(plugin)
        }
    }
}
