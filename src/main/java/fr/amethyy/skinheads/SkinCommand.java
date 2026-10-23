package fr.amethyy.skinheads;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SkinCommand implements CommandExecutor, TabCompleter {

    private final SkinHeadsPlugin plugin;

    public SkinCommand(SkinHeadsPlugin plugin) { this.plugin = plugin; }

    private void msg(CommandSender s, String m) {
        String p = plugin.getConfig().getString("messages.prefix", "&8[&dSkinHeads&8] &7");
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', p + m));
    }


    /** UUID du joueur en ligne, sinon celui déjà enregistré dans le store, sinon UUID hors-ligne Bukkit. */
    private java.util.UUID resolveUuid(String pseudo) {
        var online = Bukkit.getPlayerExact(pseudo);
        if (online != null) return online.getUniqueId();
        for (SkinStore.Entry e : plugin.store().all())
            if (e.name != null && e.name.equalsIgnoreCase(pseudo)) return e.uuid;
        return Bukkit.getOfflinePlayer(pseudo).getUniqueId();
    }

    /** Retrouve l'entrée du store par pseudo (insensible à la casse). */
    private SkinStore.Entry findEntry(String pseudo) {
        return plugin.store().get(resolveUuid(pseudo));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            msg(s, "/skinheads <reload|regen|check|give|info|link|remake|emulate>");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                msg(s, "Configuration rechargée.");
            }
            case "regen" -> {
                msg(s, "Génération des images manquantes + /iazip si besoin...");
                plugin.service().runDailyNow(false).thenAccept(ch ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                msg(s, ch ? "Terminé, /iazip lancé." : "Rien à mettre à jour.")));
            }
            case "check" -> {
                msg(s, "Vérification des changements de skin de tous les joueurs...");
                plugin.service().runDailyNow(true).thenAccept(ch ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                msg(s, ch ? "Skins mis à jour, /iazip lancé." : "Aucun changement.")));
            }
            case "info" -> {
                if (a.length < 2) { msg(s, "/skinheads info <joueur>"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                var e = plugin.store().get(op.getUniqueId());
                if (e == null) { msg(s, "Joueur inconnu."); return true; }
                msg(s, e.name + " -> custom_model_data " + e.customModelData
                        + ", id " + plugin.getConfig().getString("itemsadder.namespace", "amethyy")
                        + ":" + e.itemId(plugin.getConfig().getString("itemsadder.id-prefix", "skin_")));
                msg(s, "PNG : " + plugin.service().texturePathFor(e).getPath());
                msg(s, "URL source : " + (e.sourceUrl == null ? "non enregistrée (relance /skinheads remake " + e.name + ")" : e.sourceUrl));
            }
            case "link" -> {
                if (a.length < 2) { msg(s, "/skinheads link <joueur>"); return true; }
                SkinStore.Entry e = findEntry(a[1]);
                if (e == null) { msg(s, "Joueur inconnu : " + a[1]); return true; }
                msg(s, "URL source du PNG de " + e.name + " :");
                msg(s, e.sourceUrl == null ? "Aucun lien enregistré. Utilise /skinheads remake " + e.name + "." : e.sourceUrl);
            }
            case "give" -> {
                // /skinheads give <joueur-cible> [joueur-profil]  -> utilise /iagive
                if (a.length < 2) { msg(s, "/skinheads give <cible> [profil]"); return true; }
                String target = a[1];
                String profil = a.length >= 3 ? a[2] : a[1];
                OfflinePlayer op = Bukkit.getOfflinePlayer(profil);
                var e = plugin.store().get(op.getUniqueId());
                if (e == null || e.hash == null) { msg(s, "Aucune image pour ce joueur."); return true; }
                String id = plugin.getConfig().getString("itemsadder.namespace", "amethyy")
                        + ":" + e.itemId(plugin.getConfig().getString("itemsadder.id-prefix", "skin_"));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iagive " + target + " " + id + " 1");
                msg(s, "Item donné.");
            }
            case "remake" -> {
                // /skinheads remake <pseudo> : force la regénération de la texture d'un joueur
                if (a.length < 2) { msg(s, "/skinheads remake <pseudo>"); return true; }
                String pseudo = a[1];
                SkinStore.Entry e = findEntry(pseudo);
                if (e == null) {
                    msg(s, "Joueur inconnu : " + pseudo + ". Utilise /skinheads emulate " + pseudo + " d'abord.");
                    return true;
                }
                msg(s, "Regénération de l'icône de " + e.name + "...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean ok = plugin.service().remakeFor(e.uuid);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ok) {
                            msg(s, "Icône regénérée : " + plugin.service().texturePathFor(e).getPath());
                            msg(s, "Visible en jeu après /iazip (ou /skinheads regen).");
                        } else {
                            msg(s, "Échec de la regénération (voir la console).");
                        }
                    });
                });
            }
            case "emulate" -> {
                // /skinheads emulate <pseudo> : simule la première connexion d'un joueur
                if (a.length < 2) { msg(s, "/skinheads emulate <pseudo>"); return true; }
                String pseudo = a[1];
                java.util.UUID uuid = resolveUuid(pseudo);
                if (plugin.store().has(uuid)) {
                    msg(s, pseudo + " est déjà enregistré. Utilise /skinheads remake " + pseudo + ".");
                    return true;
                }
                msg(s, "Émulation de la première connexion de " + pseudo + "...");
                plugin.handleFirstJoin(uuid, pseudo, ok ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (ok) {
                                var entry = plugin.store().get(uuid);
                                msg(s, "Texture pré-générée pour " + pseudo
                                        + " (CMD " + entry.customModelData + ").");
                                msg(s, "Visible en jeu après /iazip (ou /skinheads regen).");
                            } else {
                                msg(s, "Échec du téléchargement (voir la console). Le joueur reste enregistré, "
                                        + "/skinheads remake " + pseudo + " pour réessayer.");
                            }
                        }));
            }
            default -> msg(s, "Sous-commande inconnue.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("reload", "regen", "check", "give", "info", "link", "remake", "emulate"))
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length >= 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("info") || a[0].equalsIgnoreCase("link")
                || a[0].equalsIgnoreCase("remake") || a[0].equalsIgnoreCase("emulate"))) {
            for (var p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)))
                    out.add(p.getName());
        }
        return out;
    }
}
