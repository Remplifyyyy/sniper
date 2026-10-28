package com.remplify.ahsniper;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** The one menu for everything: Snipe List, Economy, Purchases and Settings tabs. Open with O or /snipe. */
public class SniperMenuScreen extends Screen {
	private enum Tab { SNIPES("Snipe List"), ECONOMY("Economy"), PURCHASES("Purchases"), SETTINGS("Settings");
		final String label;
		Tab(String label) { this.label = label; }
	}

	// remembered between openings
	private static Tab tab = Tab.SNIPES;
	private static int snipePage;
	private static int purchasePage;
	private static int econPage;
	private static String search = "";
	private static long window = EconomyData.DAY;
	private static String selected;
	private static EconomyData.Sort sort = EconomyData.Sort.MOST_LISTED;

	private int left, top, w, h;
	private int rowsPerPage;

	// economy widgets that update without rebuilding
	private final List<Button> econRows = new ArrayList<>();
	private List<EconomyData.Group> econResults = new ArrayList<>();
	private EditBox snipeAtBox;
	private Button addFromEconButton;
	private Button eachToggle;
	private boolean eachMode = true;
	private boolean confirmClear;

	public SniperMenuScreen() {
		super(Component.literal("Remplify AH Sniper"));
	}

	@Override
	protected void init() {
		w = Math.min(460, this.width - 16);
		h = Math.min(262, this.height - 16);
		left = (this.width - w) / 2;
		top = (this.height - h) / 2;

		int x = left + 6;
		for (Tab t : Tab.values()) {
			Button b = Button.builder(Component.literal(t.label), btn -> { tab = t; confirmClear = false; rebuildWidgets(); })
					.bounds(x, top + 6, 70, 20).build();
			b.active = tab != t;
			addRenderableWidget(b);
			x += 72;
		}
		boolean busy = AhSniperClient.SNIPER.isBusy();
		addRenderableWidget(Button.builder(Component.literal(busy ? "§cStop" : "§aStart sniping"), b -> {
			if (AhSniperClient.SNIPER.isBusy()) {
				AhSniperClient.SNIPER.stop("Stopped.");
				rebuildWidgets();
			} else {
				onClose();
				AhSniperClient.SNIPER.start();
			}
		}).bounds(left + w - 96, top + 6, 90, 20).build());

		switch (tab) {
			case SNIPES -> initSnipes();
			case ECONOMY -> initEconomy();
			case PURCHASES -> initPurchases();
			case SETTINGS -> initSettings();
		}
	}

	// ================================================================== Snipe List

