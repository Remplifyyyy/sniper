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

/**
 * Add a held item to the snipe list (press K) or edit an existing entry (Edit in the Snipe List).
 * Max price, price per item / whole listing, how many to buy, budget, lore filter, strict matching.
 */
public class AddSnipeScreen extends Screen {
	private final ItemStack stack;
	private final SnipeEntry existing;
	private final boolean anyMode;
	private final Screen parent;

	private EditBox priceBox;
	private EditBox amountBox;
	private EditBox budgetBox;
	private EditBox loreBox;
	private boolean perItem;
	private boolean strict = true;
	private String error = "";
	/** "Any item" blacklist being edited (the entry's own list when editing, a new one when adding). */
	private List<SnipeEntry.Blocked> blacklist = new ArrayList<>();

	private int x0, y0;

	/** Adding the item you're holding. */
	public AddSnipeScreen(ItemStack stack) {
		this(stack, null);
	}

	public AddSnipeScreen(ItemStack stack, Screen parent) {
		super(Component.literal("Add to AH Sniper"));
		this.stack = stack.copy();
		this.existing = null;
		this.parent = parent;
		this.anyMode = false;
	}

	/** New "Any item" entry: buys every buy-now listing under the price. */
	public static AddSnipeScreen anyItem(Screen parent) {
		return new AddSnipeScreen(parent);
	}

	private AddSnipeScreen(Screen parent) {
		super(Component.literal("Snipe ANY item"));
		this.stack = new ItemStack(Items.CHEST);
		this.existing = null;
		this.parent = parent;
		this.anyMode = true;
		this.strict = false;
	}

