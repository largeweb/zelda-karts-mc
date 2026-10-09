package local.hyrule;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The server knows nothing of Zelda: every player's own game engine draws Hyrule on
 * their machine and decides where the ground is. This plugin keeps the server's Hyrule
 * world fit for that (no monsters spawning into empty air, no falling out of it), runs
 * the hub, hands out the Zelda items, and answers the few things the client mod asks.
 */
public final class HyrulePlugin extends JavaPlugin implements Listener {
    private static final NamespacedKey HYRULE = new NamespacedKey("composite", "zelda");
    /** Hyrule's areas are centred 1024 blocks apart along x; see config.yml. */
    private static final int AREA = 1024;
    private final Set<UUID> withMod = new HashSet<>();
    private final List<String[]> items = new ArrayList<>(); // id, key, name

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadItems();
        getServer().getPluginManager().registerEvents(this, this);
        if (hyrule() == null)
            getLogger().severe("The Hyrule world (composite:zelda) is missing. Put the hyrule-dimension datapack in the world's datapacks folder and restart.");
        else {
            hyrule().setStorm(false);
            hyrule().setThundering(false);
        }
    }

    private World hyrule() {
        return Bukkit.getWorld(HYRULE);
    }

    private Location place(String name) {
        var c = getConfig();
        return new Location(hyrule(), c.getDouble(name + ".x"), c.getDouble(name + ".y"), c.getDouble(name + ".z"), (float) c.getDouble(name + ".yaw"), 0);
    }

    private boolean inHyrule(Location at) {
        return at.getWorld() != null && at.getWorld().equals(hyrule());
    }

    private boolean inHub(Location at) {
        return inHyrule(at) && area(at) == getConfig().getInt("hub.scene");
    }

    /** Which of Hyrule's areas a place is in: each is centred on a multiple of 1024 blocks. */
    private static int area(Location at) {
        return Math.floorDiv(at.getBlockX() + AREA / 2, AREA);
    }

    /** The item list the client mod ships, so both sides agree on what each item is. */
    private void loadItems() {
        try (var in = getResource("hyrule_items.json")) {
            var json = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : json) {
                var o = element.getAsJsonObject();
                items.add(new String[] { o.get("id").getAsString(), o.get("key").getAsString(), o.get("name").getAsString() });
            }
        } catch (Exception e) {
            getLogger().severe("Could not read the Zelda item list: " + e);
        }
    }

    /** A Zelda item is paper carrying the item's number; the client mod gives it its look and its use. */
    private ItemStack item(String[] entry) {
        return Bukkit.getItemFactory().createItemStack("minecraft:paper[custom_data={hyrule_item:" + entry[0] + "},item_name=\"" + entry[2].replace("\"", "")
            + "\",item_model=\"hyrule:" + entry[1] + "\",max_stack_size=1]");
    }

    // --- arriving -------------------------------------------------------------------

    /** Last word on where a joining player stands: other plugins may have sent them to their own spawn. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        if (hyrule() == null) return;
        if (!inHyrule(player.getLocation())) player.teleport(place("hub"));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !inHyrule(player.getLocation())) player.teleport(place("hub"));
        }, 5);
        player.sendMessage(Component.text("Welcome to Hyrule. /hub returns here, /field goes to Hyrule Field, /zelda all gives every Zelda item, /guide explains the rest.", NamedTextColor.GOLD));
        int seconds = getConfig().getInt("require-mod-seconds");
        if (seconds > 0) Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !withMod.contains(player.getUniqueId())) player.kick(Component.text(getConfig().getString("mod-missing-message", "This server needs the Hyrule mod.")));
        }, seconds * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        withMod.remove(event.getPlayer().getUniqueId());
    }

    /** Runs last: a faction home or a bed inside Hyrule stands, anywhere else becomes the hub. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (hyrule() != null && !inHyrule(event.getRespawnLocation())) event.setRespawnLocation(place("hub"));
    }

    // --- the Hyrule world -------------------------------------------------------------

    /** The server has no ground in Hyrule, so nothing may spawn there by itself. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!inHyrule(event.getLocation())) return;
        switch (event.getSpawnReason()) {
            case SPAWNER_EGG, COMMAND, CUSTOM, BREEDING, DISPENSE_EGG -> { }
            default -> event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.getWorld().equals(hyrule()) && event.toWeatherState()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !inHyrule(player.getLocation())) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            // Their engine stopped or never started: no ground. Back to the hub rather than dying.
            event.setCancelled(true);
            player.setFallDistance(0);
            player.teleport(place("hub"));
        } else if (inHub(player.getLocation())) event.setCancelled(true); // the hub is safe
    }

    // --- the hub: nothing built, nothing broken ------------------------------------------

    private void guard(Cancellable event, Player player, Location at) {
        if (!inHub(at) || (player != null && player.hasPermission("hyrule.hub.build"))) return;
        event.setCancelled(true);
        if (player != null) player.sendActionBar(Component.text("Kokiri Forest is the hub: nothing can be built or broken here.", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        guard(event, event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        guard(event, event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        guard(event, event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        guard(event, event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        guard(event, null, event.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        guard(event, event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> inHub(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> inHub(block.getLocation()));
    }

    // --- commands ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var player = sender instanceof Player p ? p : null;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "hyrule" -> {
                if (args.length >= 1 && args[0].equals("hello") && player != null) withMod.add(player.getUniqueId());
                else if (args.length == 2 && args[0].equals("hurt") && player != null && withMod.contains(player.getUniqueId())) {
                    // A Zelda enemy hit them, on their own machine. Only ever their own health.
                    try {
                        double amount = Double.parseDouble(args[1]);
                        if (amount > 0 && amount <= 40 && !inHub(player.getLocation())) player.damage(amount);
                    } catch (NumberFormatException e) { }
                } else if (args.length == 1 && args[0].equals("reload") && sender.hasPermission("hyrule.admin")) {
                    reloadConfig();
                    sender.sendMessage("Hyrule settings reloaded.");
                } else if (args.length >= 1 && args[0].equals("sethub") && player != null && sender.hasPermission("hyrule.admin")) set("hub", player);
                else if (args.length >= 1 && args[0].equals("setfield") && player != null && sender.hasPermission("hyrule.admin")) set("field", player);
                else sender.sendMessage("/hyrule reload | sethub | setfield");
            }
            case "zelda" -> {
                if (player == null || args.length != 1) return false;
                int given = 0;
                for (var entry : items)
                    if (args[0].equals("all") || args[0].equalsIgnoreCase(entry[1])) {
                        player.getInventory().addItem(item(entry)).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                        given++;
                    }
                if (given == 0) {
                    var keys = new StringBuilder();
                    for (var entry : items) keys.append(entry[1]).append(' ');
                    sender.sendMessage("No such item. One of: all " + keys);
                }
            }
            case "hub", "field" -> {
                if (player == null || hyrule() == null) return true;
                player.setFallDistance(0);
                player.teleport(place(command.getName()));
            }
            default -> sender.sendMessage(Component.text("That command belongs to the Hyrule mod; it needs the mod installed.", NamedTextColor.RED));
        }
        return true;
    }

    private void set(String name, Player player) {
        var at = player.getLocation();
        getConfig().set(name + ".x", at.getX());
        getConfig().set(name + ".y", at.getY());
        getConfig().set(name + ".z", at.getZ());
        getConfig().set(name + ".yaw", (double) at.getYaw());
        if (name.equals("hub")) getConfig().set("hub.scene", area(at));
        saveConfig();
        player.sendMessage("The " + name + " is now where you stand.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("zelda") || args.length != 1) return List.of();
        var out = new ArrayList<String>();
        if ("all".startsWith(args[0])) out.add("all");
        for (var entry : items) if (entry[1].startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(entry[1]);
        return out;
    }
}
