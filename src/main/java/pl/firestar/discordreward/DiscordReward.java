package pl.firestar.discordreward;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class DiscordReward extends JavaPlugin implements Listener, CommandExecutor {

    private static final int BOOK_SLOT = 22;
    private static final Pattern NICK = Pattern.compile("^\\.?[A-Za-z0-9_]{3,16}$");

    private File dataFile;
    private YamlConfiguration data;

    private Connection conn;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private int pollCount = 0;
    private long lastWarn = 0;

    /** Holder pozwala rozpoznac nasze GUI bez porownywania tytulow. */
    private static class RewardHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Wynik proby odebrania nagrody. */
    private static class Result {
        final String status;
        final long remaining;
        final String nick;

        Result(String status, long remaining, String nick) {
            this.status = status;
            this.remaining = remaining;
            this.nick = nick;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("nagroda").setExecutor(this);

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            getLogger().severe("Brak sterownika MySQL! Sprawdz, czy serwer ma dostep do internetu (Maven Central).");
        }

        String pass = getConfig().getString("mysql.password", "");
        if (pass.isEmpty() || pass.startsWith("WKLEJ_")) {
            getLogger().warning("!!! Ustaw mysql.password w config.yml !!!");
        }

        // tabela + sprawdzanie zgloszen z bota (1x na sekunde, poza glownym watkiem)
        Bukkit.getScheduler().runTaskAsynchronously(this, this::initTable);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::poll, 60L, 20L);
    }

    @Override
    public void onDisable() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    // ------------------------------------------------------------------ baza

    private synchronized Connection db() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(2)) {
            String url = "jdbc:mysql://" + getConfig().getString("mysql.host")
                    + ":" + getConfig().getInt("mysql.port", 3306)
                    + "/" + getConfig().getString("mysql.database")
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
            conn = DriverManager.getConnection(url,
                    getConfig().getString("mysql.user"),
                    getConfig().getString("mysql.password"));
        }
        return conn;
    }

    private void warnThrottled(String text) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            getLogger().warning(text);
        }
    }

    private void initTable() {
        try (Statement st = db().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dr_requests ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "discord_id VARCHAR(32) NOT NULL,"
                    + "nick VARCHAR(20) NOT NULL,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
                    + "remaining BIGINT NOT NULL DEFAULT 0,"
                    + "result_nick VARCHAR(20) NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "INDEX idx_status (status),"
                    + "INDEX idx_discord (discord_id)"
                    + ") DEFAULT CHARSET=utf8mb4");
            getLogger().info("Polaczono z baza MySQL. Czekam na zgloszenia z bota.");
        } catch (SQLException e) {
            getLogger().severe("Nie udalo sie polaczyc z baza MySQL: " + e.getMessage());
        }
    }

    /** Dziala asynchronicznie co sekunde: pobiera zgloszenia z bota i je przetwarza. */
    private void poll() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            Connection c = db();

            List<Long> ids = new ArrayList<>();
            List<String> nicks = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, nick FROM dr_requests WHERE status='PENDING' "
                            + "AND created_at > (NOW() - INTERVAL 2 MINUTE) ORDER BY id LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                    nicks.add(rs.getString(2));
                }
            }

            for (int i = 0; i < ids.size(); i++) {
                final String nick = nicks.get(i);
                Result r;
                if (!NICK.matcher(nick).matches()) {
                    r = new Result("BAD_NICK", 0, null);
                } else {
                    r = Bukkit.getScheduler().callSyncMethod(this, () -> claim(nick)).get(5, TimeUnit.SECONDS);
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE dr_requests SET status=?, remaining=?, result_nick=? WHERE id=?")) {
                    up.setString(1, r.status);
                    up.setLong(2, r.remaining);
                    up.setString(3, r.nick);
                    up.setLong(4, ids.get(i));
                    up.executeUpdate();
                }
            }

            // co ok. minute sprzatanie starych, nieudanych zgloszen
            if (++pollCount % 60 == 0) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM dr_requests WHERE status<>'OK' "
                            + "AND created_at < (NOW() - INTERVAL 10 MINUTE)");
                }
            }
        } catch (Exception e) {
            warnThrottled("Blad komunikacji z baza MySQL: " + e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, ""));
    }

    private boolean isClaimed(UUID id) {
        return data.getBoolean("claimed." + id);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie zapisac data.yml: " + e.getMessage());
        }
    }

    private String formatTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || h > 0) sb.append(m).append("min ");
        sb.append(s).append("s");
        return sb.toString();
    }

    private void showTitle(Player p, String text) {
        p.sendTitle(" ", text, 10, 70, 20);
    }

    // ------------------------------------------------------------------ GUI

    private void openGui(Player p) {
        Inventory inv = Bukkit.createInventory(new RewardHolder(), 54, color(getConfig().getString("gui.title")));

        if (getConfig().getBoolean("gui.filler", true)) {
            Material pane = Material.matchMaterial("BLACK_STAINED_GLASS_PANE");
            if (pane != null) {
                ItemStack filler = new ItemStack(pane);
                ItemMeta fm = filler.getItemMeta();
                fm.setDisplayName(" ");
                filler.setItemMeta(fm);
                for (int i = 0; i < 54; i++) inv.setItem(i, filler);
            }
        }

        boolean claimed = isClaimed(p.getUniqueId());
        String status = color(getConfig().getString(claimed ? "gui.status-yes" : "gui.status-no"));

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("gui.title")));
        List<String> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("gui.lore")) {
            lore.add(color(line).replace("{status}", status));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        book.setItemMeta(meta);
        inv.setItem(BOOK_SLOT, book);

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RewardHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getRawSlot() != BOOK_SLOT) return;

        Player p = (Player) e.getWhoClicked();
        p.closeInventory();

        if (isClaimed(p.getUniqueId())) {
            p.sendMessage(msg("already-claimed-chat"));
            return;
        }

        String link = getConfig().getString("link");
        String text = msg("link").replace("{link}", link);
        BaseComponent[] comps = TextComponent.fromLegacyText(text);
        for (BaseComponent c : comps) {
            c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
        }
        p.spigot().sendMessage(comps);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RewardHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ komenda

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reset")) {
            if (!sender.hasPermission("discordreward.admin")) {
                sender.sendMessage(msg("no-perm"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uzycie: /nagroda reset <nick>");
                return true;
            }
            final String name = args[1];
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            data.set("claimed." + op.getUniqueId(), null);
            saveData();

            // usun tez zapis konta Discord, zeby mozna bylo odebrac ponownie
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try (PreparedStatement ps = db().prepareStatement(
                        "DELETE FROM dr_requests WHERE status='OK' AND LOWER(result_nick)=LOWER(?)")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    getLogger().warning("Reset w bazie nie powiodl sie: " + e.getMessage());
                }
            });

            sender.sendMessage(msg("reset-ok").replace("{player}", name));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Tylko dla graczy.");
            return true;
        }
        openGui((Player) sender);
        return true;
    }

    // ------------------------------------------------------------------ odbieranie nagrody

    /** Wywolywane NA GLOWNYM WATKU. */
    private Result claim(String nick) {
        Player p = Bukkit.getPlayerExact(nick);
        if (p == null) return new Result("OFFLINE", 0, null);

        UUID id = p.getUniqueId();
        if (isClaimed(id)) return new Result("ALREADY", 0, p.getName());

        long playedTicks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long neededTicks = getConfig().getLong("required-minutes", 60) * 60L * 20L;

        if (playedTicks < neededTicks) {
            long remaining = (neededTicks - playedTicks + 19) / 20; // w gore do pelnych sekund
            showTitle(p, msg("need-time").replace("{time}", formatTime(remaining)));
            return new Result("TIME", remaining, p.getName());
        }

        for (String c : getConfig().getStringList("reward-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        }
        data.set("claimed." + id, true);
        saveData();
        showTitle(p, msg("success"));
        return new Result("OK", 0, p.getName());
    }
}
