# Remplify AH Sniper

Fabric mod for Minecraft 1.21.11, made for lifesteal.net. It snipes `/ah` for items under your price and tracks AH prices so you know what things are worth.

## Keys
| Key | Does |
|---|---|
| **O** | Open the menu (or type `/snipe`) |
| **J** | Start / stop sniping |
| **K** | Add the item you're holding to the snipe list |

Rebind them in Options → Controls → Key Binds → *Remplify AH Sniper*.

## The menu
**Snipe List**: every item you're sniping. Edit the max price in the box (`25000`, `250k`, `1.5m`), turn items on/off, or delete them. **Edit** opens the full settings for an item: max price (total or each), **buy amount**, **budget**, lore filter and Strict/Loose. "+ Add held item" (or K) opens the same screen for what's in your hand.
- *Buy amount*: stop after buying this many (e.g. 5 netherite ingots). Stacks count by size, and a stack that would go over is skipped.
- *Budget*: stop once this much has been spent on the item (e.g. 10m). A listing that would go over the budget is skipped.
- **+ Any item**: buys *any* buy-now listing under the price you set (total or per item), with the same buy amount, budget and lore filter options. Set a budget so it can't spend all your coins. If a listing matches several entries, the one highest in the list is used (and its budget is charged).
  - **Blacklist** (Edit → *Never buy*): items an Any-item snipe will never buy. Search on the left: items seen on the AH block that exact item (type + name, e.g. a custom "Raid TNT"), and item types marked *(all)* block every item of that type whatever it's named. *+ Held item* blocks the exact item in your hand. Remove with X on the right.
- Progress shows under the item (e.g. `3/5 bought · 4.2M/10M spent`). When a limit is reached the item shows **Done** and is skipped; Edit → Reset starts it again. Sniping stops by itself when every item is done.
- *Strict* (default): a listing must have the same item type, name, lore lines (description, enchants) and hidden item data. A normal TNT renamed "Raid TNT" won't match. Listings can have more than the item you added (e.g. extra enchants), never less.
- *Loose*: item type + name only.
- A price can be for the whole listing or per item (useful for stacks like TNT).

**Economy**: search any item that's been seen on the AH and click it to see:
- Lowest price, outliers removed (and who's selling it); a troll-cheap listing is shown separately as "ignored outlier"
- Average with outliers removed (troll 1-coin and 999M listings are filtered out with the IQR method)
- Median, highest, number of listings, sellers and total quantity
- A price-over-time chart
- Filter by past hour / past 24h / past 7 days
- Sort the item list by most listed, cheapest, priciest, newest or A-Z (the number in brackets changes to match)
- "Add to snipes" puts it on your snipe list, pre-filled at 80% of the average

Prices of stacked items are shown per item.

**Purchases**: everything the sniper bought, when, for how much, and how much you saved compared with the average.

**Settings**: refresh speed, sort clicks, max buys per run, stop when damaged, deal alerts, price tooltips, and:
- *Scan all AH pages*: flips through every AH page once to fill the Economy tab with data
- *Debug next /ah*: prints what the mod reads from the AH (buttons, sort button, first listings) in chat
- *Clear price data*

## Where prices come from
The mod records every listing it sees in "Auction | Items": while sniping, while you browse `/ah` yourself, and during *Scan all AH pages*. It tracks **listed** prices (the server doesn't show what actually sold). Each listing is counted once, and data older than 7 days is dropped. Saved in `.minecraft/config/remplify-ahsniper-economy.json`.

Extras:
- **Price tooltips**: any item you hover (including in your own inventory) shows its 24h AH average and lowest, handy when selling.
- **Deal alerts**: a chat message when a new listing is 30%+ cheaper than the lowest listing of the past 24h (threshold adjustable; needs 5+ listings of data). Outliers are removed first, so a troll-cheap or way-overpriced listing doesn't change what counts as a deal.

## Sniping
J (or *Start sniping* in the menu) first goes through **every AH page** once and buys anything already listed under your prices (turn off with *Check all pages first* in Settings). Then it reopens `/ah`, sorts by newest once (4 clicks on the repeater). It then keeps the menu open and re-checks every listing on page 1 every tick; the sort stays on newest while the menu is open. On a match it clicks the listing, re-checks the price and item on **Confirm Buy**, then confirms. After a buy (or if it backs out with Cancel) the AH returns to the list with the sort still on, so it just rescans the whole first page without touching the repeater. It only re-sorts if `/ah` has to be opened fresh. Several identical listings (same seller, same price) listed at once are all bought, one after another. After each buy it checks the listing actually disappeared; if it didn't (e.g. not enough coins) it removes it from Purchases and skips that listing for *Full rescan every* seconds (default 3), after which every item on page 1 is checked again. If the AH menu doesn't show new listings by itself, set *Full refresh* in Settings (e.g. every 15s), or turn *Keep AH open* off to reopen every refresh. Esc or J stops it; it also stops if you take damage. It skips your own listings, and it ignores bid/auction listings: only listings whose AH lore says "Click to buy" and never mentions a bid are bought or counted in the price data.

## Building
Push to GitHub and the included workflow builds it: **Actions** tab → latest run → **Artifacts**; use the jar without `-sources`.
Locally: `./gradlew build` (Windows: `gradlew.bat build`) with JDK 25; the jar is in `build/libs/`. Requires Fabric API.
