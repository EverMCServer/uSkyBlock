package us.talabrek.ultimateskyblock.handbook;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dk.lockfuglsang.minecraft.file.FileUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.WrittenBookContent;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.bootstrap.PluginDataDir;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads {@code handbook.yml} and turns it into written books.
 *
 * <p>The YAML file is plain content, not a versioned config: it is read on demand (so edits only need
 * {@code /handbook reload}) and never merged with a bundled default, because merging would rewrite an author's work.
 * A sample file is copied out of the jar when none exists yet.
 */
@Singleton
public class HandbookLogic {

    public static final String FILE_NAME = "handbook.yml";

    private static final String DEFAULT_AUTHOR = "服务器";
    private static final String DEFAULT_BOOK = "handbook";

    private final uSkyBlock plugin;
    private final Path dataDir;
    private YamlConfiguration config;
    private final List<String> loadWarnings = new ArrayList<>();

    @Inject
    public HandbookLogic(@NotNull uSkyBlock plugin, @NotNull @PluginDataDir Path dataDir) {
        this.plugin = plugin;
        this.dataDir = dataDir;
    }

    /**
     * Re-reads {@code handbook.yml} from disk. Warnings (bad anchors, overflow) are written to the log.
     */
    public void reload() {
        config = null;
        loadWarnings.clear();
        config();
        for (String warning : loadWarnings) {
            plugin.getLogger().warning("[handbook] " + warning);
        }
    }

    /**
     * @return the id of the book {@code /handbook} opens, or {@code null} when nothing is configured
     */
    public @Nullable String defaultBookId() {
        String configured = config().getString("default-book", DEFAULT_BOOK);
        if (configured != null && books().containsKey(configured.toLowerCase(Locale.ROOT))) {
            return configured.toLowerCase(Locale.ROOT);
        }
        return books().keySet().stream().findFirst().orElse(null);
    }

    /**
     * @return book ids in file order
     */
    public List<String> bookIds() {
        return new ArrayList<>(books().keySet());
    }

    public boolean hasBook(String id) {
        return id != null && books().containsKey(id.toLowerCase(Locale.ROOT));
    }

    /**
     * @return the title configured for a book, or the id when it cannot be resolved
     */
    public String titleOf(String id) {
        ConfigurationSection book = books().get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (book == null) {
            return String.valueOf(id);
        }
        String title = book.getString("title", book.getName());
        return title == null ? book.getName() : title;
    }

    /**
     * @return the number of rendered pages of a book, or {@code -1} when the book does not exist
     */
    public int pageCount(String id) {
        ConfigurationSection book = books().get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (book == null) {
            return -1;
        }
        return render(book).pages().size();
    }

    /**
     * Builds the written book item for the given id.
     *
     * @return the item, or {@code null} when no such book is configured
     */
    public @Nullable ItemStack createItem(String id) {
        ConfigurationSection book = books().get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (book == null) {
            return null;
        }
        String title = book.getString("title", book.getName());
        if (title == null || title.isBlank()) {
            title = book.getName();
        }
        if (title.length() > 32) {
            title = title.substring(0, 32);
        }
        String author = book.getString("author", DEFAULT_AUTHOR);
        int generation = Math.max(0, Math.min(3, book.getInt("generation", 0)));

        HandbookRenderer.Result rendered = render(book);
        WrittenBookContent.Builder builder = WrittenBookContent.writtenBookContent(title,
            author == null || author.isBlank() ? DEFAULT_AUTHOR : author).generation(generation);
        for (Component page : rendered.pages()) {
            builder.addPage(page);
        }
        ItemStack item = ItemStack.of(Material.WRITTEN_BOOK);
        item.setData(DataComponentTypes.WRITTEN_BOOK_CONTENT, builder.build());
        for (String warning : rendered.warnings()) {
            plugin.getLogger().warning("[handbook:" + book.getName() + "] " + warning);
        }
        return item;
    }

    /**
     * Validates every configured book.
     *
     * @return human readable report lines, one per book plus one per warning
     */
    public List<String> check() {
        List<String> report = new ArrayList<>();
        Map<String, ConfigurationSection> books = books();
        if (books.isEmpty()) {
            report.add("handbook.yml 里没有配置任何书（books: 为空）");
            return report;
        }
        for (Map.Entry<String, ConfigurationSection> entry : books.entrySet()) {
            HandbookRenderer.Result rendered = render(entry.getValue());
            report.add(String.format(Locale.ROOT, "%s（%s）: %d 页", entry.getKey(),
                entry.getValue().getString("title", entry.getKey()), rendered.pages().size()));
            for (String warning : rendered.warnings()) {
                report.add("  - " + warning);
            }
        }
        return report;
    }

    private HandbookRenderer.Result render(ConfigurationSection book) {
        int linesPerPage = book.getInt("lines-per-page",
            config().getInt("options.lines-per-page", HandbookRenderer.DEFAULT_LINES_PER_PAGE));
        int lineWidth = book.getInt("line-width",
            config().getInt("options.line-width", HandbookRenderer.DEFAULT_LINE_WIDTH));
        return HandbookRenderer.render(sections(book), linesPerPage, lineWidth);
    }

    private List<HandbookRenderer.Section> sections(ConfigurationSection book) {
        List<HandbookRenderer.Section> sections = new ArrayList<>();
        List<?> raw = book.getList("pages");
        if (raw == null) {
            return sections;
        }
        for (Object entry : raw) {
            if (entry instanceof String text) {
                sections.add(new HandbookRenderer.Section(null, text));
            } else if (entry instanceof ConfigurationSection section) {
                sections.add(new HandbookRenderer.Section(section.getString("id"),
                    section.getString("text", "")));
            } else if (entry instanceof Map<?, ?> map) {
                Object id = map.get("id");
                Object text = map.get("text");
                sections.add(new HandbookRenderer.Section(id == null ? null : String.valueOf(id),
                    text == null ? "" : String.valueOf(text)));
            }
        }
        return sections;
    }

    private Map<String, ConfigurationSection> books() {
        ConfigurationSection root = config().getConfigurationSection("books");
        if (root == null) {
            return Map.of();
        }
        Map<String, ConfigurationSection> result = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection book = root.getConfigurationSection(key);
            if (book != null) {
                result.put(key.toLowerCase(Locale.ROOT), book);
            }
        }
        return result;
    }

    private YamlConfiguration config() {
        if (config == null) {
            config = load();
        }
        return config;
    }

    private YamlConfiguration load() {
        File file = new File(dataDir.toFile(), FILE_NAME);
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) {
            createSampleFile(file);
        }
        FileUtil.readConfig(yaml, file);
        if (!yaml.isConfigurationSection("books")) {
            loadWarnings.add(FILE_NAME + " 里没有 books: 节，/handbook 将没有任何内容可发");
        }
        return yaml;
    }

    private void createSampleFile(File file) {
        try (InputStream in = plugin.getResource(FILE_NAME)) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("[handbook] 无法创建目录 " + parent);
            }
            if (in != null) {
                Files.copy(in, file.toPath());
                plugin.getLogger().info("[handbook] 已从插件内释放示例 " + FILE_NAME);
            } else {
                plugin.getLogger().warning("[handbook] " + file + " 不存在，且插件内没有示例文件");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[handbook] 无法写入 " + file, e);
        }
    }
}
