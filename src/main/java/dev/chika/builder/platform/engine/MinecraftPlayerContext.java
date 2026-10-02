package dev.chika.builder.platform.engine;

import dev.chika.builder.build.material.PlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Reads the local player's real gamemode and inventory.
 *
 * <p><b>Read-only by design.</b> This class never calls
 * {@code setLocalMode}, never sends a gamemode packet, and never changes the
 * player's mode in any way. {@link #isActuallyInCreative()} reports the mode the
 * client is genuinely in, so Creative building can only be used when the player
 * really is in Creative - the setting alone never grants it.
 */
public final class MinecraftPlayerContext implements PlayerContext {

    @Override
    public boolean isActuallyInCreative() {
        try {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.gameMode == null) {
                return false;
            }

            // Reads the client's own gamemode; makes no change.
            return minecraft.gameMode.getPlayerMode().isCreative();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int countItem(String itemId) {
        try {
            return countOf(net.minecraft.client.Minecraft.getInstance().player, itemId);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * How many of the 36 storage slots the engine scans are occupied.
     *
     * <p>Diagnostics for {@code #chika_builder debug}: it answers "is there even
     * room left?" over exactly the range the engine reads.
     */
    @Override
    public int occupiedSlots() {
        try {
            net.minecraft.client.player.LocalPlayer player =
                    net.minecraft.client.Minecraft.getInstance().player;

            if (player == null) {
                return 0;
            }

            net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
            int used = 0;

            for (int slot = 0;
                 slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inventory.getItem(slot);

                if (stack != null && !stack.isEmpty()) {
                    used++;
                }
            }

            return used;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Total number of matching items in the player's 36 storage slots
     * (hotbar and main rows).
     *
     * <p>Deliberately the same 36-slot view the build engine scans when it
     * decides what it can place, so the plan, the hand-over verification and
     * the engine can never disagree about what the player is holding.
     *
     * <p>Shared with {@link CreativeInventorySupplier} so the "before" and
     * "after" counts a Creative hand-over is verified against are taken the same
     * way as the counts the plan was built from. That matters: verifying with a
     * different counter could either over-report a hand-over or reject one that
     * really happened.
     *
     * @return the count, or {@code 0} when there is no player or the id is unknown
     */
    static int countOf(net.minecraft.client.player.LocalPlayer player, String itemId) {
        if (player == null) {
            return 0;
        }

        Identifier id;
        try {
            id = Identifier.parse(itemId);
        } catch (Throwable t) {
            return 0;
        }

        NonNullList<ItemStack> stacks = Inventories.storageStacks(player.getInventory());
        int total = 0;

        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);

            if (stack == null || stack.isEmpty()) {
                continue;
            }

            if (ItemIds.of(stack.getItem()).equals(id.toString())) {
                total += stack.getCount();
            }
        }

        return total;
    }
}