	private void initSnipes() {
		List<SnipeEntry> list = SniperConfig.get().entries;
		rowsPerPage = Math.max(1, (h - 36 - 34) / 22);
		int pages = Math.max(1, (list.size() + rowsPerPage - 1) / rowsPerPage);
		snipePage = Math.min(snipePage, pages - 1);

		int y = top + 36;
		for (int i = snipePage * rowsPerPage; i < Math.min(list.size(), (snipePage + 1) * rowsPerPage); i++) {
			SnipeEntry e = list.get(i);
			int rx = left + w;

			EditBox price = new EditBox(this.font, rx - 196, y + 1, 70, 18, Component.literal("Max price"));
			price.setMaxLength(20);
			price.setValue(SnipeEntry.formatPrice(e.maxPrice));
			price.setResponder(text -> {
				long p = SnipeEntry.parsePrice(text);
				if (p > 0 && p != e.maxPrice) {
					e.maxPrice = p;
					SniperConfig.save();
				}
			});
			price.setTooltip(Tooltip.create(Component.literal("Max price" + (e.perItem ? " per item" : " for the whole listing")
					+ ". Accepts 25000, 250k, 1.5m")));
			addRenderableWidget(price);

			Button edit = Button.builder(Component.literal("Edit"), b -> this.minecraft.setScreen(new AddSnipeScreen(e, this)))
					.bounds(rx - 122, y, 44, 20).build();
			edit.setTooltip(Tooltip.create(Component.literal("Change price, how many to buy, budget, lore filter and strict matching.")));
			addRenderableWidget(edit);

			Button on = Button.builder(Component.literal(e.enabled ? "§aOn" : "§cOff"), b -> {
				e.enabled = !e.enabled;
				SniperConfig.save();
				rebuildWidgets();
			}).bounds(rx - 74, y, 34, 20).build();
			addRenderableWidget(on);

			Button del = Button.builder(Component.literal("§cX"), b -> {
				list.remove(e);
				SniperConfig.save();
				rebuildWidgets();
			}).bounds(rx - 36, y, 20, 20).build();
			del.setTooltip(Tooltip.create(Component.literal("Remove " + e.name)));
			addRenderableWidget(del);
			y += 22;
		}

		int by = top + h - 26;
		Button add = Button.builder(Component.literal("+ Add held item"), b -> {
			if (this.minecraft.player == null || this.minecraft.player.getMainHandItem().isEmpty()) {
				AhSniperClient.msg("§cHold the item you want to snipe first.");
				return;
			}
			this.minecraft.setScreen(new AddSnipeScreen(this.minecraft.player.getMainHandItem(), this));
		}).bounds(left + 8, by, 100, 20).build();
		add.setTooltip(Tooltip.create(Component.literal("Or press K while holding an item. Tip: the Economy tab can add items too.")));
		addRenderableWidget(add);
		Button any = Button.builder(Component.literal("+ Any item"), b -> this.minecraft.setScreen(AddSnipeScreen.anyItem(this)))
				.bounds(left + 112, by, 80, 20).build();
		any.setTooltip(Tooltip.create(Component.literal("Buy ANY buy-now listing under a price. Set a budget so it can't spend all your coins.")));
		addRenderableWidget(any);

		addPager(by, () -> snipePage, p -> snipePage = p, pages);
	}

	private void renderSnipes(GuiGraphics g) {
		List<SnipeEntry> list = SniperConfig.get().entries;
		if (list.isEmpty()) {
			g.drawCenteredString(this.font, Component.literal("§7No items yet. Hold one and click \"+ Add held item\" (or press K),"),
					left + w / 2, top + 70, 0xFFFFFFFF);
			g.drawCenteredString(this.font, Component.literal("§7or pick one in the Economy tab."), left + w / 2, top + 82, 0xFFFFFFFF);
			return;
		}
		int y = top + 36;
		for (int i = snipePage * rowsPerPage; i < Math.min(list.size(), (snipePage + 1) * rowsPerPage); i++) {
			SnipeEntry e = list.get(i);
			g.renderItem(iconFor(e.itemId, null), left + 8, y + 2);
			int maxW = w - 196 - 32 - 6;
			String name = this.font.plainSubstrByWidth(e.name, maxW);
			g.drawString(this.font, name, left + 28, y + 2, e.enabled && !e.isDone() ? 0xFFFFFFFF : 0xFF888888);
			String progress = e.progressText();
			String sub = e.isDone() ? "§aDone: " + progress + " §7(Edit > Reset to buy more)"
					: (e.perItem ? "per item" : "whole listing")
					+ (progress.isEmpty() ? "" : " · " + progress)
					+ (e.strict || e.anyItem ? "" : " · §6loose§7")
					+ (e.anyItem && e.blacklist != null && !e.blacklist.isEmpty() ? " · " + e.blacklist.size() + " blacklisted" : "")
					+ (e.loreFilter != null && !e.loreFilter.isBlank() ? " · lore: " + e.loreFilter : "");
			g.drawString(this.font, this.font.plainSubstrByWidth(sub, maxW), left + 28, y + 12, 0xFF777777);
			y += 22;
		}
	}

	// ================================================================== Economy

	private static final int LIST_W = 160;

