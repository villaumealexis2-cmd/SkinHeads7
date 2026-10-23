package fr.amethyy.skinheads;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Stockage persistant : UUID -> (pseudo, custom_model_data, hash de l'image). */
public final class SkinStore {

    public static final class Entry {
        public final UUID uuid;
        public volatile String name;
        public final int customModelData;
        public volatile String hash; // SHA-256 de l'image, null si pas encore générée
        public volatile String sourceUrl; // URL exacte utilisée pour générer/télécharger le PNG

        Entry(UUID uuid, String name, int cmd, String hash, String sourceUrl) {
            this.uuid = uuid;
            this.name = name;
            this.customModelData = cmd;
            this.hash = hash;
            this.sourceUrl = sourceUrl;
        }

        /** ID ItemsAdder sans namespace. */
        public String itemId(String prefix) {
            return prefix + uuid.toString().replace("-", "");
        }
    }

    private final SkinHeadsPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private int nextCmd;

    public SkinStore(SkinHeadsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public synchronized void load() {
        entries.clear();
        int first = plugin.getConfig().getInt("item.first-custom-model-data", 25);
        int max = first - 1;
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            var sec = y.getConfigurationSection("players");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(k);
                        String name = sec.getString(k + ".name", "?");
                        int cmd = sec.getInt(k + ".cmd");
                        String hash = sec.getString(k + ".hash");
                        String sourceUrl = sec.getString(k + ".source-url");
                        entries.put(id, new Entry(id, name, cmd, hash, sourceUrl));
                        max = Math.max(max, cmd);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            max = Math.max(max, y.getInt("last-cmd", max));
        }
        nextCmd = max + 1;
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-cmd", nextCmd - 1);
        for (Entry e : entries.values()) {
            String p = "players." + e.uuid;
            y.set(p + ".name", e.name);
            y.set(p + ".cmd", e.customModelData);
            y.set(p + ".hash", e.hash);
            y.set(p + ".source-url", e.sourceUrl);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Impossible de sauvegarder players.yml : " + ex.getMessage());
        }
    }

    public boolean has(UUID id) { return entries.containsKey(id); }
    public Entry get(UUID id) { return entries.get(id); }
    public int size() { return entries.size(); }
    public Collection<Entry> all() { return new ArrayList<>(entries.values()); }

    public synchronized Entry register(UUID id, String name) {
        Entry ex = entries.get(id);
        if (ex != null) return ex;
        Entry e = new Entry(id, name, nextCmd++, null, null);
        entries.put(id, e);
        return e;
    }

    public void updateName(UUID id, String name) {
        Entry e = entries.get(id);
        if (e != null && !Objects.equals(e.name, name)) {
            e.name = name;
            save();
        }
    }
}
