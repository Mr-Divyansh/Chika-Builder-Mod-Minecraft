package dev.chika.builder.platform.engine;

import dev.chika.builder.build.material.PlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
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
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.player == null) {
                return 0;
            }

            return count(minecraft.player.getInventory(), itemId);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Total number of matching items across the whole inventory. */
    private static int count(Inventory inventory, String itemId) {
        Identifier id;
        try {
            id = Identifier.parse(itemId);
        } catch (Throwable t) {
            return 0;
        }

        int total = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

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
