package dev.totemspheres;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ItemManager {

    public enum Kind { SPHERE, TOTEM }

    public record EffectDef(PotionEffectType type, int amplifier, int seconds) {}

    /** speed - в процентах (15 = +15%), health - в HP. */
    public record Stats(double damage, double health, double armor, double toughness, double speed) {
        public static final Stats ZERO = new Stats(0, 0, 0, 0, 0);
    }

    public record ItemDef(String id, Kind kind, Material material, String name, List<String> lore,
                          String itemModel, Stats stats,
                          List<EffectDef> effects, List<EffectDef> deathEffects) {}

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, ItemDef> defs = new LinkedHashMap<>();

    public ItemManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "item_id");
    }

    public void load() {
        plugin.reloadConfig();
        defs.clear();
        FileConfiguration cfg = plugin.getConfig();
        loadSection(cfg.getConfigurationSection("spheres"), Kind.SPHERE);
        loadSection(cfg.getConfigurationSection("totems"), Kind.TOTEM);
        plugin.getLogger().info("Загружено предметов: " + defs.size());
    }

    private void loadSection(ConfigurationSection sec, Kind kind) {
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Material mat = Material.matchMaterial(s.getString("material", "HEART_OF_THE_SEA"));
            if (mat == null || mat.isAir()) {
                plugin.getLogger().warning("[" + id + "] неизвестный материал, пропуск");
                continue;
            }
            ConfigurationSection st = s.getConfigurationSection("stats");
            Stats stats = st == null ? Stats.ZERO : new Stats(
                    st.getDouble("damage"), st.getDouble("health"), st.getDouble("armor"),
                    st.getDouble("toughness"), st.getDouble("speed"));
            String lid = id.toLowerCase();
            defs.put(lid, new ItemDef(lid, kind, mat, s.getString("name", id), s.getStringList("lore"),
                    s.getString("item-model", ""), stats,
                    parseEffects(id, s.getStringList("effects"), 0),
                    parseEffects(id, s.getStringList("death-effects"), 30)));
        }
    }

    private List<EffectDef> parseEffects(String owner, List<String> raw, int defaultSeconds) {
        List<EffectDef> out = new ArrayList<>();
        for (String line : raw) {
            try {
                String[] p = line.trim().toLowerCase().split(":");
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(p[0]));
                if (type == null) {
                    plugin.getLogger().warning("[" + owner + "] неизвестный эффект: " + line);
                    continue;
                }
                int level = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                int seconds = p.length > 2 ? Integer.parseInt(p[2]) : defaultSeconds;
                out.add(new EffectDef(type, Math.max(0, level - 1), seconds));
            } catch (Exception ex) {
                plugin.getLogger().warning("[" + owner + "] неверный формат эффекта: " + line);
            }
        }
        return out;
    }

    public ItemDef get(String id) {
        return id == null ? null : defs.get(id.toLowerCase());
    }

    public Collection<ItemDef> all() {
        return defs.values();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) Math.abs(v)) : String.valueOf(Math.abs(v));
    }

    private String statLine(double v, String suffix) {
        String color = v > 0 ? "<green>" : "<red>";
        return color + (v > 0 ? "+" : "-") + fmt(v) + " " + suffix;
    }

    private List<String> statLines(Stats s) {
        List<String> l = new ArrayList<>();
        if (s.damage() != 0) l.add(statLine(s.damage(), "к урону"));
        if (s.speed() != 0) l.add(statLine(s.speed(), "% к скорости").replace(" %", "%"));
        if (s.health() != 0) l.add(statLine(s.health(), "к макс. здоровью"));
        if (s.armor() != 0) l.add(statLine(s.armor(), "к броне"));
        if (s.toughness() != 0) l.add(statLine(s.toughness(), "к прочности брони"));
        return l;
    }

    public ItemStack create(ItemDef d, int amount) {
        ItemStack stack = new ItemStack(d.material(), amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(mm.deserialize(d.name()).decoration(TextDecoration.ITALIC, false));
        List<String> lines = new ArrayList<>();
        if (plugin.getConfig().getBoolean("auto-lore", true)) lines.addAll(statLines(d.stats()));
        lines.addAll(d.lore());
        List<Component> lore = new ArrayList<>();
        for (String l : lines) lore.add(mm.deserialize(l).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (!d.itemModel().isBlank()) {
            NamespacedKey model = NamespacedKey.fromString(d.itemModel());
            if (model != null) meta.setItemModel(model);
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, d.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /** Описание предмета, если это наша сфера/талисман, иначе null. */
    public ItemDef fromStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        String id = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? null : defs.get(id);
    }
}
