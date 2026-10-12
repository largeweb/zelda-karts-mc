package local.hyrule;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
        // Settings added by a newer version of the plugin appear in an existing file.
        getConfig().options().copyDefaults(true);
        saveConfig();
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
        player.sendMessage(Component.text("Welcome to Hyrule. /hub is here, /go lists places to build, /kit starter gives tools and blocks hourly, /zelda adult gives Link's gear, /shop sells more, /guide explains the rest.", NamedTextColor.GOLD));
        // The mod announces itself when its engine is up. Someone without it sees an empty sky.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || withMod.contains(player.getUniqueId())) return;
            getLogger().warning(player.getName() + " has not been seen with the Hyrule mod.");
            player.sendMessage(Component.text(getConfig().getString("mod-missing-message", "This server needs the Hyrule mod."), NamedTextColor.RED));
            int seconds = getConfig().getInt("require-mod-seconds");
            if (seconds > 0) Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && !withMod.contains(player.getUniqueId())) player.kick(Component.text(getConfig().getString("mod-missing-message", "This server needs the Hyrule mod.")));
            }, seconds * 20L);
        }, 45 * 20L);
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
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getDamage() > 30) {
            event.setCancelled(true); // no real drop in Hyrule is that long: it was a jump between places
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            // Their engine stopped or never started: no ground. Back to the hub rather than dying.
            event.setCancelled(true);
            player.setFallDistance(0);
            player.teleport(place("hub"));
        } else if (inHub(player.getLocation())) event.setCancelled(true); // the hub is safe
    }

    // --- the hub: nothing built, nothing broken ------------------------------------------

    private void guard(Cancellable event, Player player, Location at) {
        if (!inHub(at) || (player != null && (player.getGameMode() == GameMode.CREATIVE || player.hasPermission("hyrule.hub.build")))) return;
        event.setCancelled(true);
        if (player == null) return;
        player.sendActionBar(Component.text("Kokiri Forest is the hub: nothing can be built or broken here.", NamedTextColor.RED));
        // Now and then, the way out: places to build, one click each.
        long now = System.currentTimeMillis();
        if (now - told.getOrDefault(player.getUniqueId(), 0L) < 15000) return;
        told.put(player.getUniqueId(), now);
        player.sendMessage(Component.text("You can build anywhere outside the forest. Go to: ", NamedTextColor.GOLD).append(areaLinks(getConfig().getStringList("featured-areas"))));
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

    private final Map<UUID, Long> told = new HashMap<>();

    /** Area names as chat links that run /go. */
    private Component areaLinks(List<String> names) {
        var line = Component.empty();
        for (var name : names)
            line = line.append(Component.text("[" + getConfig().getString("areas." + name + ".title", name) + "] ", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/go " + name)));
        return line;
    }

    // --- digging into Hyrule ----------------------------------------------------------
    // Hyrule's ground exists only in each player's game engine. A dug cell is recorded in
    // the world as an invisible block, a light block of level 0, which every client reads;
    // solid ground exposed by a dig becomes real blocks that clients ask for as they see
    // the need. See Digging.java in the mod.

    private boolean checking;
    private final Set<Block> justBroken = new HashSet<>();

    private static boolean marker(Block block) {
        return block.getType() == Material.LIGHT && block.getBlockData() instanceof Light light && light.getLevel() == 0;
    }

    private static void mark(Block block) {
        var data = (Light) Material.LIGHT.createBlockData();
        data.setLevel(0);
        block.setBlockData(data, false);
    }

    private static final BlockFace[] SIDES = { BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };

    private static boolean besideDig(Block block) {
        for (var side : SIDES) if (marker(block.getRelative(side))) return true;
        return false;
    }

    /** May this player break here? Asked as an ordinary block break, so the hub, faction claims and regions all have their say. */
    private boolean mayBreak(Player player, Block block) {
        checking = true;
        try {
            var event = new BlockBreakEvent(block, player);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } finally {
            checking = false;
        }
    }

    private Block cell(Player player, String[] args) {
        try {
            var block = hyrule().getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            return inHyrule(player.getLocation()) && block.getLocation().distanceSquared(player.getLocation()) <= 144 ? block : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** What Zelda's surface materials (the mod's numbers) are as blocks: dug up, and underneath. */
    private static Material dugAs(int material) {
        return switch (material) { case 1 -> Material.SAND; case 2 -> Material.COBBLESTONE; case 9, 10, 13 -> Material.OAK_PLANKS; case 12 -> Material.ICE; default -> Material.DIRT; };
    }

    private Material lining(Block block, int material) {
        // Well below the surface it is stone, whatever is on top.
        int depth = 0;
        for (var above = block.getRelative(BlockFace.UP); depth < 4 && (marker(above) || !above.getType().isAir()); above = above.getRelative(BlockFace.UP)) depth++;
        if (depth >= 4) return Material.STONE;
        if (material < 0)
            // The asker did not dig here: match what already lines the hole.
            for (var side : SIDES) {
                var type = block.getRelative(side).getType();
                if (type == Material.STONE || type == Material.SANDSTONE || type == Material.DIRT) return type;
            }
        return switch (material) { case 1 -> Material.SANDSTONE; case 2, 12 -> Material.STONE; default -> Material.DIRT; };
    }

    private void dig(Player player, String[] args) {
        var block = cell(player, args);
        if (block == null || marker(block) || !mayBreak(player, block)) return;
        int material = 0;
        try { material = Integer.parseInt(args[4]); } catch (RuntimeException e) { }
        boolean drops = player.getGameMode() != GameMode.CREATIVE;
        if (!block.getType().isAir()) {
            if (drops) block.breakNaturally(player.getInventory().getItemInMainHand());
        } else if (drops) block.getWorld().dropItem(block.getLocation().add(.5, .5, .5), new ItemStack(dugAs(material)));
        block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_GRAVEL_BREAK, 1, 1);
        mark(block);
    }

    private void line(Player player, String[] args) {
        var block = cell(player, args);
        if (block == null || !block.getType().isAir() || justBroken.contains(block) || !besideDig(block) || inHub(block.getLocation())) return;
        int material = -1;
        try { material = Integer.parseInt(args[4]); } catch (RuntimeException e) { }
        block.setType(lining(block, material), false);
    }

    /** A hoe on a block standing in a dug cell: the block comes out and the game's own ground is back. */
    private void restore(Player player, String[] args) {
        var block = cell(player, args);
        if (block == null || block.getType().isAir() || marker(block) || !mayBreak(player, block)) return;
        if (player.getGameMode() != GameMode.CREATIVE) block.breakNaturally();
        block.setType(Material.AIR, false);
    }

    /** A real block broken beside a dig was part of the dug ground: its cell is dug now too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDugFurther(BlockBreakEvent event) {
        if (!checking) spread(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlastFurther(EntityExplodeEvent event) {
        spread(event.blockList());
    }

    private void spread(List<Block> broken) {
        var cells = new ArrayList<Block>();
        for (var block : broken) if (inHyrule(block.getLocation()) && !inHub(block.getLocation())) cells.add(block);
        if (cells.isEmpty()) return;
        justBroken.addAll(cells);
        Bukkit.getScheduler().runTask(this, () -> {
            // Outward from the existing dig, so a blast crater joins it cell by cell.
            for (boolean grew = true; grew;) {
                grew = false;
                for (var block : cells)
                    if (block.getType().isAir() && besideDig(block)) {
                        mark(block);
                        grew = true;
                    }
            }
        });
        Bukkit.getScheduler().runTaskLater(this, () -> justBroken.removeAll(cells), 20);
    }

    /**
     * Zelda moves players in jumps: between areas, out of a cutscene, down from where /go
     * holds them. To the server a jump downwards looks like a fall; it is not one.
     */
    @EventHandler(ignoreCancelled = true)
    public void onJump(org.bukkit.event.player.PlayerMoveEvent event) {
        if (inHyrule(event.getTo()) && event.getFrom().distanceSquared(event.getTo()) > 64) event.getPlayer().setFallDistance(0);
    }

    /** Nothing the server knows of holds things up in Hyrule: dropped items and experience stay where they appear. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(EntitySpawnEvent event) {
        var entity = event.getEntity();
        if (!(entity instanceof Item || entity instanceof ExperienceOrb) || !inHyrule(event.getLocation())) return;
        entity.setGravity(false);
        Bukkit.getScheduler().runTask(this, () -> entity.setVelocity(new org.bukkit.util.Vector()));
    }

    // --- the shop -------------------------------------------------------------------

    private static final class Shop implements InventoryHolder {
        Inventory inventory;
        final List<Object[]> offers = new ArrayList<>(); // material, amount, price

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private net.milkbowl.vault.economy.Economy economy() {
        var registration = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    private void openShop(Player player) {
        var economy = economy();
        if (economy == null) {
            player.sendMessage(Component.text("The shop is closed: no economy plugin is running.", NamedTextColor.RED));
            return;
        }
        var shop = new Shop();
        shop.inventory = Bukkit.createInventory(shop, 54, Component.text("Shop - you have " + economy.format(economy.getBalance(player))));
        for (var line : getConfig().getStringList("shop")) {
            var parts = line.trim().split("\\s+");
            var material = parts.length == 3 ? Material.matchMaterial(parts[0]) : null;
            if (material == null || shop.offers.size() >= 54) continue;
            int amount = Integer.parseInt(parts[1]);
            double price = Double.parseDouble(parts[2]);
            var stack = new ItemStack(material, amount);
            var meta = stack.getItemMeta();
            meta.lore(List.of(Component.text("Buy " + amount + " for " + economy.format(price), NamedTextColor.GREEN)));
            stack.setItemMeta(meta);
            shop.inventory.setItem(shop.offers.size(), stack);
            shop.offers.add(new Object[] { material, amount, price });
        }
        player.openInventory(shop.inventory);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Shop shop)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != shop.inventory) return;
        int slot = event.getSlot();
        if (slot < 0 || slot >= shop.offers.size()) return;
        var offer = shop.offers.get(slot);
        var economy = economy();
        double price = (Double) offer[2];
        if (economy == null || !economy.has(player, price)) {
            player.sendMessage(Component.text("You cannot afford that. /sell hand turns what you hold into money; /worth shows its price.", NamedTextColor.RED));
            return;
        }
        economy.withdrawPlayer(player, price);
        player.getInventory().addItem(new ItemStack((Material) offer[0], (Integer) offer[1])).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        player.sendActionBar(Component.text("Bought. You have " + economy.format(economy.getBalance(player)), NamedTextColor.GREEN));
    }

    // --- commands ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var player = sender instanceof Player p ? p : null;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "hyrule" -> {
                // Only the mod sends these, so any of them shows the player has it.
                if (player != null && args.length >= 1 && List.of("hello", "dig", "line", "restore", "hurt").contains(args[0])) withMod.add(player.getUniqueId());
                if (args.length >= 1 && args[0].equals("hello") && player != null) return true;
                else if (args.length >= 4 && player != null && hyrule() != null
                    && (args[0].equals("dig") || args[0].equals("line") || args[0].equals("restore"))) {
                    switch (args[0]) {
                        case "dig" -> dig(player, args);
                        case "line" -> line(player, args);
                        default -> restore(player, args);
                    }
                }
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
                var adult = getConfig().getStringList("adult-items");
                for (var entry : items)
                    if (args[0].equals("all") || args[0].equalsIgnoreCase(entry[1]) || (args[0].equals("adult") && adult.contains(entry[1]))) {
                        player.getInventory().addItem(item(entry)).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                        given++;
                    }
                if (given == 0) {
                    var keys = new StringBuilder();
                    for (var entry : items) keys.append(entry[1]).append(' ');
                    sender.sendMessage("No such item. One of: adult all " + keys);
                }
            }
            case "hub", "field" -> {
                if (player == null || hyrule() == null) return true;
                player.setFallDistance(0);
                player.teleport(place(command.getName()));
            }
            case "go" -> {
                if (player == null || hyrule() == null) return true;
                var areas = getConfig().getConfigurationSection("areas");
                if (args.length != 1 || areas == null || !areas.contains(args[0].toLowerCase(Locale.ROOT))) {
                    player.sendMessage(Component.text("Where to? ", NamedTextColor.GOLD).append(areaLinks(areas == null ? List.of() : new ArrayList<>(areas.getKeys(false)))));
                    return true;
                }
                // High above the middle of the area: the mod sets the player down at the area's own entrance.
                int scene = areas.getInt(args[0].toLowerCase(Locale.ROOT) + ".scene");
                player.setFallDistance(0);
                player.teleport(new Location(hyrule(), scene * (double) AREA + .5, 2000, .5));
            }
            case "shop" -> {
                if (player != null) openShop(player);
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
        if (command.getName().equalsIgnoreCase("go") && args.length == 1) {
            var areas = getConfig().getConfigurationSection("areas");
            var names = new ArrayList<String>();
            if (areas != null) for (var name : areas.getKeys(false)) if (name.startsWith(args[0].toLowerCase(Locale.ROOT))) names.add(name);
            return names;
        }
        if (!command.getName().equalsIgnoreCase("zelda") || args.length != 1) return List.of();
        var out = new ArrayList<String>();
        if ("all".startsWith(args[0])) out.add("all");
        if ("adult".startsWith(args[0])) out.add("adult");
        for (var entry : items) if (entry[1].startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(entry[1]);
        return out;
    }
}
