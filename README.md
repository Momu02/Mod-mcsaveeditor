# MC Save Editor

Forge 1.20.1 dual-side mod prototype for in-game loaded-chunk visualization, selection, and safe erase.

## Usage

1. Build with Java 17 and ForgeGradle.
2. Install the jar on both client and server.
3. Run `/mcsaveeditor open` as an operator with permission level 3 or higher.
4. In the GUI:
   - Set scan radius and click `Scan`. The scan includes all currently loaded chunks in that range; it does not generate or load additional chunks.
   - Generated chunks receive an 8x8 terrain preview. Select chunks manually with the mouse before deleting them.
   - Left drag selects chunks, right drag removes chunks, middle drag pans, wheel zooms.
   - Click `Confirm Delete` to request server-side erase.

## Design Notes

- No external database is used. The server reads currently loaded vanilla chunks directly and never generates chunks for a scan.
- Scan jobs run on the server tick in small batches, so they do not block the game with a large synchronous scan.
- Terrain previews sample the top block every two blocks and are streamed in batches. Rendering only visits visible chunks and reduces detail while zoomed out. Yellow chunks are protected around players and cannot be selected for deletion.
- Delete jobs run on the server tick thread in small batches. A target chunk is erased only after:
  - forced chunk state is disabled,
  - no player is within the guarded view-distance range,
  - `ServerChunkCache#hasChunk(x, z)` reports that it is not currently loaded.
- Erase removes the chunk slot from `region`, `poi`, and `entities` storage, resetting the target to an ungenerated state for future terrain generation.

## Important Files

- `ServerChunkScanService`: async scan entry point and batching.
- `AnvilChunkIo`: direct `RegionFileStorage` read/write helpers.
- `ServerChunkEraseService`: main-thread erase queue with player/load guards.
- `ChunkEditorScreen`: 2D grid rendering, zoom/pan, range selection, client packet dispatch.
- `ModNetworking`: Forge `SimpleChannel` packet registration.

## Production Hardening

For a live Mohist server, test this against a copied world first. Mohist plugins may hold chunk tickets outside normal Forge flows, so the erase service intentionally skips chunks that remain loaded after retries instead of forcing a dangerous delete.
