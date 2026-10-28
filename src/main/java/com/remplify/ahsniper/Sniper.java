package com.remplify.ahsniper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs once per client tick.
 *
 * Start-up sweep (optional): open /ah -> check every listing on every page, buying matches -> reopen /ah.
 * Sniping: open /ah -> click the sort repeater (newest first) -> read every listing's "Price:" lore ->
 * if a listing matches the snipe list under its max price, click it -> on "Auction | Buy Now"
 * re-check the price on the Confirm button and click it -> wait -> refresh.
 *
 * Scan all pages: open /ah -> record the page -> click the "next page" button -> repeat -> close.
 *
 * Press Esc (close the menu) at any point to stop either.
 * Separately, whenever any "Auction | Items" menu is open, its listings are recorded for the Economy tab.
 */
public class Sniper {
	private enum State { IDLE, WAIT_OPEN, SORTING, WAIT_UPDATE, SCAN, WAIT_CONFIRM, RETURN_TO_LIST, COOLDOWN, PAGE_READ, PAGE_WAIT, SWEEP_WAIT }

	private State state = State.IDLE;
	private boolean scanAllMode;
	private int timer;
	private int waitTicks;
	private int retries;
	private int lastContainerId = -1;
	private int pendingStateId;
	private int sortDone;
	private int pages;
	private boolean allowNullScreen;
	private boolean warnedNoRepeater;
	private long tick;
	private int buysThisRun;
	private long openedAtTick;

	private SnipeEntry target;
	private String targetGroup;
	private int targetCount;
	/** The actual item clicked (for "Any item" entries target.name is just "Any item"). */
	private String targetItemId;
	private String targetName;
	/**
	 * Listings that failed to buy (price changed, wrong item, gone) are skipped until this tick,
	 * so the sniper doesn't loop on them; after "Full rescan" seconds every listing is checked again.
	 * Successful buys are NOT remembered, so several identical listings (same seller, same price)
	 * listed at once are all bought one after another.
	 */
	private final Map<String, Long> attempted = new HashMap<>();
	private String currentKey;
	/** Set after clicking Confirm: how many copies of that listing were on page 1 before buying. */
	private String verifyKey;
	private int verifyCount;
	private int keyCountBeforeClick;
	private SnipeEntry verifyEntry;
	private long verifyPrice;
	private int verifyItems;

	/** Start-up sweep: every AH page is checked once before watching page 1 for new listings. */
	private boolean sweeping;
	private int sweepPages;
	private int sweepBuys;


	private boolean debugArmed;
	private int debugDelay;

	public boolean isRunning() {
		return state != State.IDLE && !scanAllMode;
	}

	public boolean isScanning() {
		return state != State.IDLE && scanAllMode;
	}

	public boolean isBusy() {
		return state != State.IDLE;
	}

	public void toggle() {
		if (isBusy()) stop("Stopped.");
		else start();
	}

