package com.example.fabricmod.item;

import com.example.fabricmod.FabricMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Registry class for custom items.
 * Add your items here using the register() helper method.
 */
public class ModItems {
    // --- Item Groups / Creative Tabs ---
    public static final RegistryKey<ItemGroup> MOD_ITEM_GROUP = RegistryKey.of(
            Registries.ITEM_GROUP.getKey(),
            Identifier.of(FabricMod.MOD_ID, "item_group")
    );

    /**
     * Register a simple item with no special properties.
     */
    private static Item register(String name, Item item) {
        return Registry.register(Registries.ITEM, FabricMod.id(name), item);
    }

    /**
     * Call this from FabricMod.onInitialize() to register everything.
     */
    public static void register() {
        FabricMod.LOGGER.info("Registering items for {}", FabricMod.MOD_ID);

        // === Register your items here ===
        // Example:
        // public static final Item RUBY = register("ruby",
        //         new Item(new Item.Settings().maxCount(64)));

        // --- Creative tab ---
        Registry.register(Registries.ITEM_GROUP, MOD_ITEM_GROUP,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup." + FabricMod.MOD_ID))
                        .icon(() -> new ItemStack(Registries.ITEM.get(FabricMod.id("ruby"))))
                        .entries((context, entries) -> {
                            // Add items to the creative tab:
                            // entries.add(RUBY);
                        })
                        .build()
        );
    }
}
