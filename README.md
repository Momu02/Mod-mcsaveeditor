# MC Save Editor

Forge 1.20.1 dual-side mod prototype for in-game chunk visualization, filtering, selection, and safe erase.

## Usage

1. Build with Java 17 and ForgeGradle.
2. Install the jar on both client and server.
3. Run `/mcsaveeditor open` as an operator with permission level 3 or higher.
4. In the GUI:
   - Set scan radius and maximum `InhabitedTime` minutes.
   - Click `Scan` to asynchronously scan Anvil NBT on the server.
   - Left drag selects chunks, right drag removes chunks, middle drag pans, wheel zooms.
   - Click `Confirm Delete` to request server-side erase.

## Design Notes

- No external database is used. The server reads vanilla chunk NBT `InhabitedTime` directly from `.mca` files.
- Scan jobs run on a daemon executor and use `RegionFileStorage` directly, so they do not call `ServerLevel#getChunk` and do not generate or load chunks.
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