	private void initEconomy() {
		econRows.clear();
		EditBox searchBox = new EditBox(this.font, left + 8, top + 36, LIST_W - 66, 18, Component.literal("Search"));
		searchBox.setMaxLength(50);
		searchBox.setHint(Component.literal("Search items..."));
		searchBox.setValue(search);
		searchBox.setResponder(text -> {
			search = text;
			econPage = 0;
			refreshEconomy();
		});
		addRenderableWidget(searchBox);

		Button sortButton = Button.builder(Component.literal(sort.label), b -> {
			sort = sort.next();
			b.setMessage(Component.literal(sort.label));
			b.setTooltip(Tooltip.create(Component.literal(sort.tip + ". Click to change.")));
			econPage = 0;
			refreshEconomy();
		}).bounds(left + 8 + LIST_W - 64, top + 35, 64, 20).build();
		sortButton.setTooltip(Tooltip.create(Component.literal(sort.tip + ". Click to change.")));
		addRenderableWidget(sortButton);

		rowsPerPage = Math.max(1, (h - 58 - 32) / 20);
		int y = top + 58;
		for (int i = 0; i < rowsPerPage; i++) {
			final int row = i;
			Button b = Button.builder(Component.empty(), btn -> {
				int idx = econPage * rowsPerPage + row;
				if (idx < econResults.size()) {
					selected = econResults.get(idx).key;
					refreshEconomy();
				}
			}).bounds(left + 26, y, LIST_W - 18, 18).build();
			econRows.add(addRenderableWidget(b));
			y += 20;
		}
		int by = top + h - 26;
		addRenderableWidget(Button.builder(Component.literal("<"), b -> { if (econPage > 0) { econPage--; refreshEconomy(); } })
				.bounds(left + 8, by, 20, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			if ((econPage + 1) * rowsPerPage < econResults.size()) { econPage++; refreshEconomy(); }
		}).bounds(left + 30, by, 20, 20).build());

		// time filter
		int dx = left + LIST_W + 20;
		long[] windows = { EconomyData.HOUR, EconomyData.DAY, EconomyData.WEEK };
		String[] labels = { "Past hour", "Past 24h", "Past 7 days" };
		int wbw = (left + w - 8 - dx) / 3;
		for (int i = 0; i < 3; i++) {
			long win = windows[i];
			Button b = Button.builder(Component.literal(labels[i]), btn -> { window = win; rebuildWidgets(); })
					.bounds(dx + i * wbw, top + 34, wbw - 2, 20).build();
			b.active = window != win;
			addRenderableWidget(b);
		}

		// add-to-snipe controls under the detail panel
		snipeAtBox = new EditBox(this.font, dx + 62, by + 1, 70, 18, Component.literal("Snipe price"));
		snipeAtBox.setMaxLength(20);
		addRenderableWidget(snipeAtBox);
		eachToggle = Button.builder(Component.literal(eachMode ? "each" : "total"), b -> {
			eachMode = !eachMode;
			b.setMessage(Component.literal(eachMode ? "each" : "total"));
			fillSuggestion();
		}).bounds(dx + 134, by, 38, 20).build();
		eachToggle.setTooltip(Tooltip.create(Component.literal("each = max price per item (stacks scale with amount), total = max for the whole listing")));
		addRenderableWidget(eachToggle);
		addFromEconButton = Button.builder(Component.literal("Add to snipes"), b -> addSelectedToSnipes())
				.bounds(dx + 174, by, Math.max(40, left + w - 8 - (dx + 174)), 20).build();
		addFromEconButton.setTooltip(Tooltip.create(Component.literal("Adds this item to your snipe list at the price on the left (pre-filled with 80% of the average).")));
		addRenderableWidget(addFromEconButton);

