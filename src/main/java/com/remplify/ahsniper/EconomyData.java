package com.remplify.ahsniper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Price tracker. Every listing the mod sees in "Auction | Items" (while sniping, while you browse /ah
 * yourself, or during "Scan all pages") is recorded once per seller+price+amount. Stats are worked out
 * per item on the per-item price, so a stack of 64 TNT and a single TNT are comparable.
 * Saved to config/remplify-ahsniper-economy.json; listings older than 7 days are dropped.
 */
public class EconomyData {
	public static final long HOUR = 3_600_000L;
	public static final long DAY = 24 * HOUR;
	public static final long WEEK = 7 * DAY;

	private static final Gson GSON = new GsonBuilder().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("remplify-ahsniper-economy.json");

	// ---- saved data ----
	public static class Group {
		public String key;
		public String itemId;
		public String name;
		public boolean custom;
		public List<String> lore = new ArrayList<>();
		public List<String> keys = new ArrayList<>();
	}

	public static class Listing {
		public String group;
		public String seller;
		public long price;
		public int count;
		public long first;
		public long last;

		public double unit() {
			return (double) price / Math.max(1, count);
		}
	}

	public static class Purchase {
		public long time;
		public String name;
		public String itemId;
		public long price;
		public int count;
		/** Average per-item price (24h) when it was bought, 0 if unknown. */
		public double avgUnit;
	}

	public Map<String, Group> groups = new LinkedHashMap<>();
	public List<Listing> listings = new ArrayList<>();
	public List<Purchase> purchases = new ArrayList<>();

	// ---- runtime only ----
	private transient Map<String, Listing> index;
	private transient Map<String, ItemStack> samples;
	private transient Map<String, Stats> statsCache;
	private transient boolean dirty;
	private transient long lastSave;
	private transient long lastPrune;

	private static EconomyData instance;

	public static EconomyData get() {
		if (instance == null) load();
		return instance;
	}

	// ------------------------------------------------------------------ recording

	public static String groupKey(ItemStack stack) {
		boolean custom = !SnipeEntry.descriptionLore(stack).isEmpty();
		return SnipeEntry.itemId(stack) + "|" + SnipeEntry.displayName(stack).toLowerCase(Locale.ROOT) + "|" + (custom ? "c" : "p");
	}

