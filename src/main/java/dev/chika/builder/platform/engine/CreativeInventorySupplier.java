package dev.chika.builder.platform.engine;

import dev.chika.builder.build.material.CreativeSupplier;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;

/**
 * Supplies blocks from Creative mode using the game's own Creative mechanics.
 *
 * <p><b>How it works - and why it is not an exploit.</b> When a block is taken
 * from the Creative inventory the vanilla client writes the stack into the
 * player's own inventory slot and calls
 * {@code MultiPlayerGameMode.handleCreativeModeItemAdd(stack, slot)}, which
 * sends the ordinary {@code ServerboundSetCreativeModeSlotPacket}. That is the
 * exact sequence reproduced here for one block type: fill a free slot, tell the
 * game, then confirm the items really arrived.
 *
 * <p><b>Safety properties, all enforced in code:</b>
 * <ul>
 *   <li>{@link #isAvailable()} is {@code false} unless the player is
 *       <em>genuinely</em> holding a Creative-capable inventory
 *       ({@code hasInfiniteMaterials()}). The {@code #chika_builder creative}
 *       setting is never consulted here and is never a substitute for it.</li>
 *   <li>The chat command {@code /gamemode} is never sent and
 *       {@code setLocalMode} is never called, so the player's game mode is
 *       unchanged by construction.</li>
 *   <li>No packet is constructed or sent by hand - the vanilla client method is
 *       called, so any server-side validation still applies.</li>
 *   <li>The item is re-counted afterwards and the real number is returned. If
 *       nothing arrived, the caller sees {@code 0} and pauses the build.</li>
 *   <li>A gamemode change mid-session is picked up on the next call, because the
 *       check happens immediately before every hand-over.</li>
 * </ul>
 */
public final class CreativeInventorySupplier implements CreativeSupplier {

