package ir.zeus.antiadvert;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ZEUSAntiAdvert extends JavaPlugin implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ConcurrentHashMap<UUID, Integer> warnings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> mutedUntil = new ConcurrentHashMap<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> blockedWords = new ArrayList<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        rebuildFilters();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ZEUS Anti-Advert enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getConfigurationSection("warnings") == null
                ? List.<String>of() : data.getConfigurationSection("warnings").getKeys(false)) {
            try { warnings.put(UUID.fromString(key), data.getInt("warnings." + key)); }
            catch (IllegalArgumentException ignored) {}
        }
        for (String key : data.getConfigurationSection("muted-until") == null
                ? List.<String>of() : data.getConfigurationSection("muted-until").getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                long until = data.getLong("muted-until." + key);
                if (until > System.currentTimeMillis()) mutedUntil.put(id, until);
                else warnings.remove(id);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private synchronized void saveData() {
        if (data == null) return;
        data.set("warnings", null);
        data.set("muted-until", null);
        warnings.forEach((id, count) -> data.set("warnings." + id, count));
        mutedUntil.forEach((id, until) -> data.set("muted-until." + id, until));
        try { data.save(dataFile); }
        catch (IOException e) { getLogger().severe("Could not save data.yml: " + e.getMessage()); }
    }

    private void rebuildFilters() {
        blockedWords.clear();
        for (String word : getConfig().getStringList("blocked-words")) {
            if (word != null && !word.isBlank()) blockedWords.add(word.toLowerCase(Locale.ROOT));
        }
        patterns.clear();
        // IPv4 address with optional port
        patterns.add(Pattern.compile("(?i)(?<![\\\\w])(?:\\\\d{1,3}\\\\.){3}\\\\d{1,3}(?::\\\\d{1,5})?(?![\\\\w])"));
        // Web links, URL schemes, Discord invite links and common domain suffixes
        patterns.add(Pattern.compile("(?i)(?:https?://|www\\\\.)\\\\S+"));
        patterns.add(Pattern.compile("(?i)(?:discord\\\\.gg|discord(?:app)?\\\\.com/invite)/[a-z0-9-]+"));
        patterns.add(Pattern.compile("(?i)(?<![@\\\\w])[a-z0-9][a-z0-9.-]{1,62}\\\\.(?:com|net|org|gg|xyz|ir|me|info|biz|tk|ml|cf|gq)(?::\\\\d{1,5})?(?:/\\\\S*)?"));
        for (String regex : getConfig().getStringList("extra-regex")) {
            try { patterns.add(Pattern.compile(regex)); }
            catch (PatternSyntaxException ex) { getLogger().warning("Invalid extra-regex ignored: " + regex); }
        }
    }

    private boolean isAdvertisement(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String word : blockedWords) if (lower.contains(word)) return true;
        for (Pattern pattern : patterns) if (pattern.matcher(message).find()) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("zeus.antiadvert.bypass")) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = mutedUntil.get(id);
        if (until != null) {
            if (until > now) {
                event.setCancelled(true);
                long remaining = until - now;
                Bukkit.getScheduler().runTask(this, () -> send(player,
                        "still-muted", "time", formatDuration(remaining)));
                return;
            }
            mutedUntil.remove(id);
            warnings.remove(id);
            saveData();
            Bukkit.getScheduler().runTask(this, () -> send(player, "expired"));
        }

        String plain = PLAIN.serialize(event.message());
        if (!isAdvertisement(plain)) return;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> processViolation(player));
    }

    private void processViolation(Player player) {
        if (!player.isOnline() || player.hasPermission("zeus.antiadvert.bypass")) return;
        UUID id = player.getUniqueId();
        int count = warnings.merge(id, 1, Integer::sum);
        int max = Math.max(1, getConfig().getInt("warnings-before-mute", 3));
        send(player, "blocked");
        if (count >= max) {
            long minutes = Math.max(1, getConfig().getLong("mute-duration-minutes", 30));
            long until = System.currentTimeMillis() + Duration.ofMinutes(minutes).toMillis();
            mutedUntil.put(id, until);
            warnings.remove(id);
            send(player, "muted", "duration", String.valueOf(minutes));
            getLogger().info(player.getName() + " was automatically muted for " + minutes + " minutes (advertising).");
        } else {
            send(player, "warning", "count", String.valueOf(count), "max", String.valueOf(max));
        }
        saveData();
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(1, millis / 1000);
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return minutes > 0 ? minutes + "m " + remainder + "s" : remainder + "s";
    }

    private void send(CommandSender sender, String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "<gold>ZEUS » </gold>");
        String message = getConfig().getString("messages." + key, "<red>Missing message: " + key);
        for (int i = 0; i + 1 < replacements.length; i += 2)
            message = message.replace("<" + replacements[i] + ">", replacements[i + 1]);
        sender.sendMessage(MM.deserialize(prefix + message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("zeus.antiadvert.admin")) {
            send(sender, "no-permission");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "warnings" -> {
                if (args.length != 1) { send(sender, "usage", "usage", "/warnings <player>"); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                int count = warnings.getOrDefault(target.getUniqueId(), 0);
                send(sender, "warnings", "player", target.getName() == null ? args[0] : target.getName(),
                        "count", String.valueOf(count));
                return true;
            }
            case "clearwarns" -> {
                if (args.length != 1) { send(sender, "usage", "usage", "/clearwarns <player>"); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                warnings.remove(target.getUniqueId());
                saveData();
                send(sender, "cleared", "player", target.getName() == null ? args[0] : target.getName());
                return true;
            }
            case "zeusfilter" -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    rebuildFilters();
                    send(sender, "reload");
                } else send(sender, "usage", "usage", "/zeusfilter reload");
                return true;
            }
            default -> { return false; }
        }
    }
}
