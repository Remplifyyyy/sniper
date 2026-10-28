package com.remplify.ahsniper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saved to .minecraft/config/remplify-ahsniper.json so your snipe list survives restarts. */
public class SniperConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("remplify-ahsniper.json");

	public List<SnipeEntry> entries = new ArrayList<>();

	/** Clicks on the sort button (repeater) after opening /ah. 4 = newest first on lifesteal.net. */
	public int sortClicks = 4;
	/** Ticks to wait between AH refreshes when reopening /ah (20 ticks = 1 second). */
	public int refreshTicks = 30;
	/**
	 * true: open /ah and sort once, then keep the menu open and re-check it every couple of ticks
	 * (the sort stays on "newest" until the menu is closed). false: reopen /ah and re-sort every refresh.
	 */
	public boolean stayOpen = true;
	/** With stayOpen: also do a full reopen + sort every N seconds (0 = never). For AH menus that don't update by themselves. */
	public int fullRefreshSeconds = 0;
	/** Ticks between GUI clicks. */
	public int clickDelay = 1;
	/** Ticks to let the menu settle after sorting before reading prices. */
	public int settleTicks = 2;
	/** A listing that failed to buy is skipped for this many seconds; then every listing on page 1 is checked again. */
	public int rescanSeconds = 3;
	/** When sniping starts, check every AH page once (buying anything under your prices) before watching page 1. */
	public boolean sweepOnStart = true;
	/** Bumped when defaults change, so older config files get the faster timings. */
	public int configVersion = 0;
	/** Stop sniping automatically if you take damage. */
	public boolean stopOnDamage = true;
	/** Stop after this many purchases in one run (0 = no limit). */
	public int maxBuysPerRun = 0;

	/** Record prices of every listing seen in /ah for the Economy tab. */
	public boolean recordPrices = true;
	/** Chat alert when a new listing is this % or more under the past 24h lowest listing, outliers removed (needs 5+ listings). */
	public boolean dealAlerts = true;
	public int dealPercent = 30;
	/** Show AH average/lowest on item tooltips. */
	public boolean priceTooltips = true;

	private static SniperConfig instance;

	public static SniperConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH)) {
					instance = GSON.fromJson(r, SniperConfig.class);
				}
			}
		} catch (Exception e) {
			AhSniperClient.LOGGER.error("Could not read AH Sniper config, starting fresh", e);
		}
		if (instance == null) instance = new SniperConfig();
		if (instance.entries == null) instance.entries = new ArrayList<>();
		if (instance.configVersion < 2) {
			instance.clickDelay = 1;
			instance.settleTicks = 2;
			instance.configVersion = 2;
			save();
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH)) {
				GSON.toJson(get(), w);
			}
		} catch (Exception e) {
			AhSniperClient.LOGGER.error("Could not save AH Sniper config", e);
		}
	}
}