    /** Never hand over a fill of a whole stack of items we do not recognise. */
    @Override
    public boolean isAvailable() {
        try {
            net.minecraft.client.player.LocalPlayer player =
                    net.minecraft.client.Minecraft.getInstance().player;

            if (player == null) {
                return false;
            }

            // The game's own test: infinite materials == Creative or a spectator-
            // like mode that may take blocks. Read only; never written.
            return player.hasInfiniteMaterials();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int grant(String itemId, int amount) {
        if (amount <= 0) {
            return 0;
        }

        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();

            if (minecraft.player == null) {
                return 0;
            }

            // Re-check right before acting: the setting alone must never be
            // enough, and the player may have left Creative since the plan.
            if (!minecraft.player.hasInfiniteMaterials()) {
                return 0;
            }

            ItemStack stack = fullyStacked(itemId, amount);

            if (stack.isEmpty()) {
                return 0;
            }

            int before = MinecraftPlayerContext.countOf(minecraft.player, itemId);

            this.fill(minecraft, stack, itemId);

            int after = MinecraftPlayerContext.countOf(minecraft.player, itemId);
            return Math.max(0, after - before);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Writes the stack into free storage slots and mirrors each write to the
     * server, exactly like the Creative inventory does.
     */
    private void fill(net.minecraft.client.Minecraft minecraft, ItemStack stack, String itemId) {
        net.minecraft.world.entity.player.Inventory inventory = minecraft.player.getInventory();
        int max = Math.max(1, stack.getMaxStackSize());
        int remaining = stack.getCount();

        // Any slot already holding the same block is filled first. Only the
        // 36 storage rows: that is exactly what the engine scans (see
        // Inventories.isStorageContainerIndex).
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            if (!Inventories.isStorageContainerIndex(slot)) {
                continue;
            }
            ItemStack existing = inventory.getItem(slot);

            if (existing == null || existing.isEmpty() || !ItemStack.isSameItem(existing, stack)) {
                continue;
            }

            remaining -= addTo(minecraft, inventory, slot, existing, remaining, max);
        }

        // Then entirely empty slots.
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            if (!Inventories.isStorageContainerIndex(slot)) {
                continue;
            }

            ItemStack existing = inventory.getItem(slot);

            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            remaining -= addTo(minecraft, inventory, slot,
                    new ItemStack(stack.getItem(), 1), remaining, max);
        }
    }

    /**
     * Adds up to {@code amount} onto {@code slot}, capped by {@code max}, and
     * tells the game about the new contents.
     *
     * @return how many items were actually added
     */
    private int addTo(net.minecraft.client.Minecraft minecraft,
                      net.minecraft.world.entity.player.Inventory inventory,
                      int slot, ItemStack template, int amount, int max) {
        int room = Math.max(0, max - template.getCount());
        int added = Math.min(room, amount);

        if (added <= 0) {
            return 0;
        }

        // 1. put the stack in the player's own inventory.
        ItemStack merged = template.copy();
        merged.setCount(template.getCount() + added);
        inventory.setItem(slot, merged);

        // 2. keep the open menu's copy in sync, the way the game does.
        syncMenu(minecraft, slot, merged);

        // 3. tell the server, through the vanilla client method.
        //
        // The vanilla Creative screen passes an InventoryMenu slot number
        // (hotbar container 0-8 -> menu 36-44), never a raw container index:
        // the server validates the packet slot against 1..45 and then writes
        // player.inventoryMenu.getSlot(slot). A container index would land in
        // the crafting/armor slots (or be dropped entirely for slot 0), so the
        // server would never actually receive the blocks.
        int menuSlot = Inventories.menuSlotOfContainerIndex(slot);
        addToCreative(minecraft, merged, menuSlot);

        // Diagnostics: record exactly where this landed, so a live log shows the
        // real slot and stack rather than an assumption.
        this.lastWrite = "slot " + slot + " (menu " + menuSlot + ") x" + merged.getCount();

        return added;
    }

    /** Where the most recent hand-over went; diagnostics only. */
    private String lastWrite = "none";

    @Override
    public String lastWrite(String itemId) {
        return this.lastWrite;
    }

    /** Mirrors a slot write into the open inventory menu, when there is one. */
    private static void syncMenu(net.minecraft.client.Minecraft minecraft, int slot, ItemStack stack) {
        if (minecraft.player == null || minecraft.player.inventoryMenu == null) {
            return;
        }

        int menuSlot = Inventories.menuSlotOfContainerIndex(slot);

        // Armour, the off-hand and the saddle have no writable Creative menu
        // slot, so they are left untouched rather than overwritten.
        if (menuSlot < 0) {
            return;
        }

        if (menuSlot < minecraft.player.inventoryMenu.slots.size()) {
            Slot target = minecraft.player.inventoryMenu.getSlot(menuSlot);

            if (target != null) {
                target.set(stack);
            }
        }
    }

    /**
     * Calls the vanilla Creative hand-over. No packet is built or sent by hand.
     *
     * @param menuSlot the {@code InventoryMenu} slot number - what the server
     *                 validates and what the vanilla Creative screen passes;
     *                 never a raw {@code Inventory} container index
     */
    private static void addToCreative(net.minecraft.client.Minecraft minecraft,
                                      ItemStack stack, int menuSlot) {
        if (minecraft.gameMode != null) {
            minecraft.gameMode.handleCreativeModeItemAdd(stack, menuSlot);
        }
    }

    /**
     * A stack of {@code itemId} holding {@code amount} items, capped at one
     * stack.
     *
     * <p>{@link #grant} therefore stops at one stack per call. That is an
     * intentional limit on how much can enter the inventory at once: the caller
     * re-plans afterwards and asks again for the remainder.
     */
    private static ItemStack fullyStacked(String itemId, int amount) {
        Item item = itemFrom(itemId);

        if (item == null) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(item);
        stack.setCount(Math.min(amount, Math.max(1, stack.getMaxStackSize())));
        return stack;
    }

    /** Looks an item up from its registry id, rejecting anything with no item form. */
    private static Item itemFrom(String itemId) {
        try {
            Identifier id = Identifier.parse(itemId);
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);

            // Air and fluids have no block item, so they can never be handed over.
            if (item == null || item == net.minecraft.world.item.Items.AIR
                    || item == Fluids.EMPTY.getBucket()) {
                return null;
            }

            return item;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String describe() {
        return "creative";
    }
}