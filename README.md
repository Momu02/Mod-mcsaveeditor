# MC Save Editor

Forge 1.20.1 dual-side mod for in-game saved-chunk visualization, filtering, data updates, and safe deletion.

## Server Promotion

The mod doubles as a promotional tool for the **AmityMC** server (`amitymc.com`). When distributed to other players, it works to bring them to the server:

- **Forced splash screen** — on entering a world, a promo page is shown (configurable, default 5 seconds) before it can be skipped. Buttons on it connect straight to `amitymc.com` or open the website.
- **Title screen button** — "加入 AmityMC 服务器" on the main menu joins the server with one click.
- **Periodic chat reminders** — a clickable reminder is posted in chat every few minutes while playing; clicking it joins the server directly.
- **Editor banner** — the chunk editor sidebar shows a join button and AmityMC branding.
- **Client command** — `/amitymc join` and `/amitymc promo` work on the client.

All promotion settings live in the client config (`promo.*`): `splashEnabled`, `splashSeconds`, `reminderMinutes`, `chatReminders`, `titleButton`, `editorBanner`.

## Usage

1. Build with Java 17 and ForgeGradle.
2. Install the jar on both client and server.
3. Run `/mcsaveeditor open` as an operator with permission level 3 or higher.
4. In the GUI:
   - Enable the range and inhabited-time filters as needed, then click scan. A filter is ignored unless its checkbox is enabled.
   - Generated chunks receive an 8x8 terrain preview. Scan results are not selected automatically.
   - Left drag selects chunks, right drag removes chunks, middle drag pans, wheel zooms.
   - Click `Update` to run Minecraft's current chunk data fixer without removing blocks, block entities, entities, or POI data.
   - Click `Delete Completely` to remove the chunk from terrain, POI, and entity region storage. The chunk is generated again when it is next visited.

## Design Notes

- No external database is used. The server reads currently loaded vanilla chunks directly and never generates chunks for a scan.
- Scan jobs run on the server tick in small batches, so they do not block the game with a large synchronous scan.
- Terrain previews sample the top block every two blocks and are streamed in batches. Rendering only visits visible chunks and reduces detail while zoomed out. Yellow chunks are protected around players and cannot be selected for deletion.
- Delete jobs run on the server tick thread in small batches. A target chunk is erased only after:
  - forced chunk state is disabled,
  - no player is within the guarded view-distance range,
  - `ServerChunkCache#hasChunk(x, z)` reports that it is not currently loaded.
- Complete deletion removes the chunk slot from `region`, `poi`, and `entities` storage, resetting the target to an ungenerated state for future terrain generation. The live server uses its own chunk storage queue for terrain deletion so a later save cannot restore stale data. Physical `.mca` compaction requires an offline tool because the running server keeps region files open.
- Updating a chunk means upgrading its stored NBT to the current Minecraft data version. Minecraft cannot regenerate arbitrary terrain at the same coordinates while also guaranteeing that overlapping player-built blocks remain unchanged.

## Important Files

- `ServerChunkScanService`: async scan entry point and batching.
- `AnvilChunkIo`: direct `RegionFileStorage` read/write helpers.
- `ServerChunkEraseService`: main-thread erase queue with player/load guards.
- `ChunkEditorScreen`: 2D grid rendering, zoom/pan, range selection, client packet dispatch.
- `ModNetworking`: Forge `SimpleChannel` packet registration.

## Production Hardening

For a live Mohist server, test this against a copied world first. Mohist plugins may hold chunk tickets outside normal Forge flows, so the erase service intentionally skips chunks that remain loaded after retries instead of forcing a dangerous delete.
