package kz.bejiihiu.arinaLOXSHLUXAMEGAFIX;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class AttributeGuardListener implements Listener {
    private static final long RAPID_SWAP_WINDOW_TICKS = 1L;
    private static final double MAX_ATTACK_DAMAGE_MODIFIER = 2048.0;
    private static final double MAX_ATTACK_SPEED_MODIFIER = 32.0;

    private static final Set<Attribute> HAND_RESTRICTED_ATTRIBUTES = Set.of(
            Attribute.ATTACK_DAMAGE,
            Attribute.ATTACK_SPEED,
            Attribute.BLOCK_BREAK_SPEED
    );

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerGuardState> states = new ConcurrentHashMap<>();

    public AttributeGuardListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerGuardState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerGuardState());
        state.refreshSnapshot(player.getInventory());
        state.lastSwapTick = getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        PlayerGuardState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerGuardState());

        long tick = getCurrentTick();
        boolean rapidSwap = tick - state.lastSwapTick <= RAPID_SWAP_WINDOW_TICKS;

        ItemStack previousSnapshot = state.getSnapshot(event.getPreviousSlot());
        ItemStack newSnapshot = state.getSnapshot(event.getNewSlot());

        if (rapidSwap) {
            scheduleDelayed(player, task -> {
                enforceSnapshot(player, event.getPreviousSlot(), previousSnapshot, "rapid-swap previous-slot rollback");
                enforceSnapshot(player, event.getNewSlot(), newSnapshot, "rapid-swap new-slot rollback");
            });
        }

        state.lastSwapTick = tick;
        scheduleDelayed(player, task -> validateAndSnapshot(player, "held-slot-change"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PlayerGuardState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerGuardState());

        long tick = getCurrentTick();
        if (tick - state.lastSwapTick <= RAPID_SWAP_WINDOW_TICKS) {
            ItemStack offhandSnapshot = state.offhandSnapshot == null ? null : state.offhandSnapshot.clone();
            scheduleDelayed(player, task -> enforceOffhandSnapshot(player, offhandSnapshot));
        }

        state.lastSwapTick = tick;
        scheduleDelayed(player, task -> validateAndSnapshot(player, "swap-hands"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!isRelevantClick(event)) {
            return;
        }

        scheduleDelayed(player, task -> validateAndSnapshot(player, "inventory-click:" + event.getAction()));
    }

    private boolean isRelevantClick(InventoryClickEvent event) {
        if (event.getHotbarButton() >= 0) {
            return true;
        }

        InventoryAction action = event.getAction();
        return switch (action) {
            case HOTBAR_SWAP,
                 MOVE_TO_OTHER_INVENTORY,
                 PICKUP_ALL,
                 PICKUP_HALF,
                 PICKUP_SOME,
                 PICKUP_ONE,
                 PLACE_ALL,
                 PLACE_SOME,
                 PLACE_ONE,
                 SWAP_WITH_CURSOR,
                 COLLECT_TO_CURSOR,
                 UNKNOWN -> true;
            default -> false;
        };
    }

    private void validateAndSnapshot(Player player, String source) {
        if (!player.isOnline()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        PlayerGuardState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerGuardState());

        sanitizeMainHand(player, inventory, source);
        sanitizeOffhand(player, inventory, source);
        sanitizeHotbar(player, inventory, source);

        state.refreshSnapshot(inventory);
    }

    private void sanitizeMainHand(Player player, PlayerInventory inventory, String source) {
        int slot = inventory.getHeldItemSlot();
        ItemStack stack = inventory.getItem(slot);
        ItemStack sanitized = sanitizeItem(stack, player, source + " main-hand");
        if (sanitized != stack) {
            inventory.setItem(slot, sanitized);
        }
    }

    private void sanitizeOffhand(Player player, PlayerInventory inventory, String source) {
        ItemStack stack = inventory.getItemInOffHand();
        ItemStack sanitized = sanitizeItem(stack, player, source + " off-hand");
        if (sanitized != stack) {
            inventory.setItemInOffHand(sanitized);
        }
    }

    private void sanitizeHotbar(Player player, PlayerInventory inventory, String source) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            ItemStack sanitized = sanitizeItem(stack, player, source + " hotbar[" + slot + "]");
            if (sanitized != stack) {
                inventory.setItem(slot, sanitized);
            }
        }
    }

    private void enforceSnapshot(Player player, int slot, ItemStack snapshot, String source) {
        if (!player.isOnline()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(slot);

        if (snapshot == null && isEmpty(current)) {
            return;
        }

        if (matchesSnapshot(current, snapshot)) {
            inventory.setItem(slot, snapshot == null ? null : snapshot.clone());
            logRollback(player, source, slot, current, snapshot);
        }
    }

    private void enforceOffhandSnapshot(Player player, ItemStack snapshot) {
        if (!player.isOnline()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItemInOffHand();
        if (snapshot == null && isEmpty(current)) {
            return;
        }

        if (matchesSnapshot(current, snapshot)) {
            inventory.setItemInOffHand(snapshot == null ? null : snapshot.clone());
            logRollback(player, "rapid-swap offhand rollback", -1, current, snapshot);
        }
    }

    private boolean matchesSnapshot(ItemStack current, ItemStack snapshot) {
        if (isEmpty(current) && snapshot == null) {
            return false;
        }
        if (snapshot == null || current == null) {
            return true;
        }
        return !current.equals(snapshot);
    }

    private ItemStack sanitizeItem(ItemStack original, Player player, String source) {
        if (isEmpty(original)) {
            return original;
        }

        ItemStack sanitized = original.clone();
        ItemMeta meta = sanitized.getItemMeta();
        if (meta == null) {
            return original;
        }

        List<String> anomalies = new ArrayList<>();
        sanitizeEnchantments(sanitized, meta, anomalies);
        sanitizeAttributeModifiers(sanitized, meta, anomalies);
        sanitizeDamage(sanitized, meta, anomalies);

        if (anomalies.isEmpty()) {
            return original;
        }

        sanitized.setItemMeta(meta);
        logAnomaly(player, source, original, sanitized, anomalies);
        return sanitized;
    }

    private void sanitizeEnchantments(ItemStack stack, ItemMeta meta, List<String> anomalies) {
        Map<Enchantment, Integer> enchantments = new HashMap<>(meta.getEnchants());
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (!enchantment.canEnchantItem(stack)) {
                meta.removeEnchant(enchantment);
                anomalies.add("Removed incompatible enchantment " + enchantment.getKey());
            }
        }
    }

    private void sanitizeAttributeModifiers(ItemStack stack, ItemMeta meta, List<String> anomalies) {
        if (meta.getAttributeModifiers() == null) {
            return;
        }

        Material material = stack.getType();
        for (Attribute attribute : meta.getAttributeModifiers().keySet()) {
            assert attribute != null;
            for (AttributeModifier modifier : List.copyOf(Objects.requireNonNull(meta.getAttributeModifiers(attribute)))) {
                if (isHandRelated(modifier) && !isAttributeAllowedForMaterial(material, attribute)) {
                    meta.removeAttributeModifier(attribute, modifier);
                    anomalies.add("Removed illegal attribute " + attribute + " for material " + material);
                    continue;
                }

                if (attribute == Attribute.ATTACK_DAMAGE && Math.abs(modifier.getAmount()) > MAX_ATTACK_DAMAGE_MODIFIER) {
                    meta.removeAttributeModifier(attribute, modifier);
                    anomalies.add("Removed excessive ATTACK_DAMAGE modifier=" + modifier.getAmount());
                    continue;
                }

                if (attribute == Attribute.ATTACK_SPEED && Math.abs(modifier.getAmount()) > MAX_ATTACK_SPEED_MODIFIER) {
                    meta.removeAttributeModifier(attribute, modifier);
                    anomalies.add("Removed excessive ATTACK_SPEED modifier=" + modifier.getAmount());
                }
            }
        }
    }

    private void sanitizeDamage(ItemStack stack, ItemMeta meta, List<String> anomalies) {
        if (!(meta instanceof Damageable damageable)) {
            return;
        }

        short maxDurability = stack.getType().getMaxDurability();
        if (maxDurability <= 0) {
            return;
        }

        if (damageable.getDamage() < 0) {
            damageable.setDamage(0);
            anomalies.add("Fixed negative durability damage");
            return;
        }

        if (damageable.getDamage() > maxDurability) {
            damageable.setDamage(maxDurability);
            anomalies.add("Clamped durability damage to max=" + maxDurability);
        }
    }

    private boolean isAttributeAllowedForMaterial(Material material, Attribute attribute) {
        if (!HAND_RESTRICTED_ATTRIBUTES.contains(attribute)) {
            return true;
        }
        return isCombatOrToolItem(material);
    }

    private boolean isCombatOrToolItem(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_MACE")
                || name.endsWith("_TRIDENT");
    }

    private boolean isHandRelated(AttributeModifier modifier) {
        EquipmentSlotGroup group = modifier.getSlotGroup();
        return group == EquipmentSlotGroup.HAND || group == EquipmentSlotGroup.MAINHAND;
    }

    private boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }

    private void logAnomaly(Player player, String source, ItemStack original, ItemStack sanitized, List<String> anomalies) {
        long tick = getCurrentTick();
        String region = player.getWorld().getName() + "@" + player.getLocation().getChunk().getX() + "," + player.getLocation().getChunk().getZ();

        plugin.getLogger().warning("[AttributeGuard] Invalid attribute transfer prevented"
                + " | Player=" + player.getName()
                + " | Source=" + source
                + " | From=" + original.getType()
                + " | To=" + sanitized.getType()
                + " | Tick=" + tick
                + " | Region=" + region
                + " | Anomalies=" + anomalies);
    }

    private void logRollback(Player player, String source, int slot, ItemStack current, ItemStack snapshot) {
        long tick = getCurrentTick();
        String region = player.getWorld().getName() + "@" + player.getLocation().getChunk().getX() + "," + player.getLocation().getChunk().getZ();
        String currentType = isEmpty(current) ? "AIR" : current.getType().name();
        String snapshotType = snapshot == null ? "AIR" : snapshot.getType().name();

        plugin.getLogger().warning("[AttributeGuard] Rapid swap rollback applied"
                + " | Player=" + player.getName()
                + " | Source=" + source
                + " | Slot=" + slot
                + " | Current=" + currentType
                + " | Snapshot=" + snapshotType
                + " | Tick=" + tick
                + " | Region=" + region);
    }

    private void scheduleDelayed(Player player, Consumer<ScheduledTask> consumer) {
        player.getScheduler().runDelayed(plugin, consumer, null, 1L);
    }

    private long getCurrentTick() {
        return Bukkit.getCurrentTick();
    }

    private static final class PlayerGuardState {
        private final ItemStack[] hotbarSnapshot = new ItemStack[9];
        private ItemStack offhandSnapshot;
        private long lastSwapTick = -100L;

        private void refreshSnapshot(PlayerInventory inventory) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inventory.getItem(i);
                hotbarSnapshot[i] = stack == null ? null : stack.clone();
            }
            ItemStack offhand = inventory.getItemInOffHand();
            offhandSnapshot = offhand.clone();
        }

        private ItemStack getSnapshot(int slot) {
            if (slot < 0 || slot >= hotbarSnapshot.length) {
                return null;
            }
            return hotbarSnapshot[slot] == null ? null : hotbarSnapshot[slot].clone();
        }
    }
}
