package com.remplify.ahsniper;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Blacklist for an "Any item" snipe. Left: search items to block. Right: what's blocked.
 * Search covers every item seen on the AH (exact item, e.g. custom "Raid TNT") and every
 * vanilla item type ("All tnt" blocks every TNT whatever its name).
 */
public class BlacklistScreen extends Screen {
	private record Result(SnipeEntry.Blocked blocked, ItemStack icon, boolean fromAh) {}

	private static List<Result> vanillaCache;

	private final List<SnipeEntry.Blocked> list;
	private final Screen parent;

	private static String search = "";
	private List<Result> results = new ArrayList<>();
	private int resultOffset;
	private int listOffset;

	private final List<Button> resultButtons = new ArrayList<>();
	private int left, top, w, h, colW, rows;

	public BlacklistScreen(List<SnipeEntry.Blocked> list, Screen parent) {
		super(Component.literal("Any item: blacklist"));
		this.list = list;
		this.parent = parent;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}

	@Override
	protected void init() {
		w = Math.min(440, this.width - 16);
		h = Math.min(250, this.height - 16);
		left = (this.width - w) / 2;
		top = (this.height - h) / 2;
		colW = (w - 24) / 2;
		rows = Math.max(1, (h - 64 - 30) / 20);
		resultButtons.clear();

		EditBox box = new EditBox(this.font, left + 8, top + 22, colW, 18, Component.literal("Search"));
		box.setMaxLength(50);
		box.setHint(Component.literal("Search items to blacklist..."));
		box.setValue(search);
		box.setResponder(text -> {
			search = text;
			resultOffset = 0;
			refreshResults();
		});
		addRenderableWidget(box);

		int y = top + 44;
		for (int i = 0; i < rows; i++) {
			final int row = i;
			Button b = Button.builder(Component.empty(), btn -> {
				int idx = resultOffset + row;
				if (idx >= results.size()) return;
				SnipeEntry.Blocked add = results.get(idx).blocked();
				if (!contains(add)) {
					list.add(new SnipeEntry.Blocked(add.itemId, add.name));
					SniperConfig.save();
					rebuildWidgets();
				}
			}).bounds(left + 28, y, colW - 20, 18).build();
			resultButtons.add(addRenderableWidget(b));
			y += 20;
		}

		// right column: current blacklist with remove buttons
		int rx = left + 16 + colW;
		listOffset = Math.max(0, Math.min(listOffset, list.size() - rows));
		y = top + 44;
		for (int i = listOffset; i < Math.min(list.size(), listOffset + rows); i++) {
			SnipeEntry.Blocked bl = list.get(i);
			Button x = Button.builder(Component.literal("§cX"), btn -> {
				list.remove(bl);
				SniperConfig.save();
				rebuildWidgets();
			}).bounds(rx + colW - 20, y, 20, 18).build();
			x.setTooltip(Tooltip.create(Component.literal("Remove " + bl.label() + " from the blacklist")));
			addRenderableWidget(x);
			y += 20;
		}

		int by = top + h - 26;
		Button held = Button.builder(Component.literal("+ Held item"), b -> {
			if (this.minecraft.player == null || this.minecraft.player.getMainHandItem().isEmpty()) return;
			ItemStack s = this.minecraft.player.getMainHandItem();
			SnipeEntry.Blocked add = new SnipeEntry.Blocked(SnipeEntry.itemId(s), SnipeEntry.displayName(s));
			if (!contains(add)) {
				list.add(add);
				SniperConfig.save();
				rebuildWidgets();
			}
		}).bounds(left + 8, by, 90, 20).build();
		held.setTooltip(Tooltip.create(Component.literal("Blacklist the exact item you're holding (same type and name).")));
		addRenderableWidget(held);

		Button clear = Button.builder(Component.literal("Clear all"), b -> {
			list.clear();
			SniperConfig.save();
			rebuildWidgets();
		}).bounds(left + 102, by, 70, 20).build();
		clear.active = !list.isEmpty();
		addRenderableWidget(clear);

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(left + w - 88, by, 80, 20).build());

