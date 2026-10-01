package dev.chika.builder.platform.engine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Converts between item objects and their {@code namespace:path} ids.
 *
 * <p>Centralised so the material layer has exactly one place that knows how
 * 26.1.2 exposes registry names, and so every lookup degrades to {@code null}
 * instead of throwing when an id is unknown.
 */
final class ItemIds {

    private ItemIds() {
    }

    /**
     * The registry id of an item, e.g. {@code minecraft:stone}.
     *
     * <p>Prefer the built-in registry lookup over the deprecated
     * {@code builtInRegistryHolder()} when a key is available; the holder is
     * only consulted as a fallback.
     *
     * @return the id, or {@code null} when the item is not registered
     */
    static String of(Item item) {
        if (item == null) {
            return null;
        }

        try {
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            return key == null ? null : key.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Looks a block up from its registry id.
     *
     * @return the block, or {@code null} when the id is unknown or malformed
     */
    static Block blockFrom(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        try {
            return BuiltInRegistries.BLOCK
                    .getOptional(Identifier.parse(itemId))
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