	/** Reads every listing in an open "Auction | Items" menu. */
	public void record(AbstractContainerMenu menu, int size, String me) {
		long now = System.currentTimeMillis();
		for (int i = 0; i < size && i < menu.slots.size(); i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.isEmpty()) continue;
			if (!SnipeEntry.isBuyNowListing(stack)) continue; // skip bids
			String priceText = SnipeEntry.loreValue(stack, "Price:");
			if (priceText == null) continue;
			long price = SnipeEntry.parsePrice(priceText);
			if (price <= 0) continue;
			String seller = SnipeEntry.loreValue(stack, "Seller:");
			if (seller == null) seller = "?";
			recordListing(stack, seller, price, now, me);
		}
	}

	private void recordListing(ItemStack stack, String seller, long price, long now, String me) {
		String gk = groupKey(stack);
		Group g = groups.get(gk);
		if (g == null) {
			g = new Group();
			g.key = gk;
			g.itemId = SnipeEntry.itemId(stack);
			g.name = SnipeEntry.displayName(stack);
			g.custom = gk.endsWith("|c");
			groups.put(gk, g);
		}
		// keep the snipe fingerprint of the most recent copy seen
		g.lore = SnipeEntry.descriptionLore(stack);
		g.keys = SnipeEntry.customDataKeys(stack);
		samples().put(gk, stack.copy());

		String lk = gk + "|" + seller.toLowerCase(Locale.ROOT) + "|" + price + "|" + stack.getCount();
		Listing l = index().get(lk);
		if (l == null) {
			l = new Listing();
			l.group = gk;
			l.seller = seller;
			l.price = price;
			l.count = stack.getCount();
			l.first = now;
			l.last = now;
			// deal alert, checked against what we knew before this listing
			if (!seller.equalsIgnoreCase(me)) checkDeal(g, l);
			listings.add(l);
			index().put(lk, l);
			statsCache().clear();
			dirty = true;
		} else if (now - l.last > 30_000) {
			l.last = now;
			statsCache().clear();
			dirty = true;
		}
	}

	private transient Set<String> alerted;

	private void checkDeal(Group g, Listing l) {
		SniperConfig cfg = SniperConfig.get();
		if (!cfg.dealAlerts) return;
		// Compared with the cheapest normal listing of the past 24h. Outliers (troll-cheap or
		// overpriced listings) are removed first so they don't move the bar either way.
		Stats s = stats(g.key, DAY);
		if (s.count < 5 || s.cleanLowest <= 0) return;
		double pct = 1.0 - l.unit() / s.cleanLowest;
		if (pct * 100 < cfg.dealPercent) return;
		String key = g.key + "|" + l.seller + "|" + l.price;
		if (alerted == null) alerted = new HashSet<>();
		if (!alerted.add(key)) return;
		AhSniperClient.msg("§aDeal: §f" + g.name + " x" + l.count + " for §e" + SnipeEntry.formatPrice(l.price)
				+ " §7(" + Math.round(pct * 100) + "% under the 24h lowest of " + fmt(s.cleanLowest) + " each, seller " + l.seller + ")");
	}

	public void addPurchase(String groupKey, String name, String itemId, long price, int count) {
		Purchase p = new Purchase();
		p.time = System.currentTimeMillis();
		p.name = name;
		p.itemId = itemId;
		p.price = price;
		p.count = count;
		Stats s = groupKey == null ? null : stats(groupKey, DAY);
		p.avgUnit = s == null ? 0 : s.average;
		purchases.add(0, p);
		while (purchases.size() > 500) purchases.remove(purchases.size() - 1);
		dirty = true;
		save();
	}

	// ------------------------------------------------------------------ stats

	public static class Stats {
		public int count;
		public int used;          // listings left after removing outliers
		public double lowest;     // per item
		public long lowestPrice;  // whole listing price of the lowest
		public int lowestCount;
		public String lowestSeller = "";
		public double cleanLowest;     // per item, lowest after removing outliers
		public long cleanLowestPrice;
		public int cleanLowestCount;
		public String cleanLowestSeller = "";
		public long lastSeen;
		public double average;    // per item, outliers removed
		public double median;
		public double highest;
		public int sellers;
		public long quantity;
		public double[] buckets = new double[0]; // average per time slice, oldest first, 0 = no data
	}

	public Stats stats(String groupKey, long window) {
		String ck = groupKey + "@" + window;
		Stats cached = statsCache().get(ck);
		if (cached != null) return cached;

		long now = System.currentTimeMillis();
		long from = now - window;
		List<Listing> in = new ArrayList<>();
		for (Listing l : listings) if (l.group.equals(groupKey) && l.last >= from) in.add(l);

		Stats s = new Stats();
		s.count = in.size();
		if (!in.isEmpty()) {
			double[] units = new double[in.size()];
			Set<String> sellers = new HashSet<>();
			s.lowest = Double.MAX_VALUE;
			for (int i = 0; i < in.size(); i++) {
				Listing l = in.get(i);
				units[i] = l.unit();
				sellers.add(l.seller.toLowerCase(Locale.ROOT));
				s.quantity += l.count;
				if (l.unit() < s.lowest) {
					s.lowest = l.unit();
					s.lowestPrice = l.price;
					s.lowestCount = l.count;
					s.lowestSeller = l.seller;
				}
				s.highest = Math.max(s.highest, l.unit());
				s.lastSeen = Math.max(s.lastSeen, l.last);
			}
			s.sellers = sellers.size();
			Arrays.sort(units);
			s.median = percentile(units, 0.5);
			double[] kept = removeOutliers(units);
			s.used = kept.length;
			double sum = 0;
			for (double v : kept) sum += v;
			s.average = kept.length == 0 ? s.median : sum / kept.length;

			double keptLo = kept.length == 0 ? s.lowest : kept[0];
			s.cleanLowest = Double.MAX_VALUE;
			for (Listing l : in) {
				if (l.unit() >= keptLo && l.unit() < s.cleanLowest) {
					s.cleanLowest = l.unit();
					s.cleanLowestPrice = l.price;
					s.cleanLowestCount = l.count;
					s.cleanLowestSeller = l.seller;
				}
			}
			if (s.cleanLowest == Double.MAX_VALUE) {
				s.cleanLowest = s.lowest;
				s.cleanLowestPrice = s.lowestPrice;
				s.cleanLowestCount = s.lowestCount;
				s.cleanLowestSeller = s.lowestSeller;
			}

			// chart: 12 slices for 1h (5 min), 24 for 24h (1h), 28 for 7d (6h)
			int n = window <= HOUR ? 12 : window <= DAY ? 24 : 28;
			long slice = window / n;
			double[] bsum = new double[n];
			int[] bcnt = new int[n];
			double lo = kept.length == 0 ? 0 : kept[0];
			double hi = kept.length == 0 ? Double.MAX_VALUE : kept[kept.length - 1];
			for (Listing l : in) {
				double u = l.unit();
				if (u < lo || u > hi) continue; // keep outliers off the chart too
				int b = (int) Math.min(n - 1, Math.max(0, (l.last - from) / slice));
				bsum[b] += u;
				bcnt[b]++;
			}
			s.buckets = new double[n];
			for (int i = 0; i < n; i++) s.buckets[i] = bcnt[i] == 0 ? 0 : bsum[i] / bcnt[i];
		}
		statsCache().put(ck, s);
		return s;
	}

	/** Interquartile-range filter: drops prices far below Q1 or far above Q3 (troll / typo listings). */
	static double[] removeOutliers(double[] sorted) {
		if (sorted.length < 4) return sorted;
		double q1 = percentile(sorted, 0.25);
		double q3 = percentile(sorted, 0.75);
		double iqr = q3 - q1;
		double lo = q1 - 1.5 * iqr;
		double hi = q3 + 1.5 * iqr;
		return Arrays.stream(sorted).filter(v -> v >= lo && v <= hi).toArray();
	}

	static double percentile(double[] sorted, double p) {
		if (sorted.length == 0) return 0;
		double pos = p * (sorted.length - 1);
		int i = (int) Math.floor(pos);
		int j = Math.min(sorted.length - 1, i + 1);
		return sorted[i] + (sorted[j] - sorted[i]) * (pos - i);
	}

	public enum Sort {
		MOST_LISTED("Most listed", "Sort: most listings first"),
		CHEAPEST("Cheapest", "Sort: lowest price first (lowest listing, outliers removed, per item)"),
		PRICIEST("Priciest", "Sort: highest price first (average, outliers removed, per item)"),
		NEWEST("Newest", "Sort: most recently seen on the AH first"),
		NAME("A-Z", "Sort: by name");
		public final String label, tip;
		Sort(String label, String tip) { this.label = label; this.tip = tip; }
		public Sort next() { return values()[(ordinal() + 1) % values().length]; }
	}

	/** Groups seen in the window whose name contains the search text, in the chosen order. */
	public List<Group> search(String text, long window, Sort sort) {
		String q = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
		long from = System.currentTimeMillis() - window;
		Map<String, Integer> counts = new HashMap<>();
		for (Listing l : listings) if (l.last >= from) counts.merge(l.group, 1, Integer::sum);
		List<Group> out = new ArrayList<>();
		for (Group g : groups.values()) {
			if (!counts.containsKey(g.key)) continue;
			if (!q.isEmpty() && !g.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			out.add(g);
		}
		switch (sort) {
			case CHEAPEST -> out.sort((a, b) -> Double.compare(stats(a.key, window).cleanLowest, stats(b.key, window).cleanLowest));
			case PRICIEST -> out.sort((a, b) -> Double.compare(stats(b.key, window).average, stats(a.key, window).average));
			case NEWEST -> out.sort((a, b) -> Long.compare(stats(b.key, window).lastSeen, stats(a.key, window).lastSeen));
			case NAME -> out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
			default -> out.sort((a, b) -> Integer.compare(counts.get(b.key), counts.get(a.key)));
		}
		return out;
	}

	public int listingCount(String groupKey, long window) {
		return stats(groupKey, window).count;
	}

	public ItemStack sample(String groupKey) {
		return samples().get(groupKey);
	}

	public void clearPrices() {
		listings.clear();
		groups.clear();
		index().clear();
		samples().clear();
		statsCache().clear();
		dirty = true;
		save();
	}

	/** Undo the last logged purchase when the sniper finds the buy didn't actually happen. */
	public void removeLatestPurchase() {
		if (!purchases.isEmpty()) {
			purchases.remove(0);
			dirty = true;
			save();
		}
	}

		public void clearPurchases() {
		purchases.clear();
		dirty = true;
		save();
	}

	// ------------------------------------------------------------------ housekeeping

	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastPrune > 10 * 60_000L) {
			lastPrune = now;
			prune(now);
		}
		if (dirty && now - lastSave > 30_000L) save();
	}

	private void prune(long now) {
		int before = listings.size();
		listings.removeIf(l -> l.last < now - WEEK);
		if (listings.size() != before) {
			Set<String> used = new HashSet<>();
			for (Listing l : listings) used.add(l.group);
			groups.keySet().removeIf(k -> !used.contains(k));
			rebuildIndex();
			statsCache().clear();
			dirty = true;
		}
	}

	private void rebuildIndex() {
		index = new HashMap<>();
		for (Listing l : listings) {
			index.put(l.group + "|" + l.seller.toLowerCase(Locale.ROOT) + "|" + l.price + "|" + l.count, l);
		}
	}

	private Map<String, Listing> index() {
		if (index == null) rebuildIndex();
		return index;
	}

	private Map<String, ItemStack> samples() {
		if (samples == null) samples = new HashMap<>();
		return samples;
	}

	private Map<String, Stats> statsCache() {
		if (statsCache == null) statsCache = new HashMap<>();
		return statsCache;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH)) {
					instance = GSON.fromJson(r, EconomyData.class);
				}
			}
		} catch (Exception e) {
			AhSniperClient.LOGGER.error("Could not read AH price data, starting fresh", e);
		}
		if (instance == null) instance = new EconomyData();
		if (instance.groups == null) instance.groups = new LinkedHashMap<>();
		if (instance.listings == null) instance.listings = new ArrayList<>();
		if (instance.purchases == null) instance.purchases = new ArrayList<>();
		instance.prune(System.currentTimeMillis());
	}

	public void save() {
		lastSave = System.currentTimeMillis();
		if (!dirty) return;
		dirty = false;
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			AhSniperClient.LOGGER.error("Could not save AH price data", e);
		}
	}

	/** 1234567.8 -> "1.23M", 2500 -> "2,500", 12.5 -> "12.5" */
	public static String fmt(double v) {
		if (v >= 1_000_000_000) return String.format(Locale.ROOT, "%.2fB", v / 1_000_000_000);
		if (v >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", v / 1_000_000);
		if (v >= 100_000) return String.format(Locale.ROOT, "%.1fK", v / 1_000);
		if (v >= 100 || v == Math.rint(v)) return String.format(Locale.ROOT, "%,d", Math.round(v));
		return String.format(Locale.ROOT, "%.1f", v);
	}
}
