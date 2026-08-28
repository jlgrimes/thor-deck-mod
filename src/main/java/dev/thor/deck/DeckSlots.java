package dev.thor.deck;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Canonical deck slot indices. These match the pre-1.21.5 combined inventory
 * (the numbers the Amethyst launcher already uses) even though vanilla now
 * stores armor/offhand on {@link net.minecraft.world.entity.EntityEquipment}.
 *
 * <pre>
 *  0-8   hotbar
 *  9-35  main inventory (3x9)
 *  36    boots   (EquipmentSlot.FEET)
 *  37    leggings (EquipmentSlot.LEGS)
 *  38    chest    (EquipmentSlot.CHEST)
 *  39    helmet   (EquipmentSlot.HEAD)
 *  40    offhand  (EquipmentSlot.OFFHAND)
 * </pre>
 */
public final class DeckSlots {
    public static final int HOTBAR = 9;
    public static final int MAIN = 36;
    public static final int BOOTS = 36;
    public static final int LEGGINGS = 37;
    public static final int CHEST = 38;
    public static final int HELMET = 39;
    public static final int OFFHAND = 40;
    /** Hotbar + main + 4 armor + offhand. Launcher contract. */
    public static final int SIZE = 41;

    private DeckSlots() {}

    public static boolean valid(int i) {
        return i >= 0 && i < SIZE;
    }

    public static EquipmentSlot equipment(int i) {
        EquipmentSlot mapped = Inventory.EQUIPMENT_SLOT_MAPPING.get(i);
        if (mapped != null) {
            return mapped;
        }
        return switch (i) {
            case BOOTS -> EquipmentSlot.FEET;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case CHEST -> EquipmentSlot.CHEST;
            case HELMET -> EquipmentSlot.HEAD;
            case OFFHAND -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    public static ItemStack get(Inventory inv, Player player, int i) {
        if (i < 0 || i >= SIZE) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack s = inv.getItem(i);
            if (s != null && (i < MAIN || !s.isEmpty())) {
                return s;
            }
        } catch (Exception ignored) {
            // getContainerSize() may be 36 after the equipment split
        }
        EquipmentSlot eq = equipment(i);
        if (eq != null) {
            return player.getItemBySlot(eq);
        }
        return ItemStack.EMPTY;
    }

    public static void set(Inventory inv, Player player, int i, ItemStack stack) {
        if (i < 0 || i >= SIZE) {
            return;
        }
        try {
            inv.setItem(i, stack);
            return;
        } catch (Exception ignored) {
        }
        EquipmentSlot eq = equipment(i);
        if (eq != null) {
            player.setItemSlot(eq, stack);
        }
    }

    /**
     * InventoryMenu slot index for a deck slot, used with
     * {@code MultiPlayerGameMode.handleInventoryMouseClick} so the server
     * accepts the move. Mapping:
     * <pre>
     *   inv 0-8   (hotbar)  -> menu 36-44
     *   inv 9-35  (main)    -> menu 9-35
     *   inv 36    (boots)   -> menu 8
     *   inv 37    (legs)    -> menu 7
     *   inv 38    (chest)   -> menu 6
     *   inv 39    (helmet)  -> menu 5
     *   inv 40    (offhand) -> menu 45
     * </pre>
     */
    public static int toMenuSlot(int i) {
        if (i >= 0 && i < HOTBAR) {
            return 36 + i;
        }
        if (i >= HOTBAR && i < MAIN) {
            return i;
        }
        return switch (i) {
            case BOOTS -> 8;
            case LEGGINGS -> 7;
            case CHEST -> 6;
            case HELMET -> 5;
            case OFFHAND -> 45;
            default -> -1;
        };
    }

    /**
     * Swap {@code from} and {@code to}. Prefers a real container click (so the
     * change survives the next server sync) when the player's inventory menu
     * is the current container; otherwise mutates via {@link Inventory#setItem}.
     */
    public static void swap(Minecraft client, LocalPlayer player, int from, int to) {
        if (from == to || !valid(from) || !valid(to)) {
            return;
        }
        Inventory inv = player.getInventory();
        MultiPlayerGameMode gm = client.gameMode;
        if (gm != null && player.containerMenu == player.inventoryMenu) {
            int menuFrom = toMenuSlot(from);
            int menuTo = toMenuSlot(to);
            if (menuFrom >= 0 && menuTo >= 0) {
                int id = player.inventoryMenu.containerId;
                gm.handleInventoryMouseClick(id, menuFrom, 0, ClickType.PICKUP, player);
                gm.handleInventoryMouseClick(id, menuTo, 0, ClickType.PICKUP, player);
                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    gm.handleInventoryMouseClick(id, menuFrom, 0, ClickType.PICKUP, player);
                }
                return;
            }
        }
        ItemStack a = get(inv, player, from).copy();
        ItemStack b = get(inv, player, to).copy();
        set(inv, player, from, b);
        set(inv, player, to, a);
    }
}