		refreshEconomy();
	}

	private void refreshEconomy() {
		econResults = EconomyData.get().search(search, window, sort);
		int pages = Math.max(1, (econResults.size() + rowsPerPage - 1) / rowsPerPage);
		if (econPage >= pages) econPage = pages - 1;
		for (int i = 0; i < econRows.size(); i++) {
			Button b = econRows.get(i);
			int idx = econPage * rowsPerPage + i;
			if (idx < econResults.size()) {
				EconomyData.Group gr = econResults.get(idx);
				EconomyData.Stats st = EconomyData.get().stats(gr.key, window);
				String info = switch (sort) {
					case CHEAPEST -> EconomyData.fmt(st.cleanLowest);
					case PRICIEST -> EconomyData.fmt(st.average);
					default -> String.valueOf(st.count);
				};
				String suffix = " §7(" + info + ")";
				String name = this.font.plainSubstrByWidth(gr.name, LIST_W - 30 - this.font.width(" (" + info + ")"));
				b.setMessage(Component.literal((gr.key.equals(selected) ? "§b" : "") + name + suffix));
				b.visible = true;
			} else {
				b.visible = false;
			}
		}
		fillSuggestion();
	}

	private EconomyData.Group selectedGroup() {
		return selected == null ? null : EconomyData.get().groups.get(selected);
	}

	private void fillSuggestion() {
		EconomyData.Group gr = selectedGroup();
		boolean ok = gr != null && EconomyData.get().stats(gr.key, window).count > 0;
		snipeAtBox.visible = ok;
		eachToggle.visible = ok;
		addFromEconButton.visible = ok;
		if (!ok) return;
		EconomyData.Stats s = EconomyData.get().stats(gr.key, window);
		// suggest 80% of the cleaned average
		double unit = s.average * 0.8;
		long suggestion = eachMode ? Math.max(1, Math.round(unit)) : Math.max(1, Math.round(unit * Math.max(1, s.lowestCount)));
		snipeAtBox.setValue(SnipeEntry.formatPrice(suggestion));
	}

	private void addSelectedToSnipes() {
		EconomyData.Group gr = selectedGroup();
		if (gr == null) return;
		long p = SnipeEntry.parsePrice(snipeAtBox.getValue());
		if (p <= 0) { AhSniperClient.msg("§cEnter a valid price."); return; }
		SnipeEntry e = new SnipeEntry(gr.itemId, gr.name, p, eachMode, gr.lore, gr.keys);
		SniperConfig.get().entries.add(e);
		SniperConfig.save();
		AhSniperClient.msg("§aAdded §f" + e.describe());
		addFromEconButton.setMessage(Component.literal("§aAdded!"));
	}

	private void renderEconomy(GuiGraphics g) {
		// icons next to the result rows
		for (int i = 0; i < econRows.size(); i++) {
			int idx = econPage * rowsPerPage + i;
			if (idx >= econResults.size()) break;
			EconomyData.Group gr = econResults.get(idx);
			g.renderItem(iconFor(gr.itemId, gr.key), left + 8, top + 59 + i * 20);
		}
		int pages = Math.max(1, (econResults.size() + rowsPerPage - 1) / rowsPerPage);
		g.drawString(this.font, (econPage + 1) + "/" + pages, left + 56, top + h - 20, 0xFFAAAAAA);

		int dx = left + LIST_W + 20;
		int rx = left + w - 10;
		g.fill(dx - 8, top + 34, dx - 7, top + h - 6, 0xFF333344);

		if (econResults.isEmpty() && search.isBlank()) {
			int y = top + 66;
			for (String line : new String[] {
					"§7No price data for this time range yet.",
					"§7Prices are recorded from every /ah page you",
					"§7see: while sniping, while you browse the AH,",
					"§7or with Settings > \"Scan all AH pages\"." }) {
				g.drawString(this.font, line, dx, y, 0xFFFFFFFF);
				y += 11;
			}
			return;
		}

		EconomyData.Group gr = selectedGroup();
		if (gr == null) {
			g.drawString(this.font, "§7Pick an item on the left.", dx, top + 66, 0xFFFFFFFF);
			return;
		}
		EconomyData.Stats s = EconomyData.get().stats(gr.key, window);
		int y = top + 60;
		g.renderItem(iconFor(gr.itemId, gr.key), dx, y);
		g.drawString(this.font, this.font.plainSubstrByWidth(gr.name, rx - dx - 22), dx + 20, y + 1, 0xFF55FFFF);
		g.drawString(this.font, "§8" + gr.itemId.replace("minecraft:", "") + (gr.custom ? " · custom item" : ""), dx + 20, y + 10, 0xFFFFFFFF);
		y += 24;

		if (s.count == 0) {
			g.drawString(this.font, "§7No listings in this time range.", dx, y, 0xFFFFFFFF);
			return;
		}
		String each = s.quantity > s.count ? " each" : "";
		line(g, dx, y, "Lowest", "§a" + EconomyData.fmt(s.cleanLowest) + each
				+ (s.cleanLowestCount > 1 ? " §7(" + EconomyData.fmt(s.cleanLowestPrice) + " for x" + s.cleanLowestCount + ")" : "")
				+ " §7by " + s.cleanLowestSeller);
		y += 11;
		if (s.lowest < s.cleanLowest) {
			line(g, dx, y, "", "§8ignored outlier: " + EconomyData.fmt(s.lowest) + each + " by " + s.lowestSeller);
			y += 11;
		}
		line(g, dx, y, "Average", "§e" + EconomyData.fmt(s.average) + each + " §7(" + s.used + "/" + s.count + " used, outliers removed)");
		y += 11;
		line(g, dx, y, "Median", "§f" + EconomyData.fmt(s.median) + each + "   §7Highest §f" + EconomyData.fmt(s.highest));
		y += 11;
		line(g, dx, y, "Listings", "§f" + s.count + "   §7Sellers §f" + s.sellers + "   §7Items §f" + s.quantity);
		y += 16;

		// price trend chart
		int chartBottom = top + h - 34;
		int chartTop = Math.min(y + 4, chartBottom - 20);
		int cw = rx - dx;
		g.fill(dx, chartTop, rx, chartBottom, 0x40000000);
		double max = 0;
		for (double v : s.buckets) max = Math.max(max, v);
		if (max > 0 && s.buckets.length > 0) {
			int bw = Math.max(1, cw / s.buckets.length);
			for (int i = 0; i < s.buckets.length; i++) {
				double v = s.buckets[i];
				if (v <= 0) continue;
				int bh = (int) Math.max(1, (chartBottom - chartTop - 2) * (v / max));
				int x0 = dx + i * bw;
				int color = v <= s.average ? 0xFF55CC66 : 0xFFCC8844;
				g.fill(x0 + 1, chartBottom - bh, x0 + bw - 1, chartBottom, color);
			}
			// average line
			int ay = chartBottom - (int) ((chartBottom - chartTop - 2) * (s.average / max));
			g.fill(dx, ay, rx, ay + 1, 0xFFFFFF55);
		}
		g.drawString(this.font, "§8price over time (old → now) · yellow = avg", dx + 3, chartTop + 3, 0xFFFFFFFF);

		if (snipeAtBox.visible) g.drawString(this.font, "Snipe under", dx, top + h - 20, 0xFFAAAAAA);
	}

	private void line(GuiGraphics g, int x, int y, String label, String value) {
		g.drawString(this.font, "§7" + label, x, y, 0xFFFFFFFF);
		g.drawString(this.font, value, x + 52, y, 0xFFFFFFFF);
	}

	// ================================================================== Purchases

	private void initPurchases() {
		List<EconomyData.Purchase> list = EconomyData.get().purchases;
		rowsPerPage = Math.max(1, (h - 60 - 34) / 12);
		int pages = Math.max(1, (list.size() + rowsPerPage - 1) / rowsPerPage);
		purchasePage = Math.min(purchasePage, pages - 1);
		int by = top + h - 26;
		addPager(by, () -> purchasePage, p -> purchasePage = p, pages);
		Button clear = Button.builder(Component.literal(confirmClear ? "§cClick again to clear" : "Clear history"), b -> {
			if (confirmClear) {
				EconomyData.get().clearPurchases();
				confirmClear = false;
			} else {
				confirmClear = true;
			}
			rebuildWidgets();
		}).bounds(left + 8, by, 120, 20).build();
		addRenderableWidget(clear);
	}

	private void renderPurchases(GuiGraphics g) {
		List<EconomyData.Purchase> list = EconomyData.get().purchases;
		long spent = 0;
		double worth = 0;
		for (EconomyData.Purchase p : list) {
			spent += p.price;
			worth += p.avgUnit > 0 ? p.avgUnit * Math.max(1, p.count) : p.price;
		}
		int y = top + 36;
		g.drawString(this.font, "§7Bought §f" + list.size() + "§7 items for §e" + EconomyData.fmt(spent)
				+ "§7 · worth at avg §e" + EconomyData.fmt(worth)
				+ "§7 · saved " + (worth - spent >= 0 ? "§a" : "§c") + EconomyData.fmt(Math.abs(worth - spent)), left + 8, y, 0xFFFFFFFF);
		y += 16;
		if (list.isEmpty()) {
			g.drawString(this.font, "§7Nothing sniped yet.", left + 8, y, 0xFFFFFFFF);
			return;
		}
		long now = System.currentTimeMillis();
		for (int i = purchasePage * rowsPerPage; i < Math.min(list.size(), (purchasePage + 1) * rowsPerPage); i++) {
			EconomyData.Purchase p = list.get(i);
			String row = "§8" + ago(now - p.time) + " §f" + p.name + (p.count > 1 ? " x" + p.count : "") + " §7for §e" + EconomyData.fmt(p.price);
			if (p.avgUnit > 0) {
				double value = p.avgUnit * Math.max(1, p.count);
				double diff = value - p.price;
				row += " §7(avg " + EconomyData.fmt(value) + ", " + (diff >= 0 ? "§a+" : "§c-") + EconomyData.fmt(Math.abs(diff)) + "§7)";
			}
			g.drawString(this.font, this.font.plainSubstrByWidth(row, w - 16), left + 8, y, 0xFFFFFFFF);
			y += 12;
		}
	}

	private static String ago(long ms) {
		long m = ms / 60_000;
		if (m < 1) return "now";
		if (m < 60) return m + "m";
		long hrs = m / 60;
		if (hrs < 24) return hrs + "h";
		return (hrs / 24) + "d";
	}

	// ================================================================== Settings

	private void initSettings() {
		settingLabels.clear();
		settingLabelText.clear();
		SniperConfig c = SniperConfig.get();
		int colW = (w - 24) / 2;
		int x1 = left + 8;
		int x2 = left + 16 + colW;
		int y = top + 40;

		number(x1, y, colW, "Refresh delay", () -> c.refreshTicks, v -> c.refreshTicks = v, 5, 5, 1200,
				v -> String.format(java.util.Locale.ROOT, "%.2fs", v / 20.0), "Only used when 'Keep AH open' is off: wait between reopening /ah. Too fast may hit command cooldowns.");
		number(x2, y, colW, "Sort clicks", () -> c.sortClicks, v -> c.sortClicks = v, 1, 0, 20,
				String::valueOf, "Clicks on the repeater each time /ah is opened. 4 = newest first. With 'Keep AH open' this only happens once per opening.");
		y += 24;
		number(x1, y, colW, "Max buys per run", () -> c.maxBuysPerRun, v -> c.maxBuysPerRun = v, 1, 0, 1000,
				v -> v == 0 ? "no limit" : String.valueOf(v), "Stop sniping after this many purchases.");
		toggle(x2, y, colW, "Stop when damaged", () -> c.stopOnDamage, v -> c.stopOnDamage = v,
				"Stops sniping if you take damage, so you don't stand in the AH while getting hit.");
		y += 24;
		toggle(x1, y, colW, "Record AH prices", () -> c.recordPrices, v -> c.recordPrices = v,
				"Saves the price of every listing you see for the Economy tab.");
		toggle(x2, y, colW, "Price tooltips", () -> c.priceTooltips, v -> c.priceTooltips = v,
				"Adds the 24h AH average and lowest price to item tooltips (handy when selling).");
		y += 24;
		toggle(x1, y, colW, "Deal alerts", () -> c.dealAlerts, v -> c.dealAlerts = v,
				"Chat message when a new listing is cheaper than the past 24h lowest listing by your threshold. Outliers are ignored. Needs 5+ listings of data.");
		number(x2, y, colW, "Deal threshold", () -> c.dealPercent, v -> c.dealPercent = v, 5, 5, 95,
				v -> v + "% under", "How far under the past 24h lowest listing (outliers removed) a new listing must be to alert you.");
		y += 24;
		toggle(x1, y, colW, "Keep AH open", () -> c.stayOpen, v -> c.stayOpen = v,
				"On: open /ah and sort by newest once, then keep watching the open menu (fastest, no re-sorting). Off: reopen /ah and re-sort every refresh.");
		number(x2, y, colW, "Full refresh", () -> c.fullRefreshSeconds, v -> c.fullRefreshSeconds = v, 15, 0, 3600,
				v -> v == 0 ? "never" : v + "s", "With 'Keep AH open': also reopen /ah and re-sort every this many seconds. Only needed if the AH menu doesn't show new listings by itself.");
		y += 24;
		number(x1, y, colW, "Full rescan every", () -> c.rescanSeconds, v -> c.rescanSeconds = v, 1, 1, 60,
				v -> v + "s", "Page 1 is checked every tick. A listing that failed to buy is skipped for this long, then every item on page 1 is checked again from scratch.");
		toggle(x2, y, colW, "Check all pages first", () -> c.sweepOnStart, v -> c.sweepOnStart = v,
				"When sniping starts, go through every AH page once and buy anything under your prices, then sort by newest and watch page 1.");

		int by = top + h - 26;
		int bw = (w - 24) / 3;
		Button scan = Button.builder(Component.literal("Scan all AH pages"), b -> {
			onClose();
			AhSniperClient.SNIPER.startScanAll();
		}).bounds(left + 8, by, bw, 20).build();
		scan.setTooltip(Tooltip.create(Component.literal("Opens /ah and flips through every page to record prices for the Economy tab.")));
		scan.active = !AhSniperClient.SNIPER.isBusy();
		addRenderableWidget(scan);

		Button debug = Button.builder(Component.literal("Debug next /ah"), b -> {
			AhSniperClient.SNIPER.armDebug();
			onClose();
			AhSniperClient.msg("Open §e/ah§f now, the mod will print what it reads from the menu.");
		}).bounds(left + 12 + bw, by, bw, 20).build();
		debug.setTooltip(Tooltip.create(Component.literal("Prints the AH buttons, sort button and first listings in chat. Use if something isn't detected.")));
		addRenderableWidget(debug);

		addRenderableWidget(Button.builder(Component.literal(confirmClear ? "§cClick again to confirm" : "Clear price data"), b -> {
			if (confirmClear) {
				EconomyData.get().clearPrices();
				confirmClear = false;
				selected = null;
			} else {
				confirmClear = true;
			}
			rebuildWidgets();
		}).bounds(left + 16 + 2 * bw, by, bw, 20).build());
	}

	private final List<int[]> settingLabels = new ArrayList<>();
	private final List<String> settingLabelText = new ArrayList<>();

	private interface IntFormat { String format(int v); }

	private void number(int x, int y, int colW, String label, IntSupplier get, IntConsumer set, int step, int min, int max,
						IntFormat format, String tip) {
		settingLabels.add(new int[] { x, y + 6 });
		settingLabelText.add(label);
		int bx = x + colW - 110;
		Button value = Button.builder(Component.literal(format.format(get.getAsInt())), b -> {}).bounds(bx + 22, y, 66, 20).build();
		value.active = false;
		value.setTooltip(Tooltip.create(Component.literal(tip)));
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			set.accept(Math.max(min, get.getAsInt() - step));
			SniperConfig.save();
			value.setMessage(Component.literal(format.format(get.getAsInt())));
		}).bounds(bx, y, 20, 20).build());
		addRenderableWidget(value);
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			set.accept(Math.min(max, get.getAsInt() + step));
			SniperConfig.save();
			value.setMessage(Component.literal(format.format(get.getAsInt())));
		}).bounds(bx + 90, y, 20, 20).build());
	}

	private interface BoolGet { boolean get(); }

	private void toggle(int x, int y, int colW, String label, BoolGet get, Consumer<Boolean> set, String tip) {
		settingLabels.add(new int[] { x, y + 6 });
		settingLabelText.add(label);
		Button b = Button.builder(Component.literal(get.get() ? "§aOn" : "§cOff"), btn -> {
			set.accept(!get.get());
			SniperConfig.save();
			btn.setMessage(Component.literal(get.get() ? "§aOn" : "§cOff"));
		}).bounds(x + colW - 110, y, 110, 20).build();
		b.setTooltip(Tooltip.create(Component.literal(tip)));
		addRenderableWidget(b);
	}

	private void renderSettings(GuiGraphics g) {
		for (int i = 0; i < settingLabels.size(); i++) {
			int[] p = settingLabels.get(i);
			g.drawString(this.font, settingLabelText.get(i), p[0], p[1], 0xFFDDDDDD);
		}
		int entries = SniperConfig.get().entries.size();
		EconomyData d = EconomyData.get();
		g.drawString(this.font, "§8" + entries + " snipe item(s) · " + d.listings.size() + " listings tracked (last 7 days) · "
				+ d.groups.size() + " different items", left + 8, top + h - 40, 0xFFFFFFFF);
	}

	// ================================================================== shared

	private void addPager(int y, IntSupplier get, IntConsumer set, int pages) {
		Button prev = Button.builder(Component.literal("<"), b -> { set.accept(get.getAsInt() - 1); rebuildWidgets(); })
				.bounds(left + w - 80, y, 20, 20).build();
		prev.active = get.getAsInt() > 0;
		addRenderableWidget(prev);
		Button next = Button.builder(Component.literal(">"), b -> { set.accept(get.getAsInt() + 1); rebuildWidgets(); })
				.bounds(left + w - 28, y, 20, 20).build();
		next.active = get.getAsInt() < pages - 1;
		addRenderableWidget(next);
	}

	private static ItemStack iconFor(String itemId, String groupKey) {
		if (SnipeEntry.ANY_ID.equals(itemId)) return new ItemStack(Items.CHEST);
		if (groupKey != null) {
			ItemStack sample = EconomyData.get().sample(groupKey);
			if (sample != null) return sample;
		}
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
		g.fill(left, top + 30, left + w, top + 31, 0xFF3A3A55);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		switch (tab) {
			case SNIPES -> renderSnipes(g);
			case ECONOMY -> renderEconomy(g);
			case PURCHASES -> renderPurchases(g);
			case SETTINGS -> renderSettings(g);
		}
		if (tab == Tab.SNIPES || tab == Tab.PURCHASES) {
			int count = tab == Tab.SNIPES ? SniperConfig.get().entries.size() : EconomyData.get().purchases.size();
			int pages = Math.max(1, (count + rowsPerPage - 1) / rowsPerPage);
			int page = tab == Tab.SNIPES ? snipePage : purchasePage;
			g.drawCenteredString(this.font, Component.literal((page + 1) + "/" + pages), left + w - 44, top + h - 20, 0xFFAAAAAA);
		}
		String status = AhSniperClient.SNIPER.isRunning() ? "§aSniping" : AhSniperClient.SNIPER.isScanning() ? "§eScanning" : "";
		if (!status.isEmpty()) g.drawString(this.font, status, left + w - 150, top + 12, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int dir = scrollY < 0 ? 1 : scrollY > 0 ? -1 : 0;
		if (dir == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		switch (tab) {
			case SNIPES -> {
				int pages = Math.max(1, (SniperConfig.get().entries.size() + rowsPerPage - 1) / rowsPerPage);
				int p = Math.max(0, Math.min(pages - 1, snipePage + dir));
				if (p != snipePage) { snipePage = p; rebuildWidgets(); }
			}
			case PURCHASES -> {
				int pages = Math.max(1, (EconomyData.get().purchases.size() + rowsPerPage - 1) / rowsPerPage);
				int p = Math.max(0, Math.min(pages - 1, purchasePage + dir));
				if (p != purchasePage) { purchasePage = p; rebuildWidgets(); }
			}
			case ECONOMY -> {
				if (mouseX < left + LIST_W + 12) {
					int pages = Math.max(1, (econResults.size() + rowsPerPage - 1) / rowsPerPage);
					int p = Math.max(0, Math.min(pages - 1, econPage + dir));
					if (p != econPage) { econPage = p; refreshEconomy(); }
				}
			}
			default -> {}
		}
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
