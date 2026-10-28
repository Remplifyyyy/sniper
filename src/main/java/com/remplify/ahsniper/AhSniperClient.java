package com.remplify.ahsniper;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class AhSniperClient implements ClientModInitializer {
	public static final String MOD_ID = "remplify-ahsniper";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final Sniper SNIPER = new Sniper();

	private static KeyMapping toggleKey;
	private static KeyMapping addKey;
	private static KeyMapping menuKey;
	private static boolean openMenuNextTick;

	@Override
	public void onInitializeClient() {
		SniperConfig.load();
		EconomyData.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.remplify-ahsniper.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.remplify-ahsniper.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		addKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.remplify-ahsniper.add", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (menuKey.consumeClick()) mc.setScreen(new SniperMenuScreen());
			while (toggleKey.consumeClick()) SNIPER.toggle();
			while (addKey.consumeClick()) openAddScreen(mc);
			if (openMenuNextTick) {
				openMenuNextTick = false;
				mc.setScreen(new SniperMenuScreen());
			}
			SNIPER.tick(mc);
		});

		// Everything lives in the menu now; /snipe just opens it (handy if you forget the key).
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				literal("snipe").executes(ctx -> {
					openMenuNextTick = true; // wait for the chat screen to close first
					return 1;
				})));

		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
			if (!SniperConfig.get().priceTooltips || stack.isEmpty()) return;
			EconomyData data = EconomyData.get();
			String key = EconomyData.groupKey(stack);
			if (!data.groups.containsKey(key)) return;
			EconomyData.Stats s = data.stats(key, EconomyData.DAY);
			if (s.count == 0) return;
			String each = s.quantity > s.count ? " each" : "";
			lines.add(Component.literal("§8AH 24h: §7avg §e" + EconomyData.fmt(s.average) + each
					+ " §7· low §a" + EconomyData.fmt(s.cleanLowest) + each + " §8(" + s.count + ")"));
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			EconomyData.get().save();
			SniperConfig.save();
		});

		LOGGER.info("Remplify AH Sniper loaded with {} snipe entries", SniperConfig.get().entries.size());
	}

	private static void openAddScreen(Minecraft mc) {
		if (mc.player == null) return;
		ItemStack held = mc.player.getMainHandItem();
		if (held.isEmpty()) {
			msg("§cHold the item you want to snipe first.");
			return;
		}
		mc.setScreen(new AddSnipeScreen(held));
	}

	public static void msg(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal("§b[AH Sniper] §f" + text));
	}
}