	/** Editing an entry already on the snipe list. */
	public AddSnipeScreen(SnipeEntry existing, Screen parent) {
		super(Component.literal("Edit snipe"));
		this.existing = existing;
		this.parent = parent;
		this.perItem = existing.perItem;
		this.strict = existing.strict;
		this.anyMode = existing.anyItem;
		if (existing.blacklist == null) existing.blacklist = new ArrayList<>();
		this.blacklist = existing.blacklist;
		ItemStack icon;
		try {
			if (existing.anyItem) throw new IllegalStateException();
			icon = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(existing.itemId)));
		} catch (Exception e) {
			icon = existing.anyItem ? new ItemStack(Items.CHEST) : ItemStack.EMPTY;
		}
		this.stack = icon;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}

	private String name() {
		if (anyMode) return "Any item §7(every buy-now listing)";
		return existing != null ? existing.name : SnipeEntry.displayName(stack);
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		x0 = cx - 115;
		y0 = Math.max(4, this.height / 2 - 112);
		int bx = x0 + 80;
		int y = y0 + 36;

		// remember what was typed if the window is resized
		String price = priceBox != null ? priceBox.getValue() : existing != null ? SnipeEntry.formatPrice(existing.maxPrice) : "";
		String amount = amountBox != null ? amountBox.getValue() : existing != null && existing.maxAmount > 0 ? String.valueOf(existing.maxAmount) : "";
		String budget = budgetBox != null ? budgetBox.getValue() : existing != null && existing.budget > 0 ? SnipeEntry.formatPrice(existing.budget) : "";
		String lore = loreBox != null ? loreBox.getValue() : existing != null && existing.loreFilter != null ? existing.loreFilter : "";

		priceBox = new EditBox(this.font, bx, y + 1, 96, 18, Component.literal("Max price"));
		priceBox.setMaxLength(32);
		priceBox.setHint(Component.literal("e.g. 50000, 1.5m"));
		priceBox.setValue(price);
		addRenderableWidget(priceBox);
		Button each = Button.builder(Component.literal(perItem ? "each" : "total"), b -> {
			perItem = !perItem;
			b.setMessage(Component.literal(perItem ? "each" : "total"));
		}).bounds(bx + 98, y, 52, 20).build();
		each.setTooltip(Tooltip.create(Component.literal("total = max price for the whole listing. each = max price per item, so a stack of 8 may cost up to 8x.")));
		addRenderableWidget(each);
		y += 24;

		amountBox = new EditBox(this.font, bx, y + 1, 150, 18, Component.literal("Amount"));
		amountBox.setMaxLength(9);
		amountBox.setHint(Component.literal("blank = no limit"));
		amountBox.setValue(amount);
		amountBox.setTooltip(Tooltip.create(Component.literal("Stop after buying this many items. Stacks count by size (a stack of 3 counts as 3), and a stack that would go over the amount is skipped.")));
		addRenderableWidget(amountBox);
		y += 24;

		budgetBox = new EditBox(this.font, bx, y + 1, 150, 18, Component.literal("Budget"));
		budgetBox.setMaxLength(32);
		budgetBox.setHint(Component.literal("blank = no budget"));
		budgetBox.setValue(budget);
		budgetBox.setTooltip(Tooltip.create(Component.literal("Stop once this many coins have been spent on this item. A listing that would go over the budget is skipped.")));
		addRenderableWidget(budgetBox);
		y += 24;

		loreBox = new EditBox(this.font, bx, y + 1, 150, 18, Component.literal("Lore filter"));
		loreBox.setMaxLength(64);
		loreBox.setHint(Component.literal("optional"));
		loreBox.setValue(lore);
		loreBox.setTooltip(Tooltip.create(Component.literal("Only buy listings whose lore contains this text, e.g. Sharpness V.")));
		addRenderableWidget(loreBox);
		y += 24;

		Button strictBtn = Button.builder(Component.literal(strictLabel()), b -> {
			strict = !strict;
			b.setMessage(Component.literal(strictLabel()));
		}).bounds(bx, y, 150, 20).build();
		strictBtn.setTooltip(Tooltip.create(Component.literal("Strict: same name, type, lore lines and hidden item data, so renamed vanilla items can't match. Loose: type + name only.")));
		if (anyMode) {
			Button bl = Button.builder(Component.literal("Blacklist (" + blacklist.size() + ")..."),
					b -> this.minecraft.setScreen(new BlacklistScreen(blacklist, this))).bounds(bx, y, 150, 20).build();
			bl.setTooltip(Tooltip.create(Component.literal("Items this Any item snipe will never buy. Search any item, or block one you're holding.")));
			addRenderableWidget(bl);
		} else {
			addRenderableWidget(strictBtn);
		}
		y += 24;

		if (existing != null) {
			Button reset = Button.builder(Component.literal("Reset"), b -> {
				existing.resetProgress();
				SniperConfig.save();
			}).bounds(bx + 100, y, 50, 20).build();
			reset.setTooltip(Tooltip.create(Component.literal("Set bought / spent back to 0 so the amount and budget start again.")));
			addRenderableWidget(reset);
			y += 24;
		}

		addRenderableWidget(Button.builder(Component.literal(existing != null ? "Save" : "Add"), b -> save())
				.bounds(x0, y + 4, 113, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(x0 + 117, y + 4, 113, 20).build());

		setInitialFocus(priceBox);
	}

	private String strictLabel() {
		return strict ? "Matching: Strict" : "Matching: §6Loose";
	}

	private void save() {
		long price = SnipeEntry.parsePrice(priceBox.getValue());
		if (price <= 0) {
			error = "Enter a max price, e.g. 25000, 250k or 1.5m";
			return;
		}
		String amountText = amountBox.getValue().strip();
		long amount = amountText.isEmpty() ? 0 : SnipeEntry.parsePrice(amountText);
		if (amount < 0) {
			error = "Amount must be a number, or blank for no limit";
			return;
		}
		String budgetText = budgetBox.getValue().strip();
		long budget = budgetText.isEmpty() ? 0 : SnipeEntry.parsePrice(budgetText);
		if (budget < 0) {
			error = "Budget must be a price like 10m, or blank for none";
			return;
		}

		SnipeEntry entry = existing != null ? existing : anyMode ? SnipeEntry.any(price) : new SnipeEntry(stack, price);
		entry.maxPrice = price;
		entry.perItem = perItem;
		entry.strict = anyMode ? false : strict;
		entry.maxAmount = (int) Math.min(Integer.MAX_VALUE, amount);
		entry.budget = budget;
		String lore = loreBox.getValue().strip();
		entry.loreFilter = lore.isEmpty() ? null : lore;
		if (anyMode) entry.blacklist = blacklist;
		if (existing == null) SniperConfig.get().entries.add(entry);
		SniperConfig.save();
		AhSniperClient.msg((existing != null ? "§aSaved §f" : "§aAdded §f") + entry.describe());
		onClose();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(this.font, "§l" + this.title.getString(), x0, y0, 0xFFFFFFFF);
		g.renderItem(stack, x0, y0 + 13);
		g.drawString(this.font, this.font.plainSubstrByWidth(name(), 210), x0 + 20, y0 + 17, 0xFF55FFFF);

		int y = y0 + 42;
		for (String label : new String[] { "Max price", "Buy amount", "Budget", "Lore filter", anyMode ? "Never buy" : "" }) {
			g.drawString(this.font, label, x0, y, 0xFFDDDDDD);
			y += 24;
		}
		if (existing != null) {
			String progress = existing.progressText();
			String text = progress.isEmpty() ? "§7Bought " + existing.bought + " so far" : "§7" + progress;
			g.drawString(this.font, this.font.plainSubstrByWidth(text, 175), x0, y + 6, 0xFFFFFFFF);
			y += 24;
		}
		if (!error.isEmpty()) {
			g.drawString(this.font, error, x0, y + 28, 0xFFFF5555);
		} else if (anyMode) {
			g.drawString(this.font, budgetBox.getValue().isBlank()
					? "§cTip: set a budget so it can't spend all your coins"
					: "§6Buys ANY buy-now listing under this price", x0, y + 28, 0xFFFFFFFF);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
