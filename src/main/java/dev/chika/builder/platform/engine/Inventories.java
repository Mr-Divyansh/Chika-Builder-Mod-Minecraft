package dev.chika.builder.platform.engine;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Helpers for the player's whole inventory, including the parts that do not
 * live in the hotbar or the main rows.
 *
 * <p>{@code Inventory.getContainerSize()} here is 43, not 36: container
 * indices 0-35 are the hotbar/main storage ({@code INVENTORY_SIZE = 36}),
 * 36-39 are the four armour slots, 40 is the off-hand
 * ({@code SLOT_OFFHAND = 40}), 41 is body armour and 42 is the saddle, all
 * confirmed in the game's own bytecode. That is why a loop capped at 36
 * silently misses an item sitting in the off-hand. {@link #storageStacks} copies the player-storage
 * range (hotbar, main rows and off-hand), so that is what
 * {@link MinecraftPlayerContext} counts against.
 */
final class Inventories {

    /**
     * The container indices that hold ordinary player storage: the 36
     * hotbar/main slots plus the off-hand (container 40). Armour (36-39), body
     * armour (41) and the saddle (42) are equipment, not storage, so they are
     * never counted and never written to.
     */
    static final int MENU_SLOT_MIN = 9;
    static final int MENU_SLOT_MAX = 45;

    /** Container index of the off-hand inside {@code Inventory} (confirmed as 40 in bytecode). */
    static final int OFFHAND_CONTAINER_INDEX = 40;

    private Inventories() {
    }

    /**
     * A copy of the player's storage stacks: the 36 hotbar/main stacks plus
     * the off-hand.
     *
     * <p>Container indices come from {@code Inventory} itself
     * ({@code INVENTORY_SIZE = 36}, {@code SLOT_OFFHAND = 40} in bytecode):
     * 0-35 are the hotbar and main rows and 40 is the off-hand. Indices
     * 36-39/41-42 are equipment (armour, body armour, saddle) and are skipped
     * because they are worn items, not storage.
     */
    static NonNullList<ItemStack> storageStacks(Inventory inventory) {
        NonNullList<ItemStack> stacks = NonNullList.create();

        if (inventory == null) {
            return stacks;
        }

        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            stacks.add(stack == null ? ItemStack.EMPTY : stack);
        }

        ItemStack offhand = inventory.getItem(OFFHAND_CONTAINER_INDEX);
        stacks.add(offhand == null ? ItemStack.EMPTY : offhand);

        return stacks;
    }

    /**
     * Maps a container index on {@code Inventory} back to the matching
     * {@code InventoryMenu} slot.
     *
     * <p>{@code InventoryMenu} builds its slots as: 0 = crafting result,
     * 1-4 = crafting grid, 5-8 = armour, 9-35 = the three rows of the main
     * inventory (container indices 9-35), 36-44 = the nine hotbar slots
     * (container 0-8), and 45 = the off-hand. Equipment (container 36-39,
     * 41-42) has no ordinary menu slot, which is why this returns {@code -1}
     * for it.
     *
     * @return the menu slot, or {@code -1} when the index has no writable
     *         storage slot
     */
    static int menuSlotOfContainerIndex(int containerIndex) {
        if (containerIndex == OFFHAND_CONTAINER_INDEX) {
            // The off-hand is menu slot 45 (InventoryMenu.SHIELD_SLOT).
            return 45;
        }
        if (containerIndex < 0) {
            return -1;
        }

        if (containerIndex < 9) {
            // Hotbar: container 0-8 -> menu 36-44.
            return 36 + containerIndex;
        }

        if (containerIndex < 36) {
            // Main inventory rows: container 9-35 -> menu 9-35.
            return containerIndex;
        }

        // Armour (36-39), body armour (41) and saddle (42) are equipment, not
        // storage: no Creative menu slot exists for them, so Creative blocks
        // must never be written there. Returning -1 keeps both fill passes
        // and the menu synchronisation from ever touching them.
        return -1;
    }

    /** True when {@code containerIndex} is a writable player-storage slot. */
    static boolean isWritableStorageContainerIndex(int containerIndex) {
        return menuSlotOfContainerIndex(containerIndex) >= 0;
    }

    /**
     * True when {@code menuSlot} is a player-storage slot of
     * {@code InventoryMenu} that may hold a Creative hand-over: the main rows
     * (9-35), the hotbar (36-44), or the off-hand (45).
     *
     * <p>Storage only: {@code menuSlotOfContainerIndex} never maps equipment
     * (container 36-39/41-42) to one of these, so this can stay a pure range
     * check.
     */
    static boolean isStorageMenuSlot(int menuSlot) {
        return menuSlot >= MENU_SLOT_MIN && menuSlot <= MENU_SLOT_MAX;
    }
}