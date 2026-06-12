package us.talabrek.ultimateskyblock.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Utility for translating Minecraft item/block internal names to localized display names.
 * Downloads language files from Mojang's asset servers on first load.
 */
public class TranslationUtil {
    public static final TranslationUtil INSTANCE = new TranslationUtil();

    public Map<String, String> dictionary = new HashMap<>();

    private TranslationUtil() {}

    /**
     * Returns the localized name for an ItemStack's material.
     * Falls back to the lowercase material name if no translation is available.
     */
    public String getItemLocalizedName(ItemStack itemStack) {
        String name = itemStack.getType().name().toLowerCase(Locale.getDefault());
        if (dictionary.containsKey("item.minecraft." + name)) {
            return dictionary.get("item.minecraft." + name);
        }
        if (dictionary.containsKey("block.minecraft." + name)) {
            return dictionary.get("block.minecraft." + name);
        }
        return name;
    }

    /**
     * Loads item language translations from the plugin's i18n/items folder.
     * If the folder does not exist, fetches language assets from Mojang first.
     */
    public boolean loadItemLang(JavaPlugin plugin) {
        File folder = new File(plugin.getDataFolder(), "i18n/items");
        if (!folder.exists()) {
            fetchLanguageAssets(plugin);
        }

        File[] files = folder.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            String tr;
            try {
                tr = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
                plugin.getLogger().severe("TranslationUtil: error in loading " + file.getName());
                return false;
            }

            Type type = new TypeToken<HashMap<String, String>>() {}.getType();
            dictionary = new Gson().fromJson(new JsonParser().parse(tr).getAsJsonObject(), type);
        }
        plugin.getLogger().info("TranslationUtil: Loaded " + dictionary.size() + " item translations. ");
        return true;
    }

    @SuppressWarnings("deprecation")
    private void fetchLanguageAssets(JavaPlugin plugin) {
        try {
            JsonParser parser = new JsonParser();
            String version = Bukkit.getMinecraftVersion();
            String versionManifestJson = readUrl("https://launchermeta.mojang.com/mc/game/version_manifest.json");
            JsonArray versionsManifest = parser.parse(versionManifestJson).getAsJsonObject().getAsJsonArray("versions");

            URL versionUrl = null;
            for (JsonElement element : versionsManifest) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("id") && obj.get("id").getAsString().equals(version)) {
                    versionUrl = new URL(obj.get("url").getAsString());
                    break;
                }
            }
            if (versionUrl == null) {
                plugin.getLogger().severe("TranslationUtil: Unable to find version information: " + version);
                throw new RuntimeException();
            }

            plugin.getLogger().info("TranslationUtil: Fetching metadata of " + version + "...");
            JsonObject versionObj = parser.parse(readUrl(versionUrl.toString())).getAsJsonObject();
            String assetsURL = versionObj.getAsJsonObject("assetIndex").get("url").getAsString();
            if (assetsURL == null) {
                plugin.getLogger().severe("TranslationUtil: Unable to get assets information.");
                throw new RuntimeException();
            }

            plugin.getLogger().info("TranslationUtil: Fetching assets information...");
            JsonObject objects = parser.parse(readUrl(assetsURL)).getAsJsonObject().getAsJsonObject("objects");

            File folder = new File(plugin.getDataFolder(), "i18n/items");
            if (!folder.exists()) {
                folder.mkdirs();
            }

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("minecraft/lang/([^\\.]+)\\.json");

            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String key = entry.getKey();
                java.util.regex.Matcher match = pattern.matcher(key);
                if (match.matches() && match.groupCount() == 1) {
                    String lang = match.group(1);

                    if (!lang.equals("zh_cn")) continue;

                    JsonObject langJsonObject = objects.getAsJsonObject("minecraft/lang/" + lang + ".json");
                    if (langJsonObject == null) {
                        plugin.getLogger().severe("TranslationUtil: Failed to fetch: " + lang + ", internal error.");
                        continue;
                    }
                    plugin.getLogger().info("TranslationUtil: Fetching " + lang + "...");
                    String langJsonDownloadUrl = null;
                    try {
                        String hash = langJsonObject.get("hash").getAsString();
                        langJsonDownloadUrl = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
                        URL json = new URL(langJsonDownloadUrl);
                        File file = new File(folder, lang + ".json");
                        Files.copy(json.openStream(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Cannot load " + lang + " from mojang, url=" + langJsonDownloadUrl);
                        e.printStackTrace();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("TranslationUtil: Error when get item translation files from mojang. Please check your config or add these files manually.");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }
    }

    @SuppressWarnings("deprecation")
    private static String readUrl(String urlString) throws IOException {
        URL url = new URL(urlString);
        return new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
