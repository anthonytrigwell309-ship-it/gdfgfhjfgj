package com.vemom.fakebase;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class FakeBasePlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final Component PREFIX = Component.text("[FakeBase] ", NamedTextColor.GOLD);

    /** A spawned fake base. Offsets in {@code original} are relative to (cx, cy, cz). */
    record Region(int id, UUID world, int cx, int cy, int cz, int half, String creator, List<String> original) {
        boolean contains(Location l) {
            return l.getWorld() != null
                    && l.getWorld().getUID().equals(world)
                    && Math.abs(l.getBlockX() - cx) <= half
                    && Math.abs(l.getBlockZ() - cz) <= half
                    && l.getBlockY() >= cy - 1
                    && l.getBlockY() <= cy + 4;
        }
    }

    private final Map<Integer, Region> regions = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<String> punished = new HashSet<>();
    private File dataFile;
    private YamlConfiguration data;
    private int nextId = 1;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadRegions();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("fakebase");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    private void loadRegions() {
        regions.clear();
        nextId = data.getInt("next-id", 1);
        ConfigurationSection sec = data.getConfigurationSection("regions");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(key);
                regions.put(id, new Region(id, UUID.fromString(s.getString("world", "")),
                        s.getInt("cx"), s.getInt("cy"), s.getInt("cz"), s.getInt("half"),
                        s.getString("creator", "?"), s.getStringList("original")));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping corrupt fake base entry: " + key);
            }
        }
    }

    private void saveData() {
        data.set("next-id", nextId);
        data.set("regions", null);
        for (Region r : regions.values()) {
            String p = "regions." + r.id() + ".";
            data.set(p + "world", r.world().toString());
            data.set(p + "cx", r.cx());
            data.set(p + "cy", r.cy());
            data.set(p + "cz", r.cz());
            data.set(p + "half", r.half());
            data.set(p + "creator", r.creator());
            data.set(p + "original", r.original());
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("fakebase.use")) {
            s.sendMessage(msg("You don't have permission.", NamedTextColor.RED));
            return true;
        }
        String sub = a.length == 0 ? "create" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create", "spawn" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(msg("Run this in-game.", NamedTextColor.RED));
                    return true;
                }
                create(p);
            }
            case "remove", "delete" -> remove(s, a);
            case "list" -> list(s);
            case "tp" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(msg("Run this in-game.", NamedTextColor.RED));
                    return true;
                }
                Region r = a.length > 1 ? byId(a[1]) : null;
                World w = r == null ? null : Bukkit.getWorld(r.world());
                if (w == null) {
                    s.sendMessage(msg("Usage: /fakebase tp <id>", NamedTextColor.RED));
                } else {
                    p.teleport(new Location(w, r.cx() + 0.5, r.cy(), r.cz() + 0.5));
                }
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage(msg("Config reloaded.", NamedTextColor.GREEN));
            }
            default -> s.sendMessage(msg("/fakebase [create | remove <id|all> | list | tp <id> | reload]",
                    NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("fakebase.use")) return List.of();
        if (a.length == 1) {
            return List.of("create", "remove", "list", "tp", "reload").stream()
                    .filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("tp"))) {
            List<String> out = new ArrayList<>();
            regions.keySet().forEach(id -> out.add(String.valueOf(id)));
            if (a[0].equalsIgnoreCase("remove")) out.add("all");
            return out;
        }
        return List.of();
    }

    private Region byId(String raw) {
        try {
            return regions.get(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void list(CommandSender s) {
        if (regions.isEmpty()) {
            s.sendMessage(msg("No fake bases active.", NamedTextColor.YELLOW));
            return;
        }
        for (Region r : regions.values()) {
            World w = Bukkit.getWorld(r.world());
            s.sendMessage(msg("#" + r.id() + " - " + (w == null ? "?" : w.getName()) + " "
                    + r.cx() + " " + r.cy() + " " + r.cz() + " (by " + r.creator() + ")", NamedTextColor.YELLOW));
        }
    }

    private void remove(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(msg("Usage: /fakebase remove <id|all>", NamedTextColor.RED));
            return;
        }
        if (a[1].equalsIgnoreCase("all")) {
            int n = regions.size();
            for (Region r : new ArrayList<>(regions.values())) restore(r);
            regions.clear();
            saveData();
            s.sendMessage(msg("Removed " + n + " fake base(s) and restored the terrain.", NamedTextColor.GREEN));
            return;
        }
        Region r = byId(a[1]);
        if (r == null) {
            s.sendMessage(msg("No fake base with that id.", NamedTextColor.RED));
            return;
        }
        regions.remove(r.id());
        restore(r);
        saveData();
        s.sendMessage(msg("Removed fake base #" + r.id() + " and restored the terrain.", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ building

    private void create(Player p) {
        FileConfiguration c = getConfig();
        if (regions.size() >= c.getInt("max-active-bases", 5)) {
            p.sendMessage(msg("Max active fake bases reached. Remove one first.", NamedTextColor.RED));
            return;
        }
        World w = p.getWorld();
        Location at = p.getLocation().getBlock().getLocation();
        int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();

        int shulkerCount = Math.max(0, Math.min(400, c.getInt("shulker-boxes", 100)));
        int cols = (int) Math.ceil(Math.sqrt(Math.max(1, shulkerCount)));
        int rows = shulkerCount == 0 ? 0 : (int) Math.ceil(shulkerCount / (double) cols);
        int half = Math.max(7, Math.max(cols, rows) / 2 + 4);
        int r = half - 1; // interior radius

        if (cy - 1 < w.getMinHeight() || cy + 4 >= w.getMaxHeight()) {
            p.sendMessage(msg("Can't build at this height.", NamedTextColor.RED));
            return;
        }

        // 1) snapshot what's there so /fakebase remove can restore it
        List<String> original = new ArrayList<>();
        for (int dx = -half; dx <= half; dx++)
            for (int dy = -1; dy <= 4; dy++)
                for (int dz = -half; dz <= half; dz++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    original.add(dx + "," + dy + "," + dz + "|" + b.getBlockData().getAsString());
                }

        Material wall = mat(c.getString("wall-material"), Material.DEEPSLATE_BRICKS);
        Material lightMat = mat(c.getString("light-material"), Material.SEA_LANTERN);

        // 2) shell: floor, walls, roof, hollow interior
        for (int dx = -half; dx <= half; dx++)
            for (int dy = -1; dy <= 4; dy++)
                for (int dz = -half; dz <= half; dz++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                    if (dy == -1 || dy == 4) b.setType(wall, false);
                    else b.setType(edge ? wall : Material.AIR, false);
                }
        for (int dx = -half + 1; dx <= half - 1; dx++)
            for (int dz = -half + 1; dz <= half - 1; dz++)
                if (dx % 4 == 0 && dz % 4 == 0) w.getBlockAt(cx + dx, cy + 4, cz + dz).setType(lightMat, false);

        if (c.getBoolean("door", true)) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 1; dy++)
                    w.getBlockAt(cx + dx, cy + dy, cz + half).setType(Material.AIR, false);
        }

        List<String> loot = c.getStringList("loot");
        double chance = c.getDouble("loot-fill-chance", 0.5);

        // 3) shulker box grid in the middle
        List<Material> boxes = Arrays.stream(Material.values())
                .filter(m -> m.name().endsWith("SHULKER_BOX") && !m.name().startsWith("LEGACY_"))
                .toList();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < shulkerCount; i++) {
            int x = cx - cols / 2 + (i % cols);
            int z = cz - rows / 2 + (i / cols);
            Block b = w.getBlockAt(x, cy, z);
            b.setType(boxes.get(rnd.nextInt(boxes.size())), false);
            fill(b, loot, chance);
        }

        // 4) ring of positions along the walls (no corners, skips the door)
        List<int[]> ring = new ArrayList<>();
        for (int dx = -r + 1; dx <= r - 1; dx += 2) ring.add(new int[]{cx + dx, cz - r});
        for (int dz = -r + 1; dz <= r - 1; dz += 2) ring.add(new int[]{cx + r, cz + dz});
        for (int dx = r - 1; dx >= -r + 1; dx -= 2) if (Math.abs(dx) > 2) ring.add(new int[]{cx + dx, cz + r});
        for (int dz = r - 1; dz >= -r + 1; dz -= 2) ring.add(new int[]{cx - r, cz + dz});
        int idx = 0;

        // cake spawners
        List<String> types = c.getStringList("spawner-types");
        if (types.isEmpty()) types = List.of("ZOMBIE");
        int spawnerCount = c.getInt("spawners", 6);
        for (int k = 0; k < spawnerCount && idx < ring.size(); k++, idx++) {
            int[] pos = ring.get(idx);
            placeSpawner(w.getBlockAt(pos[0], cy, pos[1]), types.get(k % types.size()));
            w.getBlockAt(pos[0], cy + 1, pos[1]).setType(Material.CAKE, false);
        }

        // loot chests
        int chestCount = c.getInt("chests", 8);
        for (int k = 0; k < chestCount && idx < ring.size(); k++, idx++) {
            int[] pos = ring.get(idx);
            Block b = w.getBlockAt(pos[0], cy, pos[1]);
            place(b, Material.CHEST, inward(pos[0], pos[1], cx, cz, r));
            fill(b, loot, chance);
        }

        // extra blocks
        for (String name : c.getStringList("extra-blocks")) {
            if (idx >= ring.size()) break;
            int[] pos = ring.get(idx++);
            place(w.getBlockAt(pos[0], cy, pos[1]), mat(name, Material.ENDER_CHEST),
                    inward(pos[0], pos[1], cx, cz, r));
        }

        // 5) corner pillars of valuable blocks
        List<String> corner = c.getStringList("corner-blocks");
        int[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int k = 0; k < 4 && !corner.isEmpty(); k++) {
            Material m = mat(corner.get(k % corner.size()), Material.DIAMOND_BLOCK);
            int x = cx + corners[k][0] * r, z = cz + corners[k][1] * r;
            w.getBlockAt(x, cy, z).setType(m, false);
            w.getBlockAt(x, cy + 1, z).setType(m, false);
        }

        Region region = new Region(nextId++, w.getUID(), cx, cy, cz, half, p.getName(), original);
        regions.put(region.id(), region);
        saveData();
        p.sendMessage(msg("Fake base #" + region.id() + " created (" + shulkerCount + " shulkers, "
                + spawnerCount + " cake spawners, " + chestCount + " chests). "
                + "Remove it with /fakebase remove " + region.id(), NamedTextColor.GREEN));
    }

    private void restore(Region r) {
        World w = Bukkit.getWorld(r.world());
        if (w == null) return;
        for (String e : r.original()) {
            int bar = e.indexOf('|');
            if (bar < 0) continue;
            String[] o = e.substring(0, bar).split(",");
            Block b = w.getBlockAt(r.cx() + Integer.parseInt(o[0]), r.cy() + Integer.parseInt(o[1]),
                    r.cz() + Integer.parseInt(o[2]));
            try {
                b.setBlockData(Bukkit.createBlockData(e.substring(bar + 1)), false);
            } catch (IllegalArgumentException ex) {
                b.setType(Material.AIR, false);
            }
        }
    }

    private void placeSpawner(Block b, String type) {
        b.setType(Material.SPAWNER, false);
        if (b.getState() instanceof CreatureSpawner cs) {
            EntityType et;
            try {
                et = EntityType.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                et = EntityType.PIG;
            }
            cs.setSpawnedType(et);
            cs.setRequiredPlayerRange(0);   // inactive: never spawns anything
            cs.setMaxNearbyEntities(0);
            cs.update(true, false);
        }
    }

    private void place(Block b, Material m, BlockFace facing) {
        BlockData bd = m.createBlockData();
        if (bd instanceof Directional d && d.getFaces().contains(facing)) d.setFacing(facing);
        b.setBlockData(bd, false);
    }

    private BlockFace inward(int x, int z, int cx, int cz, int r) {
        if (z == cz - r) return BlockFace.SOUTH;
        if (x == cx + r) return BlockFace.WEST;
        if (z == cz + r) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    private Material mat(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return (m != null && m.isBlock()) ? m : def;
    }

    private void fill(Block b, List<String> loot, double chance) {
        if (loot.isEmpty() || !(b.getState() instanceof Container ct)) return;
        Inventory inv = ct.getInventory();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < inv.getSize(); i++) {
            if (rnd.nextDouble() < chance) {
                ItemStack it = randomLoot(loot, rnd);
                if (it != null) inv.setItem(i, it);
            }
        }
    }

    private ItemStack randomLoot(List<String> loot, ThreadLocalRandom rnd) {
        try {
            String[] parts = loot.get(rnd.nextInt(loot.size())).split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || !m.isItem()) return null;
            int min = 1, max = 1;
            if (parts.length > 1) {
                String[] mm = parts[1].split("-");
                min = Integer.parseInt(mm[0].trim());
                max = mm.length > 1 ? Integer.parseInt(mm[1].trim()) : min;
            }
            int amt = rnd.nextInt(min, Math.max(min, max) + 1);
            return new ItemStack(m, Math.max(1, Math.min(amt, m.getMaxStackSize())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ detection

    private Region regionAt(Location l) {
        for (Region r : regions.values()) if (r.contains(l)) return r;
        return null;
    }

    private boolean isFake(Inventory inv) {
        Location l = inv == null ? null : inv.getLocation();
        return l != null && regionAt(l) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (regions.isEmpty() || !getConfig().getBoolean("alert.enter", true)) return;
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY()
                && to.getBlockZ() == from.getBlockZ()) return;
        Region rt = regionAt(to);
        if (rt == null) return;
        Region rf = regionAt(from);
        if (rf != null && rf.id() == rt.id()) return;
        Player p = e.getPlayer();
        if (p.hasPermission("fakebase.bypass")) return;
        alert(p, rt, "entered", "enter");
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (regions.isEmpty() || !(e.getPlayer() instanceof Player p)) return;
        Location l = e.getInventory().getLocation();
        if (l == null) return;
        Region r = regionAt(l);
        if (r == null || p.hasPermission("fakebase.bypass")) return;
        if (getConfig().getBoolean("alert.open", true)) alert(p, r, "opened a container", "open");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (regions.isEmpty() || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CAKE) return;
        Region r = regionAt(b.getLocation());
        Player p = e.getPlayer();
        if (r == null || p.hasPermission("fakebase.bypass")) return;
        e.setCancelled(true);
        if (getConfig().getBoolean("alert.open", true)) alert(p, r, "tried to eat the cake", "cake");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (regions.isEmpty()) return;
        Region r = regionAt(e.getBlock().getLocation());
        Player p = e.getPlayer();
        if (r == null || p.hasPermission("fakebase.bypass")) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        if (getConfig().getBoolean("alert.break", true))
            alert(p, r, "broke " + e.getBlock().getType().name().toLowerCase(Locale.ROOT), "break");
    }

    // Players can look inside fake containers but can never take (or put) anything.
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!regions.isEmpty() && isFake(e.getView().getTopInventory())
                && !e.getWhoClicked().hasPermission("fakebase.bypass")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!regions.isEmpty() && isFake(e.getView().getTopInventory())
                && !e.getWhoClicked().hasPermission("fakebase.bypass")) e.setCancelled(true);
    }

    // Stops hoppers from siphoning bait loot out.
    @EventHandler(ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent e) {
        if (regions.isEmpty()) return;
        if (isFake(e.getSource()) || isFake(e.getDestination())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (regions.isEmpty()) return;
        e.blockList().removeIf(b -> regionAt(b.getLocation()) != null);
    }

    private void alert(Player p, Region r, String what, String key) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("alert-cooldown-seconds", 10) * 1000L;
        String ck = p.getUniqueId() + ":" + r.id() + ":" + key;
        Long last = cooldowns.get(ck);
        if (last != null && now - last < cd) return;
        cooldowns.put(ck, now);

        Location l = p.getLocation();
        String line = p.getName() + " " + what + " in fake base #" + r.id()
                + " (" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + ")";
        Component out = PREFIX
                .append(Component.text(line, NamedTextColor.RED))
                .append(Component.text(" [TP]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/tp " + p.getName())));
        for (Player staff : Bukkit.getOnlinePlayers())
            if (staff.hasPermission("fakebase.alert")) staff.sendMessage(out);
        getLogger().warning(line);
        logToFile(line);

        List<String> cmds = getConfig().getStringList("on-trigger-commands");
        if (!cmds.isEmpty() && punished.add(p.getUniqueId() + ":" + r.id()))
            for (String cmd : cmds)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
    }

    private void logToFile(String line) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(getDataFolder(), "catches.log"), true))) {
            pw.println("[" + LocalDateTime.now() + "] " + line);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private Component msg(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(text, color));
    }
}
