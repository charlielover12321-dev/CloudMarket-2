# CloudMarket

A dual economy for Paper 26.2, built for a Bisect-hosted server running Java 25 (Adoptium) with Geyser/Floodgate.

Two economies that share a currency and nothing else:

- **Global cloud market** — one server-wide stock pool of raw materials. Prices move automatically as stock rises and falls. `/shop`, `/buy`, `/sell`.
- **Player shop chests** — independent, player-owned, hand-priced. Right-click a registered chest.

---

## Part 1 — Getting a .jar file

You can't just download the jar; the source has to be compiled first. The easiest way needs no software on your PC at all.

### Option A — let GitHub build it (recommended)

GitHub will compile the plugin for you on their machines and hand you the finished jar. This takes about five minutes the first time.

1. Make a GitHub account at [github.com](https://github.com) if you don't have one.
2. Click **+** (top right) → **New repository**. Name it `cloudmarket`. Leave it Private if you like. Click **Create repository**.
3. On the empty repo page, click **uploading an existing file**.
4. Drag in **everything** from this project folder — `pom.xml`, the `src` folder, and the `.github` folder.
   - **This matters:** the `.github` folder starts with a dot, so your file manager may be hiding it. On Windows, File Explorer → View → tick "Hidden items". On Mac, press `Cmd + Shift + .` in Finder. Without this folder, nothing gets built.
5. Click **Commit changes**.
6. Go to the **Actions** tab. A run called "Build CloudMarket" starts on its own. Wait for the green tick (roughly 2 minutes).
7. Click the finished run, scroll to **Artifacts** at the bottom, download **CloudMarket-jar**. Inside the zip is `CloudMarket-1.0.0.jar`.

If the run fails with a red X, click it and open the "Build the plugin jar" step. The error text there is what I need to fix it — send me that and nothing else.

### Option B — build it in VS Code

Only worth it if you plan to edit the code yourself.

1. Install **JDK 25** from [adoptium.net](https://adoptium.net) (same one your server runs).
2. In VS Code, install the **Extension Pack for Java** and **Maven for Java** extensions.
3. Open this project folder, then Terminal → New Terminal and run:
   ```
   mvn clean package
   ```
4. The jar lands in `target/CloudMarket-1.0.0.jar`.

---

## Part 2 — Installing on your Bisect server

1. Stop the server from the Bisect panel. Don't just restart — stop it.
2. Open **File Manager** → the `plugins` folder.
3. Upload `CloudMarket-1.0.0.jar`.
4. Start the server.
5. Watch the console as it boots. The first start downloads two small libraries
   (HikariCP and sqlite-jdbc) from Maven Central — that's normal, takes a few
   seconds, and only happens once. Then you want to see something like:
   ```
   [CloudMarket] Recipe scan complete: 1400 recipes, 900 materials barred from
   the global market, 38 kept sellable by the storage-block neutrality rule.
   [CloudMarket] Market loaded: 84 tradable materials.
   ```
   That second number being above zero is the important part — it means the storage-block fix is working and your coal, diamonds and raw ores are still tradable.

A `plugins/CloudMarket/` folder appears with five config files. Edit them there, then run `/marketadmin reload` — no restart needed.

### First-hour checklist

- `/marketadmin info` — overall state: how many items trade, how many recipes were scanned, total currency in circulation.
- `/shop` — click through the categories and buy something. Try it from a Bedrock device too.
- `/sell hand` while holding spruce planks — should be refused as crafted. Holding a spruce log should sell.
- `/marketadmin autoconfig` — adds every other raw material the server knows about (including 26.2's sulfur and cinnabar) to `market-items.yml`, switched off, at guessed prices. Review, adjust, flip the ones you want to `enabled: true`.
- Seed some stock so day one isn't an empty shop: `/marketadmin stock oak_log 4000`.

---

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/shop` | Browse the cloud market | `market.buy` |
| `/buy <item> [amount]` | Buy without the GUI | `market.buy` |
| `/sell hand` | Sell the stack you're holding | `market.sell` |
| `/sell hotbar` | Sell everything sellable in your hotbar | `market.sell` |
| `/sell all` | Sell everything sellable in your inventory | `market.sell` |
| `/pay <player> <amount>` | Send money | `market.pay` |
| `/bal [player]` | Check a balance | `market.balance` |
| `/leaderboard` | Top 10 richest | `market.leaderboard` |
| `/shopchest create` | Turn the chest you're looking at into a shop | `market.shopchest` |
| `/shopchest additem [price]` | List the item in your hand | `market.shopchest` |
| `/shopchest removeitem` | Unlist the item in your hand | `market.shopchest` |
| `/shopchest info` | See what a shop sells | `market.shopchest` |
| `/shopchest remove` | Back to a normal chest | `market.shopchest` |
| `/marketadmin setprice <item> <base> <floor> <ceiling> <equilibrium>` | Configure an item | `market.admin.setprice` |
| `/marketadmin stock <item> <amount>` | Set cloud stock | `market.admin.stock` |
| `/marketadmin whitelist <item>` | Let a crafted item trade anyway | `market.admin.whitelist` |
| `/marketadmin autoconfig [enable]` | Generate config for unlisted raw materials | `market.admin.autoconfig` |
| `/marketadmin info [item]` | Market or item detail | `market.admin` |
| `/marketadmin reload` | Reload configs | `market.admin.reload` |

Extra nodes: `market.balance.others`, `market.shopchest.bypass` (open anyone's shop chest), `market.limit.bypass` (ignore hourly sell caps).

**Shop chest tip:** as the owner, a normal right-click previews your shop the way customers see it. **Sneak + right-click** opens the real chest to restock and collect.

---

## Three things I changed from the spec

### 1. The raw-material rule needed a fix

The spec's rule was "bar anything that appears as a recipe result." That collapses on contact with vanilla, because almost every valuable raw material is a recipe output via storage-block uncrafting:

```
9 coal     -> 1 coal block       and   1 coal block     -> 9 coal
9 raw iron -> 1 raw iron block   and   1 raw iron block -> 9 raw iron
9 wheat    -> 1 hay bale         and   1 hay bale       -> 9 wheat
```

Coal, diamond, emerald, lapis, redstone, raw iron/copper/gold, slime balls and wheat would all have been barred, and `craftable-overrides.yml` would have needed to list essentially everything worth trading.

CloudMarket detects **reversible pairs** instead. If `a` of Y makes `b` of X and `c` of X makes `d` of Y, the pair is value-neutral when `b*d == a*c` — cycling it gains you nothing. Those recipes don't count as crafting. A material is barred only if it has at least one recipe that genuinely adds value.

Smelting still counts as value-add, so iron ingots stay barred and you opt them back in deliberately — exactly the behaviour the spec wanted.

### 2. The pricing integral ignored the clamps

The spec's closed-form integral is correct on the unclamped curve, but clamping afterwards leaks money: a batch starting inside the ceiling region and ending on the curve gets paid entirely at curve prices.

It's now a piecewise integral across up to three regions — flat at the ceiling, the log curve, flat at the floor. Verified against a brute-force numeric integral: matches to six decimal places in every region, including batches that span all three. Money is rounded once on the batch total, never per unit, so stacks of 64 can't be farmed for rounding drift.

### 3. The floor price is the real money printer

Crafting was never the unbounded exploit — farms are. Sugar cane, bamboo, kelp, cactus, crops and spawner drops are infinitely farmable and are *not* recipe outputs, so they're sellable by default. The curve crashes their price to the floor and then keeps paying it forever.

Every farmable item in the starter config ships with `floorPrice: 0`. The plugin warns at startup if you set one above zero without an hourly sell cap, and `/marketadmin setprice` warns when you do it by hand.

---

## Bedrock / Geyser notes

Chest-style GUIs translate to Bedrock without help, so one code path serves both editions. The only thing that genuinely differs is typing a number — anvil and sign text entry are unreliable through Geyser. So "buy a custom amount" and "set a price" send Bedrock players a **native Bedrock form** and Java players a chat prompt.

Balances key on UUID, which Floodgate assigns Bedrock players stably. Name lookup is the part that breaks — Bedrock names carry a prefix (a dot by default) and never resolve through Mojang — so CloudMarket keeps its own name index. A Java player can type `/pay Steve 100` and reach the Bedrock player `.Steve`.

Every Floodgate reference is isolated in one lazily-loaded class, so the plugin runs unchanged if you ever remove Floodgate.

---

## Config files

| File | Contents |
|---|---|
| `economy.yml` | Currency, starting balance, tax rate, `/pay` limits, sell caps, autosave |
| `market-items.yml` | Per-material prices. 84 starter entries |
| `craftable-overrides.yml` | Crafted items you want tradable anyway. Ships empty |
| `storage.yml` | SQLite (default, no setup) or MySQL |
| `messages.yml` | Every player-facing string |

### How pricing works

```
price(stock) = clamp(basePrice * 2 * equilibriumStock / (stock + equilibriumStock),
                     floorPrice, ceilingPrice)
```

- Stock at 0 → price is **2x base** (rewards being first to sell something rare)
- Stock at equilibrium → price is **base**
- Stock climbing → price falls toward the floor

Because the price moves *as you transact*, large trades are self-limiting: dumping a double chest of iron walks the price down as you go, so you're paid the average across the curve rather than the top price on every unit.

Tax applies to both buying and selling and is destroyed, not paid to anyone. A round trip costs roughly twice the tax rate, which is what removes any residual buy-then-resell profit.

---

## Known limitations

- **Compiles cleanly against Paper 26.2, but has not run on a live server yet.** All 26 source files build against the real Paper, Floodgate and Vault APIs with no errors. What's untested is runtime behaviour — the recipe scan against a real registry, the GUIs, Bedrock forms.
- **Needs outbound internet on first start.** HikariCP and sqlite-jdbc are downloaded by the server rather than bundled, because the shade plugin can't package Java 25 bytecode. They're cached after the first load. If your host blocks that, tell me and I'll switch the build back to bundling with an explicitly pinned ASM version.
- **A jitpack 401 warning during the build is harmless.** It's a failed metadata lookup for a snapshot dependency of the Floodgate API. The build resolves everything it actually needs and compiles fine.
- **Starter prices are a starting point, not a balanced economy.** No heuristic knows what your server's progression feels like. Watch `/marketadmin info` for the first week and tune.
- **No holograms above shop chests.** Listed as a nice-to-have in the spec; skipped for now. Easy to add later.
- **Vault bank accounts aren't implemented.** The methods return "not supported" rather than silently failing, which would lose money for any plugin that trusted the result.