	public void start() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || isBusy()) return;
		List<SnipeEntry> entries = SniperConfig.get().entries;
		long active = entries.stream().filter(e -> e.enabled && !e.isDone()).count();
		if (active == 0) {
			if (entries.stream().anyMatch(e -> e.enabled))
				AhSniperClient.msg("§cEvery item has reached its amount/budget limit. Reset them in the menu's Snipe List.");
			else
				AhSniperClient.msg("§cYour snipe list is empty. Hold an item and press K, or add one from the menu.");
			return;
		}
		attempted.clear();
		buysThisRun = 0;
		warnedNoRepeater = false;
		scanAllMode = false;
		sweeping = SniperConfig.get().sweepOnStart;
		sweepPages = 0;
		sweepBuys = 0;
		AhSniperClient.msg("§aStarted§f, watching " + active + " item(s)"
				+ (sweeping ? ". Checking every AH page first" : "") + ". Press §eEsc§f in the AH to stop.");
		openAh(mc, true);
	}

	public void startScanAll() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || isBusy()) return;
		scanAllMode = true;
		pages = 0;
		AhSniperClient.msg("§aScanning every AH page for prices§f... press §eEsc§f to stop.");
		openAh(mc, true);
	}

	public void stop(String reason) {
		if (state == State.IDLE) return;
		state = State.IDLE;
		scanAllMode = false;
		sweeping = false;
		AhSniperClient.msg("§c" + reason);
	}

	public void armDebug() {
		debugArmed = true;
		debugDelay = 15;
	}

	// ------------------------------------------------------------------

	public void tick(Minecraft mc) {
		tick++;
		Screen screen = mc.screen;

		if (SniperConfig.get().recordPrices && tick % 5 == 0) {
			AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
			if (ah != null) EconomyData.get().record(ah.getMenu(), containerSize(ah.getMenu()), mc.getUser().getName());
		}
		EconomyData.get().tick();

		if (debugArmed) runDebug(screen);
		if (state == State.IDLE) return;

		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			state = State.IDLE;
			scanAllMode = false;
			return;
		}
		SniperConfig cfg = SniperConfig.get();
		if (cfg.stopOnDamage && player.hurtTime > 0) {
			stop("Stopped: you took damage.");
			return;
		}
		if (screen == null && !allowNullScreen) {
			stop("Stopped (menu closed).");
			return;
		}
		if (timer > 0) {
			timer--;
			return;
		}

		switch (state) {
			case WAIT_OPEN -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah != null && ah.getMenu().containerId != lastContainerId) {
					allowNullScreen = false;
					retries = 0;
					sortDone = 0;
					openedAtTick = tick;
					if (scanAllMode) {
						state = State.PAGE_READ;
						timer = cfg.settleTicks;
					} else if (sweeping) {
						state = State.SCAN; // order doesn't matter when every page is checked
						timer = cfg.settleTicks;
					} else {
						state = State.SORTING;
						timer = cfg.clickDelay;
					}
				} else if (++waitTicks > 40) {
					if (++retries >= 3) {
						stop("Stopped: /ah didn't open.");
					} else {
						openAh(mc, screen == null);
					}
				}
			}
			case SORTING -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah == null) { stop("Stopped: unexpected menu."); return; }
				if (sortDone >= cfg.sortClicks) {
					state = State.SCAN;
					timer = cfg.settleTicks;
					return;
				}
				int slot = findSlot(ah.getMenu(), s -> s.is(Items.REPEATER));
				if (slot < 0) {
					if (!warnedNoRepeater) AhSniperClient.msg("§eCouldn't find the sort button, scanning without sorting.");
					warnedNoRepeater = true;
					state = State.SCAN;
					return;
				}
				pendingStateId = ah.getMenu().getStateId();
				click(mc, ah.getMenu(), slot);
				sortDone++;
				waitTicks = 0;
				state = State.WAIT_UPDATE;
			}
			case WAIT_UPDATE -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah == null) { stop("Stopped: unexpected menu."); return; }
				if (ah.getMenu().getStateId() != pendingStateId || ++waitTicks > 20) {
					state = State.SORTING;
					timer = cfg.clickDelay;
				}
			}
			case SCAN -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah == null) { stop("Stopped: unexpected menu."); return; }
				scan(mc, ah.getMenu(), cfg);
			}
			case WAIT_CONFIRM -> {
				AbstractContainerScreen<?> buy = screenWithTitle(screen, "auction", "buy");
				if (buy == null) {
					boolean stillOnList = screenWithTitle(screen, "auction", "items") != null;
					if (++waitTicks > (stillOnList ? 15 : 60)) {
						AhSniperClient.msg("§7Listing was gone before the confirm menu opened.");
						skipFailed(cfg);
						// still on the (sorted) list: just keep scanning instead of reopening
						state = stillOnList ? State.SCAN : State.COOLDOWN;
					}
					return;
				}
				confirm(mc, buy.getMenu(), cfg);
			}
			case RETURN_TO_LIST -> {
				// After a buy (or backing out) the AH returns to the item list with the "newest" sort
				// still on, so don't touch the repeater: just rescan the whole first page.
				AbstractContainerScreen<?> list = screenWithTitle(screen, "auction", "items");
				if (list != null) {
					allowNullScreen = false;
					state = State.SCAN;
					timer = cfg.settleTicks;
				} else if (++waitTicks > 30) {
					openAh(mc, screen == null); // never came back by itself: open it fresh (this one does need sorting)
				}
			}
			case COOLDOWN -> openAh(mc, screen == null);
			case PAGE_READ -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah == null) { stop("Stopped: unexpected menu."); return; }
				AbstractContainerMenu menu = ah.getMenu();
				EconomyData.get().record(menu, containerSize(menu), mc.getUser().getName());
				pages++;
				int next = findNavButton(menu, "next");
				if (next < 0 || pages >= 200) {
					finishScan(player, pages == 1 && next < 0
							? "§eOnly 1 page read (no 'Next page' button found)."
							: "§aScanned " + pages + " page(s).");
					return;
				}
				pendingStateId = menu.getStateId();
				click(mc, menu, next);
				waitTicks = 0;
				state = State.PAGE_WAIT;
			}
			case SWEEP_WAIT -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah == null) { stop("Stopped: unexpected menu."); return; }
				if (ah.getMenu().getStateId() != pendingStateId) {
					state = State.SCAN;
					timer = cfg.settleTicks;
				} else if (++waitTicks > 40) {
					finishSweep(mc); // next page never loaded: treat it as the last page
				}
			}
			case PAGE_WAIT -> {
				AbstractContainerScreen<?> ah = screenWithTitle(screen, "auction", "items");
				if (ah == null) { stop("Stopped: unexpected menu."); return; }
				if (ah.getMenu().getStateId() != pendingStateId) {
					state = State.PAGE_READ;
					timer = cfg.settleTicks;
				} else if (++waitTicks > 40) {
					finishScan(player, "§aScanned " + pages + " page(s).");
				}
			}
			default -> {}
		}
	}

	private void finishScan(LocalPlayer player, String message) {
		state = State.IDLE;
		scanAllMode = false;
		EconomyData.get().save();
		player.closeContainer();
		AhSniperClient.msg(message + " Price data saved, open the menu's Economy tab.");
	}

	private void scan(Minecraft mc, AbstractContainerMenu menu, SniperConfig cfg) {
		String me = mc.getUser().getName();
		attempted.values().removeIf(expiry -> expiry < tick);

		// Did the last buy actually go through? If the same listing is still there just as many times,
		// it didn't (e.g. not enough coins): skip it for a bit instead of trying again and again.
		if (verifyKey != null) {
			int now = countKey(menu, verifyKey, me);
			if (now >= verifyCount) {
				attempted.put(verifyKey, tick + Math.max(1, cfg.rescanSeconds) * 20L);
				EconomyData.get().removeLatestPurchase();
				if (verifyEntry != null) {
					verifyEntry.undoBuy(verifyItems, verifyPrice);
					SniperConfig.save();
				}
				AhSniperClient.msg("§cThat buy didn't go through (not enough coins, or someone else got it first?).");
			}
			verifyKey = null;
		}

		int size = containerSize(menu);
		for (int i = 0; i < size; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.isEmpty()) continue;
			if (!SnipeEntry.isBuyNowListing(stack)) continue; // bids, buttons and borders are never clicked
			String priceText = SnipeEntry.loreValue(stack, "Price:");
			if (priceText == null) continue;
			long price = SnipeEntry.parsePrice(priceText);
			if (price < 0) continue;
			String seller = SnipeEntry.loreValue(stack, "Seller:");
			if (seller != null && seller.equalsIgnoreCase(me)) continue;

			for (SnipeEntry e : cfg.entries) {
				if (!e.enabled || e.isDone() || price > e.limitFor(stack.getCount())
						|| !e.fitsLimits(stack.getCount(), price) || !e.matches(stack)) continue;
				String key = listingKey(stack);
				if (attempted.containsKey(key)) continue;
				currentKey = key;
				keyCountBeforeClick = countKey(menu, key, me);

				target = e;
				targetGroup = EconomyData.groupKey(stack);
				targetItemId = SnipeEntry.itemId(stack);
				targetName = SnipeEntry.displayName(stack);
				targetCount = stack.getCount();
				AhSniperClient.msg("§bFound §f" + SnipeEntry.displayName(stack) + " x" + stack.getCount() + " for §e"
						+ SnipeEntry.formatPrice(price) + "§f (max " + SnipeEntry.formatPrice(e.limitFor(stack.getCount())) + "), buying...");
				click(mc, menu, i);
				waitTicks = 0;
				state = State.WAIT_CONFIRM;
				return;
			}
		}

		if (sweeping) {
			// nothing (more) to buy on this page: go to the next one, or finish the sweep
			int next = findNavButton(menu, "next");
			sweepPages++;
			if (next < 0 || sweepPages >= 300) {
				finishSweep(mc);
				return;
			}
			pendingStateId = menu.getStateId();
			click(mc, menu, next);
			waitTicks = 0;
			state = State.SWEEP_WAIT;
			return;
		}

		if (cfg.stayOpen && !(cfg.fullRefreshSeconds > 0 && tick - openedAtTick > cfg.fullRefreshSeconds * 20L)) {
			// The AH keeps its "newest" sort while it stays open, so look again next tick.
			state = State.SCAN;
			timer = 0;
		} else {
			state = State.COOLDOWN;
			timer = cfg.stayOpen ? 0 : cfg.refreshTicks;
		}
	}

	private void confirm(Minecraft mc, AbstractContainerMenu menu, SniperConfig cfg) {
		int confirmSlot = findSlot(menu, s -> SnipeEntry.displayName(s).toLowerCase(Locale.ROOT).contains("confirm"));
		if (confirmSlot < 0) {
			if (++waitTicks > 60) {
				AhSniperClient.msg("§cNo Confirm button found, skipping.");
				backToList(mc, menu);
			}
			return;
		}

		// Safety: the price on the Confirm button must still be within the max.
		ItemStack confirmStack = menu.slots.get(confirmSlot).getItem();
		long confirmPrice = -1;
		for (String line : SnipeEntry.loreLines(confirmStack)) {
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.contains("purchase for")) {
				confirmPrice = SnipeEntry.parsePrice(line.substring(lower.indexOf("purchase for")));
				break;
			}
		}
		if (confirmPrice < 0 || confirmPrice > target.limitFor(targetCount)) {
			AhSniperClient.msg("§cConfirm price " + (confirmPrice < 0 ? "unreadable" : SnipeEntry.formatPrice(confirmPrice))
					+ " is above your max, not buying.");
			backToList(mc, menu);
			return;
		}

		if (!target.fitsLimits(targetCount, confirmPrice)) {
			AhSniperClient.msg("§cThat would go over the amount/budget limit for " + target.name + ", not buying.");
			backToList(mc, menu);
			return;
		}

		// Safety: the item shown in the confirm menu must be the one we meant to buy.
		int itemSlot = findSlot(menu, s -> SnipeEntry.itemId(s).equals(targetItemId)
				&& SnipeEntry.displayName(s).equalsIgnoreCase(targetName));
		if (itemSlot < 0 || itemSlot == confirmSlot) {
			AhSniperClient.msg("§cItem in the confirm menu doesn't match, not buying.");
			backToList(mc, menu);
			return;
		}

		click(mc, menu, confirmSlot);
		buysThisRun++;
		EconomyData.get().addPurchase(targetGroup, targetName, targetItemId, confirmPrice, targetCount);
		verifyKey = currentKey;
		verifyCount = keyCountBeforeClick;
		verifyEntry = target;
		verifyPrice = confirmPrice;
		verifyItems = targetCount;
		target.recordBuy(targetCount, confirmPrice);
		SniperConfig.save();
		if (sweeping) sweepBuys++;
		String progress = target.progressText();
		AhSniperClient.msg("§aBought §f" + targetName + (target.anyItem ? " §7(any item)§f" : "") + " for §e" + SnipeEntry.formatPrice(confirmPrice) + "§f coins."
				+ (progress.isEmpty() ? "" : " §7(" + progress + ")"));
		if (target.isDone()) {
			AhSniperClient.msg("§e" + target.name + " reached its limit, no longer buying it.");
			if (cfg.entries.stream().noneMatch(e -> e.enabled && !e.isDone())) {
				state = State.IDLE;
				sweeping = false;
				AhSniperClient.msg("§eEvery item has reached its limit, stopping.");
				return;
			}
		}
		if (cfg.maxBuysPerRun > 0 && buysThisRun >= cfg.maxBuysPerRun) {
			state = State.IDLE;
			sweeping = false;
			AhSniperClient.msg("§eReached " + cfg.maxBuysPerRun + " buys this run, stopping.");
			return;
		}
		state = State.RETURN_TO_LIST;
		allowNullScreen = true; // the server may close the menu after a purchase
		waitTicks = 0;
		timer = 1;
	}

	/** Sweep done: reopen /ah fresh, sort by newest and start watching page 1. */
	private void finishSweep(Minecraft mc) {
		sweeping = false;
		AhSniperClient.msg("§aChecked " + sweepPages + " page(s)" + (sweepBuys > 0 ? ", bought " + sweepBuys + " item(s)" : ", nothing under your prices")
				+ ".§f Now watching for new listings.");
		openAh(mc, mc.screen == null);
	}

	private static String listingKey(ItemStack s) {
		return EconomyData.groupKey(s) + "|" + SnipeEntry.loreValue(s, "Seller:") + "|"
				+ SnipeEntry.loreValue(s, "Price:") + "|" + s.getCount();
	}

	/** How many buy-now listings on the page have this identity (same item, seller, price, amount). */
	private static int countKey(AbstractContainerMenu menu, String key, String me) {
		int n = 0;
		int size = containerSize(menu);
		for (int i = 0; i < size; i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (!s.isEmpty() && SnipeEntry.isBuyNowListing(s) && key.equals(listingKey(s))) n++;
		}
		return n;
	}

	private void skipFailed(SniperConfig cfg) {
		if (currentKey != null) attempted.put(currentKey, tick + Math.max(1, cfg.rescanSeconds) * 20L);
	}

	/** Leave the Buy Now screen without buying: click its Cancel/Back button so the list comes back. */
	private void backToList(Minecraft mc, AbstractContainerMenu menu) {
		skipFailed(SniperConfig.get());
		int cancel = findSlot(menu, s -> {
			String n = SnipeEntry.displayName(s).toLowerCase(Locale.ROOT);
			return n.contains("cancel") || n.contains("back") || n.contains("deny");
		});
		if (cancel >= 0) {
			click(mc, menu, cancel);
			allowNullScreen = true;
			waitTicks = 0;
			timer = 1;
			state = State.RETURN_TO_LIST;
		} else {
			state = State.COOLDOWN; // no way back: reopen /ah
		}
	}

	// ------------------------------------------------------------------ helpers

	private void openAh(Minecraft mc, boolean fromNoScreen) {
		LocalPlayer player = mc.player;
		if (player == null) return;
		lastContainerId = (mc.screen instanceof AbstractContainerScreen<?> c) ? c.getMenu().containerId : -1;
		allowNullScreen = fromNoScreen || mc.screen == null;
		player.connection.sendCommand("ah");
		waitTicks = 0;
		state = State.WAIT_OPEN;
	}

	static void click(Minecraft mc, AbstractContainerMenu menu, int slot) {
		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
	}

	static AbstractContainerScreen<?> screenWithTitle(Screen screen, String... words) {
		if (!(screen instanceof AbstractContainerScreen<?> c)) return null;
		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		for (String w : words) if (!title.contains(w)) return null;
		return c;
	}

	static int containerSize(AbstractContainerMenu menu) {
		if (menu instanceof ChestMenu chest) return chest.getRowCount() * 9;
		return Math.max(0, menu.slots.size() - 36);
	}

	interface StackTest { boolean test(ItemStack s); }

	/** A page button (not a listing) whose name contains the word, e.g. "next" or "prev". */
	static int findNavButton(AbstractContainerMenu menu, String word) {
		return findSlot(menu, s -> SnipeEntry.displayName(s).toLowerCase(Locale.ROOT).contains(word)
				&& SnipeEntry.loreValue(s, "Price:") == null && SnipeEntry.loreValue(s, "Seller:") == null);
	}

	static int findSlot(AbstractContainerMenu menu, StackTest test) {
		int size = containerSize(menu);
		for (int i = 0; i < size; i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (!s.isEmpty() && test.test(s)) return i;
		}
		return -1;
	}

	/** Debug: next time an auction menu opens, print the buttons and the first listings as the mod reads them. */
	private void runDebug(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> c)) return;
		if (!screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("auction")) return;
		if (--debugDelay > 0) return;
		debugArmed = false;

		AbstractContainerMenu menu = c.getMenu();
		AhSniperClient.msg("§eDebug for menu '" + screen.getTitle().getString() + "' (" + containerSize(menu) + " slots):");
		StringBuilder buttons = new StringBuilder();
		int shown = 0;
		for (int i = 0; i < containerSize(menu); i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (s.isEmpty()) continue;
			boolean listing = SnipeEntry.loreValue(s, "Price:") != null;
			if (!listing) {
				String n = SnipeEntry.displayName(s);
				if (!n.isBlank() && !s.is(Items.GRAY_STAINED_GLASS_PANE) && !s.is(Items.BLACK_STAINED_GLASS_PANE)) {
					buttons.append("§7#").append(i).append(" §f").append(n).append("  ");
				}
			}
			if (!(s.is(Items.REPEATER) || (listing && shown < 2))) continue;
			if (listing) shown++;
			AhSniperClient.msg("§7#" + i + " §f" + SnipeEntry.displayName(s) + " §7(" + SnipeEntry.itemId(s) + ")");
			List<String> lore = SnipeEntry.loreLines(s);
			for (String line : lore) AhSniperClient.msg("§8  | §7" + line);
			List<String> keys = SnipeEntry.customDataKeys(s);
			AhSniperClient.msg("§8  hidden data keys: §7" + (keys.isEmpty() ? "none" : String.join(", ", keys)));
			if (listing) AhSniperClient.msg("§8  -> " + (SnipeEntry.isBuyNowListing(s) ? "§abuy-now listing" : "§cBID / ignored"));
			if (listing) AhSniperClient.msg("§8  -> parsed price: §e"
					+ SnipeEntry.formatPrice(SnipeEntry.parsePrice(SnipeEntry.loreValue(s, "Price:"))));
		}
		AhSniperClient.msg("§eButtons: " + (buttons.length() == 0 ? "§7none" : buttons.toString()));
	}
}
