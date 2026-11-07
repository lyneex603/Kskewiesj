package dev.totemspheres;

import dev.totemspheres.ItemManager.EffectDef;
import dev.totemspheres.ItemManager.ItemDef;
import dev.totemspheres.ItemManager.Stats;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TotemSpheresPlugin extends JavaPlugin implements Listener, TabExecutor {

    private ItemManager items;
    private final Map<UUID, Set<PotionEffectType>> applied = new HashMap<>();
    private final Map<String, NamespacedKey> statKeys = new HashMap<>();
    private int counter = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        items = new ItemManager(this);
        items.load();
        for (String n : List.of("damage", "health", "armor", "toughness", "speed")) {
            statKeys.put(n, new NamespacedKey(this, "stat_" + n));
        }
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("totemspheres");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 5L, 5L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<PotionEffectType> set = applied.get(p.getUniqueId());
            if (set != null) set.forEach(p::removePotionEffect);
            applyStats(p, Stats.ZERO);
        }
        applied.clear();
    }

    /** Каждые 0.25 сек: характеристики; раз в секунду: эффекты зелий. */
    private void tick() {
        boolean doEffects = (counter++ % 4) == 0;
        int duration = getConfig().getInt("effect-duration-ticks", 260);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            ItemDef def = items.fromStack(p.getInventory().getItemInOffHand());
            applyStats(p, def == null ? Stats.ZERO : def.stats());
            if (!doEffects) continue;

            Set<PotionEffectType> now = new HashSet<>();
            if (def != null) {
                for (EffectDef e : def.effects()) {
                    p.addPotionEffect(new PotionEffect(e.type(), duration, e.amplifier(), true, false, true));
                    now.add(e.type());
                }
            }
            Set<PotionEffectType> prev = applied.get(id);
            if (prev != null) {
                for (PotionEffectType t : prev) {
                    if (!now.contains(t)) p.removePotionEffect(t);
                }
            }
            if (now.isEmpty()) applied.remove(id);
            else applied.put(id, now);
        }
    }

    private void applyStats(Player p, Stats s) {
        setMod(p, Attribute.ATTACK_DAMAGE, "damage", s.damage(), AttributeModifier.Operation.ADD_NUMBER);
        setMod(p, Attribute.MAX_HEALTH, "health", s.health(), AttributeModifier.Operation.ADD_NUMBER);
        setMod(p, Attribute.ARMOR, "armor", s.armor(), AttributeModifier.Operation.ADD_NUMBER);
        setMod(p, Attribute.ARMOR_TOUGHNESS, "toughness", s.toughness(), AttributeModifier.Operation.ADD_NUMBER);
        setMod(p, Attribute.MOVEMENT_SPEED, "speed", s.speed() / 100.0, AttributeModifier.Operation.ADD_SCALAR);
    }

    private void setMod(Player p, Attribute attr, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        NamespacedKey key = statKeys.get(name);
        AttributeModifier existing = null;
        for (AttributeModifier m : inst.getModifiers()) {
            if (key.equals(m.getKey())) {
                existing = m;
                break;
            }
        }
        if (existing != null) {
            if (existing.getAmount() == amount && existing.getOperation() == op) return;
            inst.removeModifier(existing);
        } else if (amount == 0) {
            return;
        }
        if (amount != 0) {
            inst.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
        }
        if (attr == Attribute.MAX_HEALTH) {
            double max = inst.getValue();
            if (p.getHealth() > max) p.setHealth(max);
        }
    }

    /** Срабатывание тотема при смерти: ванильное воскрешение + доп. эффекты. */
    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        ItemStack stack = hand == EquipmentSlot.OFF_HAND
                ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();
        ItemDef def = items.fromStack(stack);
        if (def == null || def.kind() != ItemManager.Kind.TOTEM) return;

        applied.remove(p.getUniqueId()); // чтобы tick() не снял эффекты воскрешения
        if (def.deathEffects().isEmpty()) return;
        Bukkit.getScheduler().runTask(this, () -> {
            for (EffectDef fx : def.deathEffects()) {
                p.addPotionEffect(new PotionEffect(fx.type(), fx.seconds() * 20, fx.amplifier()));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        applied.remove(e.getPlayer().getUniqueId());
        applyStats(e.getPlayer(), Stats.ZERO);
    }

    // ---------- Команды ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendRichMessage("<yellow>/ts give <игрок> <id> [кол-во]</yellow> | <yellow>/ts list</yellow> | <yellow>/ts reload</yellow>");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                items.load();
                s.sendRichMessage("<green>Конфиг перезагружен. Предметов: " + items.all().size());
            }
            case "list" -> {
                s.sendRichMessage("<gold>Предметы:");
                for (ItemDef d : items.all()) {
                    s.sendRichMessage("<gray> - </gray><white>" + d.id() + "</white> <dark_gray>("
                            + (d.kind() == ItemManager.Kind.SPHERE ? "сфера" : "тотем") + ")");
                }
            }
            case "give" -> {
                if (a.length < 3) {
                    s.sendRichMessage("<red>Использование: /ts give <игрок> <id> [кол-во]");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) {
                    s.sendRichMessage("<red>Игрок не найден.");
                    return true;
                }
                ItemDef def = items.get(a[2]);
                if (def == null) {
                    s.sendRichMessage("<red>Нет такого предмета. Смотрите /ts list");
                    return true;
                }
                int amount = 1;
                if (a.length > 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(a[3])));
                    } catch (NumberFormatException ex) {
                        s.sendRichMessage("<red>Количество должно быть числом.");
                        return true;
                    }
                }
                final int amt = amount;
                target.getInventory().addItem(items.create(def, amt))
                        .values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                s.sendRichMessage("<green>Выдано " + amt + "x " + def.id() + " игроку " + target.getName());
            }
            default -> s.sendRichMessage("<red>Неизвестная подкоманда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("give", "list", "reload")) {
                if (o.startsWith(a[0].toLowerCase())) out.add(o);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            for (ItemDef d : items.all()) {
                if (d.id().startsWith(a[2].toLowerCase())) out.add(d.id());
            }
        }
        return out;
    }
  }
