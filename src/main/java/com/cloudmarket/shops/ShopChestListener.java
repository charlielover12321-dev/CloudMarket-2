package com.cloudmarket.shops;

import com.cloudmarket.CloudMarket;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Iterator;
import java.util.List;

/**
 * Protection and access control for registered shop chests.
 *
 * <p>The rule is that a non-owner never sees the raw inventory - only the shop GUI.
 * That is enforced at every route into a chest's contents, not just the obvious
 * one: right-clicking, breaking the block, blowing it up, and hoppers. A hopper
 * under an unprotected shop chest would quietly drain a player's entire stock while
 * they were offline, so it gets blocked too.
 */
public final class ShopChestListener implements Listener {

    private final CloudMarket plugin;

    public ShopChestListener(CloudMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        ShopChest chest = plugin.shopChests().at(block);
        if (chest == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean owner = chest.getOwner().equals(player.getUniqueId());
        boolean bypass = player.hasPermission("market.shopchest.bypass");

        if (owner || bypass) {
            // Sneaking opens the real chest to restock; a normal click previews the
            // shop as a customer would see it.
            if (player.isSneaking()) {
                return;
            }
            event.setCancelled(true);
            plugin.gui().openShopChest(player, chest, true);
            return;
        }

        event.setCancelled(true);
        plugin.gui().openShopChest(player, chest, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ShopChest chest = plugin.shopChests().at(event.getBlock());
        if (chest == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!chest.getOwner().equals(player.getUniqueId())
                && !player.hasPermission("market.shopchest.bypass")) {
            event.setCancelled(true);
            plugin.configs().messages().send(player, "shopchest.not-yours");
            return;
        }
        // The owner breaking their own shop un-registers it rather than leaving a
        // dangling row pointing at empty air.
        plugin.shopChests().unregister(chest);
        plugin.configs().messages().send(player, "shopchest.removed-by-break");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        // Stop a player extending somebody else's single chest into a double chest,
        // which would otherwise give them a legitimate-looking half to loot from.
        Block placed = event.getBlock();
        if (placed.getType() != Material.CHEST && placed.getType() != Material.TRAPPED_CHEST) {
            return;
        }
        for (org.bukkit.block.BlockFace face : List.of(
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
            Block neighbour = placed.getRelative(face);
            if (neighbour.getType() != placed.getType()) {
                continue;
            }
            ShopChest chest = plugin.shopChests().at(neighbour);
            if (chest != null && !chest.getOwner().equals(event.getPlayer().getUniqueId())
                    && !event.getPlayer().hasPermission("market.shopchest.bypass")) {
                event.setCancelled(true);
                plugin.configs().messages().send(event.getPlayer(), "shopchest.cannot-extend");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeProtected(event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeProtected(event.blockList().iterator());
    }

    private void removeProtected(Iterator<Block> iterator) {
        while (iterator.hasNext()) {
            if (plugin.shopChests().isShop(iterator.next())) {
                iterator.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (isShopInventory(event.getSource()) || isShopInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private boolean isShopInventory(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState state) {
            return plugin.shopChests().isShop(state.getBlock());
        }
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            return isHalfShop(doubleChest.getLeftSide()) || isHalfShop(doubleChest.getRightSide());
        }
        return false;
    }

    private boolean isHalfShop(InventoryHolder holder) {
        return holder instanceof BlockState state && plugin.shopChests().isShop(state.getBlock());
    }
}
