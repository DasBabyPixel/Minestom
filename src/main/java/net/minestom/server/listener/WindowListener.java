package net.minestom.server.listener;

import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryButtonClickEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.client.common.ClientPongPacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowButtonPacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.server.network.packet.server.common.PingPacket;

public class WindowListener {

    public static void clickWindowListener(ClientClickWindowPacket packet, Player player) {
        final int windowId = packet.windowId();
        final boolean playerInventory = windowId == 0;
        final Inventory inventory = playerInventory ? player.getInventory() : player.getOpenInventory();

        // Prevent some invalid packets
        if (inventory == null || packet.slot() == -1) return;

        Click.Preprocessor preprocessor = player.clickPreprocessor();
        final Click.Info info = preprocessor.processClick(packet, player.isCreative(), playerInventory ? null : inventory.getSize());
        if (info != null) inventory.handleClick(player, info);

        // (Why is the ping packet necessary?)
        player.sendPacket(new PingPacket((1 << 30) | (windowId << 16)));
    }

    public static void pong(ClientPongPacket packet, Player player) {
        // Empty
    }

    public static void closeWindowListener(ClientCloseWindowPacket packet, Player player) {
        // if windowId == 0 then it is player's inventory, meaning that they hadn't been any open inventory packet
        var openInventory = player.getOpenInventory();
        if (openInventory == null) openInventory = player.getInventory();

        InventoryCloseEvent inventoryCloseEvent = new InventoryCloseEvent(openInventory, player);
        EventDispatcher.call(inventoryCloseEvent);

        player.closeInventory(true);

        Inventory newInventory = inventoryCloseEvent.getNewInventory();
        if (newInventory != null) {
            player.openInventory(newInventory);
        } else if (openInventory == player.getInventory()) {
            // player closed his own inventory, #closeInventory doesn't work for that
            // code taken from InventoryImpl#removeViewer. Not nice regarding maintainability
            ItemStack cursorItem = player.getInventory().getCursorItem();
            player.getInventory().setCursorItem(ItemStack.AIR);

            if (!cursorItem.isAir()) {
                // Drop the item if it can not be added back to the inventory
                if (!player.getInventory().addItemStack(cursorItem)) {
                    player.dropItem(cursorItem);
                }
            }
            player.clickPreprocessor().clearCache();
        }
    }

    public static void buttonClickListener(ClientClickWindowButtonPacket packet, Player player) {
        var openInventory = player.getOpenInventory();
        if (openInventory == null) openInventory = player.getInventory();

        InventoryButtonClickEvent event = new InventoryButtonClickEvent(openInventory, player, packet.buttonId());
        EventDispatcher.call(event);
    }
}
