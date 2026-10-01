package dev.chika.builder.build.shop;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds whichever {@link ShopAdapter} the connected server provides.
 *
 * <p>Chika Builder ships with <b>no</b> adapter, because there is no universal
 * implementation of a server-specific {@code /shop}. Servers (or add-on mods)
 * register one via {@link #register(ShopAdapter)} and Chika Builder then uses it
 * automatically.
 *
 * <p>With no adapter registered, auto-shop is simply unavailable: the planner
 * still plans the purchase, the build pauses, and the player is told no shop is
 * available. It never silently proceeds and never fakes a purchase.
 */
public final class ShopRegistry {

    private static final List<ShopAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private ShopRegistry() {
    }

    /** Registers a server-specific shop implementation. */
    public static void register(ShopAdapter adapter) {
        if (adapter != null && !ADAPTERS.contains(adapter)) {
            ADAPTERS.add(adapter);
        }
    }

    /** Removes a previously registered adapter (e.g. on disconnect). */
    public static boolean unregister(ShopAdapter adapter) {
        return ADAPTERS.remove(adapter);
    }

    /** The first registered adapter that reports itself available right now. */
    public static Optional<ShopAdapter> active() {
        return ADAPTERS.stream().filter(ShopAdapter::isAvailable).findFirst();
    }

    /** True when at least one adapter is currently usable. */
    public static boolean hasAvailableAdapter() {
        return active().isPresent();
    }

    /** Registered adapters, for diagnostics. */
    public static List<ShopAdapter> adapters() {
        return List.copyOf(ADAPTERS);
    }

    /** Clears every adapter. Used by tests and on disconnect. */
    public static void clear() {
        ADAPTERS.clear();
    }
}
