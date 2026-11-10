package fr.eternom.eterQuest.service;

import fr.eternom.eterQuest.model.Objective;
import fr.eternom.eterQuest.model.Targets;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Les objets à remettre (objectifs DELIVER) : seulement des objets simples (sans nom, enchantement ni données), main
 * secondaire comprise. Les objets d'une remise refusée sont rendus ; ce qui ne tient pas va dans la boîte de
 * récupération (jamais au sol). Repris d'EterJob 2.x.
 */
public final class Inventories {

    private Inventories() {
    }

    public static int count(PlayerInventory inventory, Objective item) {
        int count = 0;
        for (ItemStack stack : contents(inventory)) {
            if (stack != null && plain(stack) && Targets.matchesItem(item, stack.getType())) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static List<ItemStack> contents(PlayerInventory inventory) {
        List<ItemStack> all = new ArrayList<>(Arrays.asList(inventory.getStorageContents()));
        all.add(inventory.getItemInOffHand());
        return all;
    }

    private static boolean plain(ItemStack stack) {
        return !stack.getType().isAir() && stack.isSimilar(ItemStack.of(stack.getType()));
    }

    /** Prend amount objets de l'objectif ; renvoie ce qui a été pris (pour le rendre si la remise échoue). */
    public static List<ItemStack> take(PlayerInventory inventory, Objective item, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        int remaining = amount;
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack stack = storage[i];
            if (stack != null && plain(stack) && Targets.matchesItem(item, stack.getType())) {
                int count = Math.min(remaining, stack.getAmount());
                taken.add(ItemStack.of(stack.getType(), count));
                stack.setAmount(stack.getAmount() - count);
                remaining -= count;
                storage[i] = stack.getAmount() == 0 ? null : stack;
            }
        }
        inventory.setStorageContents(storage);
        ItemStack off = inventory.getItemInOffHand();
        if (remaining > 0 && plain(off) && Targets.matchesItem(item, off.getType())) {
            int count = Math.min(remaining, off.getAmount());
            taken.add(ItemStack.of(off.getType(), count));
            off.setAmount(off.getAmount() - count);
            inventory.setItemInOffHand(off.getAmount() == 0 ? null : off);
        }
        return taken;
    }

    /** « MATIÈRE quantité;... » (boîte de récupération). */
    public static String text(List<ItemStack> stacks) {
        return String.join(";", stacks.stream().map(stack -> stack.getType().name() + " " + stack.getAmount()).toList());
    }

    public static List<ItemStack> parse(String text) {
        List<ItemStack> stacks = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] fields = part.trim().split("\\s+");
            if (fields.length != 2) {
                continue;
            }
            Material material = Material.matchMaterial(fields[0]);
            if (material == null || !material.isItem()) {
                continue;
            }
            try {
                int remaining = Integer.parseInt(fields[1]);
                while (remaining > 0) {
                    int size = Math.min(remaining, material.getMaxStackSize());
                    stacks.add(ItemStack.of(material, size));
                    remaining -= size;
                }
            } catch (NumberFormatException ignored) {
                // colis abîmé : ignoré
            }
        }
        return stacks;
    }

    /** Ajoute ce qui tient ; renvoie le reste. */
    public static List<ItemStack> give(PlayerInventory inventory, List<ItemStack> stacks) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack stack : stacks) {
            left.addAll(inventory.addItem(stack.clone()).values());
        }
        return left;
    }

    public static boolean fits(PlayerInventory inventory, List<ItemStack> stacks) {
        Inventory copy = Bukkit.createInventory(null, 36);
        copy.setContents(Arrays.copyOf(inventory.getStorageContents(), 36));
        for (ItemStack stack : stacks) {
            if (!copy.addItem(stack.clone()).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