		refreshResults();
	}

	private boolean contains(SnipeEntry.Blocked b) {
		for (SnipeEntry.Blocked x : list) if (x.sameAs(b)) return true;
		return false;
	}

	private void refreshResults() {
		String q = search.strip().toLowerCase(Locale.ROOT);
		List<Result> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		// exact items seen on the AH first (covers the server's custom items)
		for (EconomyData.Group g : EconomyData.get().groups.values()) {
			if (!q.isEmpty() && !g.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			String key = g.itemId + "|" + g.name.toLowerCase(Locale.ROOT);
			if (!seen.add(key)) continue;
			ItemStack icon = EconomyData.get().sample(g.key);
			out.add(new Result(new SnipeEntry.Blocked(g.itemId, g.name), icon != null ? icon : iconFor(g.itemId), true));
		}
		// then whole item types
		if (!q.isEmpty()) {
			for (Result r : vanilla()) {
				String name = r.icon().getHoverName().getString().toLowerCase(Locale.ROOT);
				if (name.contains(q) || r.blocked().itemId.contains(q.replace(' ', '_'))) out.add(r);
				if (out.size() > 400) break;
			}
		}
		results = out;
		resultOffset = Math.max(0, Math.min(resultOffset, results.size() - rows));

		for (int i = 0; i < resultButtons.size(); i++) {
			Button b = resultButtons.get(i);
			int idx = resultOffset + i;
			if (idx >= results.size()) {
				b.visible = false;
				continue;
			}
			Result r = results.get(idx);
			boolean already = contains(r.blocked());
			String tag = r.fromAh() ? "" : " §7(all)";
			String name = this.font.plainSubstrByWidth(r.blocked().label(), colW - 34 - this.font.width(r.fromAh() ? "" : " (all)"));
			b.setMessage(Component.literal((already ? "§8" : "") + name + tag));
			b.active = !already;
			b.setTooltip(Tooltip.create(Component.literal(r.fromAh()
					? "Block this exact item (type + name), e.g. a custom server item."
					: "Block EVERY " + r.blocked().itemId.replace("minecraft:", "") + ", whatever it's named.")));
			b.visible = true;
		}
	}

	private static List<Result> vanilla() {
		if (vanillaCache == null) {
			vanillaCache = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				ItemStack s = new ItemStack(item);
				if (s.isEmpty()) continue;
				vanillaCache.add(new Result(new SnipeEntry.Blocked(SnipeEntry.itemId(s), null), s, false));
			}
		}
		return vanillaCache;
	}

	private static ItemStack iconFor(String itemId) {
		try {
			return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)));
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.renderBackground(g, mouseX, mouseY, delta);
		g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xFF3A3A55);
		g.fill(left, top, left + w, top + h, 0xF0141420);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(this.font, "§lBlacklist §7- \"Any item\" never buys these", left + 8, top + 8, 0xFFFFFFFF);

		for (int i = 0; i < resultButtons.size(); i++) {
			int idx = resultOffset + i;
			if (idx >= results.size()) break;
			g.renderItem(results.get(idx).icon(), left + 8, top + 45 + i * 20);
		}
		if (results.isEmpty()) {
			g.drawString(this.font, search.isBlank() ? "§7Type to search all items." : "§7No items found.", left + 8, top + 48, 0xFFFFFFFF);
		} else if (results.size() > rows) {
			g.drawString(this.font, "§8scroll for more (" + results.size() + ")", left + 8, top + h - 40, 0xFFFFFFFF);
		}

		int rx = left + 16 + colW;
		g.fill(rx - 8, top + 22, rx - 7, top + h - 32, 0xFF333344);
		g.drawString(this.font, "Blacklisted (" + list.size() + ")", rx, top + 27, 0xFFDDDDDD);
		if (list.isEmpty()) {
			g.drawString(this.font, "§7Nothing yet. Click an item on", rx, top + 48, 0xFFFFFFFF);
			g.drawString(this.font, "§7the left to block it.", rx, top + 59, 0xFFFFFFFF);
		}
		int y = top + 44;
		for (int i = listOffset; i < Math.min(list.size(), listOffset + rows); i++) {
			SnipeEntry.Blocked bl = list.get(i);
			g.renderItem(iconFor(bl.itemId), rx, y + 1);
			g.drawString(this.font, this.font.plainSubstrByWidth(bl.label(), colW - 46), rx + 20, y + 5,
					bl.name == null ? 0xFFFFAA55 : 0xFFFFFFFF);
			y += 20;
		}
		if (list.size() > rows) g.drawString(this.font, "§8scroll for more", rx, top + h - 40, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int dir = scrollY < 0 ? 3 : scrollY > 0 ? -3 : 0;
		if (dir == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		if (mouseX < left + 12 + colW) {
			int o = Math.max(0, Math.min(Math.max(0, results.size() - rows), resultOffset + dir));
			if (o != resultOffset) { resultOffset = o; refreshResults(); }
		} else {
			int o = Math.max(0, Math.min(Math.max(0, list.size() - rows), listOffset + dir));
			if (o != listOffset) { listOffset = o; rebuildWidgets(); }
		}
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
