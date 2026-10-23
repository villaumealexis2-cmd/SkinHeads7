package fr.amethyy.skinheads;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Téléchargement des images, écriture des textures et du YAML ItemsAdder. */
public final class SkinService {

    private final SkinHeadsPlugin plugin;
    private final SkinStore store;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Object ioLock = new Object();

    public SkinService(SkinHeadsPlugin plugin, SkinStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // ------------------------------------------------------------ chemins

    private FileConfiguration cfg() { return plugin.getConfig(); }

    private File contentsDir() {
        // plugins/ItemsAdder/contents/amethyy
        return new File(plugin.getDataFolder().getParentFile(),
                cfg().getString("itemsadder.contents-folder", "ItemsAdder/contents/amethyy"));
    }

    private String namespace() { return cfg().getString("itemsadder.namespace", "amethyy"); }
    private String prefix() { return cfg().getString("itemsadder.id-prefix", "skin_"); }
    private String texSub() { return cfg().getString("itemsadder.textures-subfolder", "items/skins"); }

    private File texturesDir() {
        // Destination demandée : plugins/ItemsAdder/contents/amethyy/textures/items/skins
        String configured = cfg().getString("itemsadder.png-output-folder",
                "ItemsAdder/contents/amethyy/textures/items/skins");
        return new File(plugin.getDataFolder().getParentFile(), configured);
    }

    private File textureFile(SkinStore.Entry e) {
        return new File(texturesDir(), e.itemId(prefix()) + ".png");
    }

    private File itemsFile() {
        return new File(contentsDir(), "items/" + cfg().getString("itemsadder.items-file", "skins.yml"));
    }

    // ------------------------------------------------------------ API

    /** L'API est toujours interrogée avec le pseudo Minecraft, jamais avec l'UUID. */
    private URI buildUri(String name) {
        String url = cfg().getString("api.url");
        int size = cfg().getInt("api.size", 8);
        String safeName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(url.replace("{name}", safeName).replace("{size}", String.valueOf(size)));
    }

    /** Télécharge l'image (octets PNG), ou null en cas d'échec. */
    private byte[] fetch(UUID uuid, String name) {
        try {
            URI source = buildUri(name);
            SkinStore.Entry entry = store.get(uuid);
            if (entry != null) entry.sourceUrl = source.toString();
            HttpRequest req = HttpRequest.newBuilder(source)
                    .timeout(Duration.ofSeconds(cfg().getInt("api.timeout-seconds", 15)))
                    .header("User-Agent", "SkinHeads/1.0 (Paper plugin)")
                    .GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                plugin.getLogger().warning("API " + res.statusCode() + " pour " + name);
                return null;
            }
            byte[] body = res.body();
            // Vérifie la signature PNG
            if (body.length < 8 || (body[0] & 0xFF) != 0x89 || body[1] != 'P' || body[2] != 'N' || body[3] != 'G') {
                plugin.getLogger().warning("Réponse non-PNG pour " + name);
                return null;
            }
            return body;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Erreur API pour " + name + " : " + ex.getMessage());
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sleepBetween() {
        try {
            Thread.sleep(cfg().getLong("api.delay-between-requests-ms", 400));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ opérations

    /** Télécharge et écrit la texture d'un joueur. @return true si succès. */
    public boolean downloadFor(UUID uuid, String name) {
        SkinStore.Entry e = store.get(uuid);
        if (e == null) return false;
        byte[] png = fetch(uuid, name);
        if (png == null) return false;
        try {
            synchronized (ioLock) {
                File f = textureFile(e);
                f.getParentFile().mkdirs();
                writeAtomic(f, png);
            }
            e.hash = sha256(png);
            store.save();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Écriture texture impossible pour " + name, ex);
            return false;
        }
    }

    /**
     * Force le re-téléchargement de la texture d'un joueur déjà enregistré,
     * même si l'image existe déjà. @return true si l'image a été regénérée.
     */
    public boolean remakeFor(UUID uuid) {
        SkinStore.Entry e = store.get(uuid);
        if (e == null) return false;
        byte[] png = fetch(uuid, e.name);
        if (png == null) return false;
        try {
            synchronized (ioLock) {
                File f = textureFile(e);
                f.getParentFile().mkdirs();
                writeAtomic(f, png);
            }
            e.hash = sha256(png);
            store.save();
            writeItemsFile();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Écriture texture impossible pour " + e.name, ex);
            return false;
        }
    }

    /** Chemin de la texture d'un joueur (pour affichage dans les commandes de debug). */
    public File texturePathFor(SkinStore.Entry e) { return textureFile(e); }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Génère les images manquantes (joueurs enregistrés sans texture). */
    public boolean processPending() {
        boolean changed = false;
        for (SkinStore.Entry e : store.all()) {
            if (e.hash == null || !textureFile(e).exists()) {
                if (downloadFor(e.uuid, e.name)) changed = true;
                sleepBetween();
            }
        }
        return changed;
    }

    /** Vérifie tous les skins : ré-télécharge et compare au hash stocké. */
    public boolean refreshAllSkins() {
        boolean changed = false;
        int n = 0;
        for (SkinStore.Entry e : store.all()) {
            byte[] png = fetch(e.uuid, e.name);
            sleepBetween();
            if (png == null) continue;
            String h = sha256(png);
            if (!h.equals(e.hash) || !textureFile(e).exists()) {
                try {
                    synchronized (ioLock) {
                        File f = textureFile(e);
                        f.getParentFile().mkdirs();
                        writeAtomic(f, png);
                    }
                    e.hash = h;
                    changed = true;
                    n++;
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Écriture impossible pour " + e.name, ex);
                }
            }
        }
        store.save();
        plugin.getLogger().info("Skins mis à jour : " + n);
        return changed;
    }

    /** Réécrit entièrement skins.yml à partir du store. */
    public void writeItemsFile() {
        synchronized (ioLock) {
            String ns = namespace();
            String material = cfg().getString("item.material", "ANGLER_POTTERY_SHERD");
            String display = cfg().getString("item.display-name", "&7Profil de &f{name}");

            List<SkinStore.Entry> list = store.all();
            list.sort(Comparator.comparingInt(x -> x.customModelData));

            StringBuilder sb = new StringBuilder();
            sb.append("# Fichier généré automatiquement par SkinHeads - NE PAS ÉDITER À LA MAIN\n");
            sb.append("info:\n  namespace: ").append(ns).append("\n\nitems:\n");

            int count = 0;
            for (SkinStore.Entry e : list) {
                if (e.hash == null) continue; // pas encore d'image : on n'écrit pas l'item
                sb.append("  ").append(e.itemId(prefix())).append(":\n");
                sb.append("    display_name: \"").append(display.replace("{name}", escape(e.name))).append("\"\n");
                sb.append("    resource:\n");
                sb.append("      material: ").append(material).append("\n");
                sb.append("      generate: true\n");
                sb.append("      model_id: ").append(e.customModelData).append("\n");
                sb.append("      textures:\n");
                sb.append("        - ").append(texSub()).append("/").append(e.itemId(prefix())).append("\n");
                count++;
            }
            if (count == 0) {
                // YAML valide même sans joueur
                sb.setLength(0);
                sb.append("# Fichier généré automatiquement par SkinHeads\n");
                sb.append("info:\n  namespace: ").append(ns).append("\n\nitems: {}\n");
            }

            try {
                File f = itemsFile();
                f.getParentFile().mkdirs();
                writeAtomic(f, sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Impossible d'écrire " + itemsFile(), ex);
            }
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Lance /iazip (puis la commande de reload éventuelle) depuis le thread principal. */
    public void runZipCommand() {
        String zip = cfg().getString("schedule.zip-command", "iazip");
        String reload = cfg().getString("schedule.reload-command", "");
        long delay = cfg().getLong("schedule.reload-delay-ticks", 100);
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("Exécution de /" + zip);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), zip);
            if (reload != null && !reload.isBlank()) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reload), delay);
            }
        });
    }

    /** Pour la commande /skinheads regen. */
    public CompletableFuture<Boolean> runDailyNow(boolean withRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            boolean changed = false;
            if (withRefresh) changed |= refreshAllSkins();
            changed |= processPending();
            if (changed) {
                writeItemsFile();
                runZipCommand();
            }
            return changed;
        });
    }
}
