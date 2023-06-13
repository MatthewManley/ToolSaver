package xyz.chinesemenu.toolsaver.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.gui.entries.IntegerListEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.TypedActionResult;
import org.spongepowered.include.com.google.gson.Gson;

import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public class ToolSaverClient implements ClientModInitializer {
    private static final KeyBinding openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.toolsaver.openconfig", InputUtil.Type.KEYSYM, 0, "category.toolsaver.main"));
    private int ticksSinceLastMessage = 20;

    private static final ArrayList<Pair<String, Item>> breakables = new ArrayList<>();
    private static ToolSaverConfig config = null;

    private TypedActionResult<ItemStack> PreventBreakingTools(PlayerEntity player, Hand hand) {
        // Get the item which is being used
        ItemStack stack = player.getStackInHand(hand);

        // If no item is being held, or the item is not damageable, then skip processing
        if (stack.isEmpty() || !stack.isDamageable())
            return TypedActionResult.pass(stack);

        var registryKey = stack.getRegistryEntry().getKey();

        // I don't actually know when this would be empty
        // Just doing this check to avoid potential errors
        if (registryKey.isEmpty())
            return TypedActionResult.pass(stack);

        var itemId = registryKey.get().getValue().toString();

        var configMinDurability = config.getItems().get(itemId);
        var remainingDurability = stack.getMaxDamage() - stack.getDamage();

        if (configMinDurability == null || configMinDurability <= 0 || remainingDurability > configMinDurability) {
            return TypedActionResult.pass(stack);
        }
        // This is a check so we don't spam their chat messages, only send a message once per second
        if (ticksSinceLastMessage >= 20) {
            var response = MutableText.of(TextContent.EMPTY)
                    .append("[Tool Saver] ")
                    .append(Text.translatable(stack.getTranslationKey()))
                    .append(" is at or below durability limit: ")
                    .append(configMinDurability.toString());
            player.sendMessage(response);
            ticksSinceLastMessage = 0;
        }
        return TypedActionResult.fail(stack);
    }

    @Override
    public void onInitializeClient() {
        var gson = new Gson();
        try {
            File f = new File("config/toolsaver.json");
            if (f.exists()) {
                var reader = new BufferedReader(new FileReader(f));
                config = gson.fromJson(reader, ToolSaverConfig.class);
            } else {
                config = new ToolSaverConfig();
                config.setItems(new Hashtable<>());
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        Registries.ITEM.getEntrySet().stream()
                .filter(x -> x.getValue().isDamageable())
                .filter(x -> !(x.getValue() instanceof ArmorItem))
                .filter(x -> !(x.getValue() instanceof ElytraItem))
                .sorted(Comparator.comparingInt(a -> a.getValue().getMaxDamage()))
                .forEach(x -> {
                    var id = x.getKey().getValue().toString();
                    var item = x.getValue();
                    breakables.add(new Pair<>(id, item));
                });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> PreventBreakingTools(player, hand).getResult());
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> PreventBreakingTools(player, hand).getResult());
        UseItemCallback.EVENT.register((player, world, hand) -> PreventBreakingTools(player, hand));

        ClientTickEvents.END_CLIENT_TICK.register(this::openConfig);
        ClientTickEvents.END_CLIENT_TICK.register(this::ticksSinceMessage);
    }

    public static Screen getConfigScreen() {
        ConfigBuilder builder = ConfigBuilder.create();

        var pickaxes = builder.getOrCreateCategory(Text.translatable("config.toolsaver.pickaxes"));
        var axes = builder.getOrCreateCategory(Text.translatable("config.toolsaver.axes"));
        var shovels = builder.getOrCreateCategory(Text.translatable("config.toolsaver.shovels"));
        var hoes = builder.getOrCreateCategory(Text.translatable("config.toolsaver.hoes"));
        var swords = builder.getOrCreateCategory(Text.translatable("config.toolsaver.swords"));
        var other = builder.getOrCreateCategory(Text.translatable("config.toolsaver.other"));
        var entries = new ArrayList<Pair<String, IntegerListEntry>>(breakables.size());
        for (var testing : breakables) {
            var id = testing.getLeft();
            var item = testing.getRight();
            var currentValue = config.getItems().get(id);
            if (currentValue == null) {
                currentValue = 0;
            }
            var entry = builder.entryBuilder()
                    .startIntField(Text.translatable(item.getTranslationKey()), currentValue)
                    .setDefaultValue(0)
                    .setMin(0)
                    .setMax(item.getMaxDamage())
                    .build();
            entries.add(new Pair<>(id, entry));
            if (item instanceof PickaxeItem) {
                pickaxes.addEntry(entry);
            } else if (item instanceof HoeItem) {
                var tmp = (HoeItem)item;
                hoes.addEntry(entry);
            } else if (item instanceof AxeItem) {
                axes.addEntry(entry);
            } else if (item instanceof ShovelItem) {
                shovels.addEntry(entry);
            } else if (item instanceof SwordItem) {
                swords.addEntry(entry);
            }   else {
                other.addEntry(entry);
            }
        }
        builder.setSavingRunnable(() -> {
            var items = config.getItems();
            for (var entry : entries) {
                var id = entry.getLeft();
                var item = entry.getRight();
                items.put(id, item.getValue());
            }
            try {
                var writer = new FileWriter("config/toolsaver.json");
                new Gson().toJson(config, writer);
                writer.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return builder.build();
    }

    private void openConfig(MinecraftClient client) {
        boolean open = false;
        while (openConfig.wasPressed()) {
            open = true;
        }
        if (open) {

            client.setScreenAndRender(getConfigScreen());
        }
    }

    private void ticksSinceMessage(MinecraftClient client) {
        if (this.ticksSinceLastMessage < 20) {
            this.ticksSinceLastMessage++;
        }
    }
}

