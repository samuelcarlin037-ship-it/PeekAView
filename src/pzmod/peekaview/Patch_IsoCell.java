package pzmod.peekaview;

import java.util.ArrayList;
import java.util.Arrays;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoUtils;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoTree;

public class Patch_IsoCell {

    // Expands the POI fan that seeds building cutaway. Re-implements
    // vanilla's raster+diamond shape scaled to PeekAViewMod.range.
    @Patch(className = "zombie.iso.IsoCell",
           methodName = "GetSquaresAroundPlayerSquare")
    public static class Patch_GetSquaresAroundPlayerSquare {

        private static final int MAX_RADIUS = PeekAViewMod.MAX_RANGE;

        // Diamond half-width scales with radius to match vanilla's
        // shape proportionally. Vanilla uses 4.5 at its effective
        // radius of 5 (raster 10 wide, deltaX/Y in [-4, +5]); we
        // mirror that ratio so radius R clips at half-width 0.9·R.
        // Earlier we used a constant 22.5 (sized for MAX_RADIUS)
        // which made the diamond never clip at small radii — at
        // radius 6 the 14-wide raster emitted ~196 tiles where
        // vanilla's R=5 path emits ~50, so each +1 step on the
        // slider above MIN added far more tiles than expected.
        private static final float DIAMOND_HALF_WIDTH_PER_RADIUS = 4.5f / 5.0f;

        // Inside this box around the player we mirror vanilla exactly
        // (no wall-adjacency / LOS filter). 5 covers vanilla's 10x10
        // half-width-4.5 diamond envelope.
        private static final int VANILLA_KEEP_RADIUS = 5;

        // Cache stores coordinates, not IsoGridSquare refs: squares
        // live in a pool and WorldReuser.discard can reassign a ref to
        // a different (x,y,z) asynchronously. Per-player slots because
        // split-screen alternates playerIndex within one wall-clock
        // frame; a single shared slot thrashes to 0% hit rate.
        //
        // Fields are public because @Patch.OnEnter inlines the advice
        // into IsoCell's access context — private fields throw
        // IllegalAccessError at runtime. Compile-time constants stay
        // private (javac folds them into literals).
        private static final int MAX_RASTER_SIZE = MAX_RADIUS * 2 + 2;
        private static final int MAX_COORDS = MAX_RASTER_SIZE * MAX_RASTER_SIZE;
        private static final int MAX_PLAYERS = IsoPlayer.MAX;

        public static final IsoCell[] cachedCell = new IsoCell[MAX_PLAYERS];
        public static final int[] cachedPxFloor = new int[MAX_PLAYERS];
        public static final int[] cachedPyFloor = new int[MAX_PLAYERS];
        public static final int[] cachedZ = new int[MAX_PLAYERS];
        public static final int[][] cachedLeftX  = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[][] cachedLeftY  = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[] cachedLeftCount = new int[MAX_PLAYERS];
        public static final int[][] cachedRightX = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[][] cachedRightY = new int[MAX_PLAYERS][MAX_COORDS];
        public static final int[] cachedRightCount = new int[MAX_PLAYERS];

        static {
            invalidateCache();
        }

        // Called from PeekAViewMod.setRange on slider change.
        public static void invalidateCache() {
            Arrays.fill(cachedPxFloor, Integer.MIN_VALUE);
            Arrays.fill(cachedPyFloor, Integer.MIN_VALUE);
            Arrays.fill(cachedZ, Integer.MIN_VALUE);
        }

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoCell cell,
                                    @Patch.Argument(0) IsoPlayer player,
                                    @Patch.Argument(1) IsoGridSquare square,
                                    @Patch.Argument(2) ArrayList outLeft,
                                    @Patch.Argument(3) ArrayList outRight) {
            try {
                if (!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()) return false;
                if (cell == null || player == null || square == null) return false;

                int playerIndex = IsoCamera.frameState.playerIndex;
                if (playerIndex < 0 || playerIndex >= MAX_PLAYERS) return false;

                // Indoor: vanilla's 5-tile cutaway is enough, the
                // extension barely changes anything visible there.
                // Fall through so vanilla runs unmodified.
                if (PeekAViewMod.isCameraPlayerIndoor()) return false;

                // Slider at MIN_RANGE = vanilla cutaway. Fall through
                // so vanilla's 10×10 raster + diamond-half-width-4.5
                // shape runs unmodified.
                if (PeekAViewMod.range <= PeekAViewMod.MIN_RANGE) return false;

                float px = player.getX();
                float py = player.getY();
                int pxFloor = PZMath.fastfloor(px);
                int pyFloor = PZMath.fastfloor(py);
                int z = square.getZ();

                int[] leftX = cachedLeftX[playerIndex];
                int[] leftY = cachedLeftY[playerIndex];
                int[] rightX = cachedRightX[playerIndex];
                int[] rightY = cachedRightY[playerIndex];
                int leftCount = cachedLeftCount[playerIndex];
                int rightCount = cachedRightCount[playerIndex];

                if (cell == cachedCell[playerIndex]
                        && pxFloor == cachedPxFloor[playerIndex]
                        && pyFloor == cachedPyFloor[playerIndex]
                        && z == cachedZ[playerIndex]) {
                    for (int i = 0; i < leftCount; ++i) {
                        IsoGridSquare sq = cell.getGridSquare(leftX[i], leftY[i], z);
                        if (sq != null) outLeft.add(sq);
                    }
                    for (int i = 0; i < rightCount; ++i) {
                        IsoGridSquare sq = cell.getGridSquare(rightX[i], rightY[i], z);
                        if (sq != null) outRight.add(sq);
                    }
                    return true;
                }

                leftCount = 0;
                rightCount = 0;

                // Snapshot once per miss so bounds stay consistent if
                // Lua flips the slider mid-frame.
                int radius = PeekAViewMod.range;
                if (radius < PeekAViewMod.MIN_RANGE) radius = PeekAViewMod.MIN_RANGE;
                if (radius > MAX_RADIUS) radius = MAX_RADIUS;
                int rasterSize = radius * 2 + 2;
                float diamondHalfWidth = (float) radius * DIAMOND_HALF_WIDTH_PER_RADIUS;

                int startX = PZMath.fastfloor(px - (float) radius);
                int startY = PZMath.fastfloor(py - (float) radius);

                for (int y = startY; y < startY + rasterSize; ++y) {
                    for (int x = startX; x < startX + rasterSize; ++x) {
                        if (x < pxFloor && y < pyFloor) continue;
                        if (x == pxFloor && y == pyFloor) continue;
                        float deltaX = (float) x - px;
                        float deltaY = (float) y - py;
                        if (!(deltaY < deltaX + diamondHalfWidth)) continue;
                        if (!(deltaY > deltaX - diamondHalfWidth)) continue;
                        IsoGridSquare iterSquare = cell.getGridSquare(x, y, z);
                        if (iterSquare == null) continue;

                        // Outside vanilla envelope: drop non-wall-adjacent
                        // squares (each emitted POI seeds a full
                        // cutawayVisit pass downstream, so raster is only
                        // useful adjacent to a wall) and POIs behind the
                        // first wall/window/door from the player (keep
                        // feature scope: extended cutaway, not see-through-
                        // rooms).
                        int dx = x - pxFloor; if (dx < 0) dx = -dx;
                        int dy = y - pyFloor; if (dy < 0) dy = -dy;
                        if (dx > VANILLA_KEEP_RADIUS || dy > VANILLA_KEEP_RADIUS) {
                            if (!isNearWall(iterSquare, cell, x, y, z)) continue;
                            if (!hasLineOfSight(cell, pxFloor, pyFloor, x, y, z)) continue;
                        }

                        if (deltaY >= deltaX) {
                            leftX[leftCount] = x;
                            leftY[leftCount] = y;
                            leftCount++;
                            outLeft.add(iterSquare);
                        }
                        if (deltaY <= deltaX) {
                            rightX[rightCount] = x;
                            rightY[rightCount] = y;
                            rightCount++;
                            outRight.add(iterSquare);
                        }
                    }
                }

                cachedCell[playerIndex] = cell;
                cachedPxFloor[playerIndex] = pxFloor;
                cachedPyFloor[playerIndex] = pyFloor;
                cachedZ[playerIndex] = z;
                cachedLeftCount[playerIndex] = leftCount;
                cachedRightCount[playerIndex] = rightCount;
                return true;
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_GetSquaresAroundPlayerSquare enter error", t);
                return false;
            }
        }

        // PZ stores walls on the owning tile: N/W on the square itself,
        // S = y+1's N, E = x+1's W. Three lookups cover all four sides.
        // Public for advice-inlining access context.
        public static boolean isNearWall(IsoGridSquare sq, IsoCell cell, int x, int y, int z) {
            if (sq.getWall() != null) return true;
            IsoGridSquare s;
            if ((s = cell.getGridSquare(x + 1, y, z)) != null && s.getWall() != null) return true;
            if ((s = cell.getGridSquare(x, y + 1, z)) != null && s.getWall() != null) return true;
            return false;
        }

        // Bresenham walk from (px,py) to (tx,ty) on z. Drops on the
        // first wall/window/door crossing (zero crossings only).
        public static boolean hasLineOfSight(IsoCell cell, int px, int py, int tx, int ty, int z) {
            int dx = tx - px; if (dx < 0) dx = -dx;
            int dy = ty - py; if (dy < 0) dy = -dy;
            int sx = px < tx ? 1 : -1;
            int sy = py < ty ? 1 : -1;
            int err = dx - dy;
            int cx = px, cy = py;
            // Cap iterations defensively — worst case ≈ RADIUS*2 steps.
            for (int guard = 0; guard < 64; ++guard) {
                if (cx == tx && cy == ty) return true;
                int prevX = cx, prevY = cy;
                int e2 = err * 2;
                if (e2 > -dy) { err -= dy; cx += sx; }
                if (e2 < dx) { err += dx; cy += sy; }
                if (crossesWall(cell, prevX, prevY, cx, cy, z)) {
                    return false;
                }
            }
            return true;
        }

        // Bresenham steps are axis-aligned — 4 directions. Windows and
        // door-walls count as crossings (else a window in the nearest
        // wall lets the walk slip into the room behind it).
        public static boolean crossesWall(IsoCell cell, int fx, int fy, int tx, int ty, int z) {
            if (tx > fx) {
                IsoGridSquare to = cell.getGridSquare(tx, ty, z);
                return to != null && hasWestBarrier(to);
            }
            if (tx < fx) {
                IsoGridSquare from = cell.getGridSquare(fx, fy, z);
                return from != null && hasWestBarrier(from);
            }
            if (ty > fy) {
                IsoGridSquare to = cell.getGridSquare(tx, ty, z);
                return to != null && hasNorthBarrier(to);
            }
            if (ty < fy) {
                IsoGridSquare from = cell.getGridSquare(fx, fy, z);
                return from != null && hasNorthBarrier(from);
            }
            return false;
        }

        public static boolean hasNorthBarrier(IsoGridSquare sq) {
            return sq.has(IsoFlagType.WallN)
                || sq.has(IsoFlagType.WindowN)
                || sq.has(IsoFlagType.DoorWallN)
                || sq.has(IsoFlagType.doorN);
        }

        public static boolean hasWestBarrier(IsoGridSquare sq) {
            return sq.has(IsoFlagType.WallW)
                || sq.has(IsoFlagType.WindowW)
                || sq.has(IsoFlagType.DoorWallW)
                || sq.has(IsoFlagType.doorW);
        }
    }

    // Vanilla DrawStencilMask renders a circular alpha texture
    // (media/mask_circledithernew.png) once per frame centered on the
    // player. The stencil it writes caps where translucent passes
    // (tree fade, wall cutaway) can render — outside the mask,
    // nothing. Vanilla's size matches the ~5-tile cutaway raster.
    //
    // We keep vanilla's circle and after it runs, add per-tile
    // stencil writes in a Euclidean circle of radius treeFadeRange
    // gated by sq.isCanSee (no fade through walls, no ghost fade in
    // unseen forest). Persistence exception: a tile keeps coverage
    // while its tree is mid-fade-up (fadeAlpha < 1) so the alphaStep
    // climb stays visible instead of snapping to opaque on cone
    // exit. Stencil is additive via GL_REPLACE with ref 128.
    @Patch(className = "zombie.iso.IsoCell",
           methodName = "drawStencilMask",
           strictMatch = true)
    public static class Patch_DrawStencilMask {

        @Patch.OnExit
        public static void exit(@Patch.This IsoCell cell) {
            try {
                if (!PeekAViewMod.fadeNWTrees) return;
                if (!PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()) return;
                // Outdoor-only: indoor we don't extend the stencil
                // mask. Mirrors the gate in Patch_isTranslucentTree
                // so the renderFlag and stencil-coverage halves of
                // the tree-fade feature stay in sync.
                if (PeekAViewMod.isCameraPlayerIndoor()) return;

                // Use IsoCamera.frameState.playerIndex (currently
                // rendering player) to stay consistent with every other
                // patch in the mod. IsoPlayer.getPlayerIndex() returns
                // the local active player and would write the stencil
                // mask for the wrong player in split-screen.
                int pidx = IsoCamera.frameState.playerIndex;
                if (pidx < 0 || pidx >= IsoPlayer.MAX) return;
                IsoPlayer player = IsoPlayer.players[pidx];
                if (player == null) return;

                Texture tex2 = Texture.getSharedTexture("media/mask_circledithernew.png");
                if (tex2 == null) return;

                // player.getX/Y/Z, not getCurrentSquare(): the latter is
                // null while the player is in a vehicle, which would
                // skip the stencil extension and leave faded trees
                // outside vanilla's 5-tile circle invisible.
                int px = PZMath.fastfloor(player.getX());
                int py = PZMath.fastfloor(player.getY());
                int pz = PZMath.fastfloor(player.getZ());
                int range = PeekAViewMod.treeFadeRange;
                int ts = Core.tileScale;

                // Per-tile mask geometry. 192×320 overshoots the iso-
                // tile footprint (64×32) on purpose: tree sprites
                // extend upward on screen from the base tile, so the
                // stencil needs coverage ABOVE the tile for the
                // sprite's GL_EQUAL pass to pass. The tallest sprites
                // reach ~7 tile-heights up (crown at sy - 224 px);
                // tileFootprintYOffset = 256 covers them with margin.
                // renderW = 192 (halfW = 96) over-covers the typical
                // sprite ±32-64 px horizontal extent so the outermost
                // leaves don't end up uncovered when the neighbouring
                // tile sits just outside the circle range and writes
                // no stencil there.
                int renderW = 192 * ts;
                int renderH = 320 * ts;
                int halfW = renderW / 2;
                int tileFootprintYOffset = 256 * ts;

                IndieGL.glStencilMask(255);
                IndieGL.enableStencilTest();
                IndieGL.enableAlphaTest();
                IndieGL.glAlphaFunc(516, 0.1f);
                IndieGL.glStencilFunc(519, 128, 255);
                IndieGL.glStencilOp(7680, 7680, 7681);
                IndieGL.glColorMask(false, false, false, false);

                float offX = IsoCamera.getOffX();
                float offY = IsoCamera.getOffY();

                // Vanilla's circular stencil already covers roughly the
                // inner 4 tiles around the player. Skipping that area
                // avoids overlapping our per-tile dither with vanilla's
                // gradient — the two independent patterns produce a
                // flickery moiré at sub-pixel camera shifts.
                int vanillaSkip = PeekAViewMod.MIN_RANGE - 1;

                // Asymmetric coverage: SE-quadrant always covers
                // MAX_TREE_FADE_RANGE because that's vanilla's natural
                // fade domain (FBORenderCell.isTranslucentTree returns
                // true for any on-screen SE-quadrant tile regardless of
                // slider) and the user expects vanilla's fade to keep
                // working independent of our slider. Other quadrants
                // follow the slider-controlled cone reach. Persistence
                // buffer ring around both keeps fading-up trees stencil-
                // covered until fadeAlpha == 1.
                int seQuadrantRange = PeekAViewMod.MAX_TREE_FADE_RANGE;
                int otherQuadrantRange = range;
                int loopRange = Math.max(otherQuadrantRange, seQuadrantRange);
                final int persistenceBuffer = 5;
                int extendedRange = loopRange + persistenceBuffer;
                int extendedRangeSq = extendedRange * extendedRange;
                int seQuadrantRangeSq = seQuadrantRange * seQuadrantRange;
                int otherQuadrantRangeSq = otherQuadrantRange * otherQuadrantRange;
                int vanillaSkipSq = vanillaSkip * vanillaSkip;

                for (int dy = -extendedRange; dy <= extendedRange; ++dy) {
                    for (int dx = -extendedRange; dx <= extendedRange; ++dx) {
                        int distSq = dx * dx + dy * dy;
                        if (distSq > extendedRangeSq) continue;
                        if (distSq <= vanillaSkipSq) continue;

                        boolean inSEQuadrant = (dx >= 0 && dy >= 0);
                        int effRangeSqForTile = inSEQuadrant ? seQuadrantRangeSq : otherQuadrantRangeSq;
                        boolean inCircle = (distSq <= effRangeSqForTile);

                        int tx = px + dx;
                        int ty = py + dy;
                        IsoGridSquare sq = cell.getGridSquare(tx, ty, pz);
                        if (sq == null) continue;

                        if (inCircle) {
                            // LOS gate. Persistence exception: a tile
                            // that just left LOS keeps coverage while
                            // its tree fades back up.
                            if (!sq.isCanSee(pidx)) {
                                IsoTree fadingTree = sq.getTree();
                                if (fadingTree == null || fadingTree.fadeAlpha >= 1.0f) continue;
                            }
                        } else {
                            // Buffer ring: persistence only.
                            IsoTree fadingTree = sq.getTree();
                            if (fadingTree == null || fadingTree.fadeAlpha >= 1.0f) continue;
                        }

                        float sx = IsoUtils.XToScreen(tx, ty, pz, 0) - offX;
                        float sy = IsoUtils.YToScreen(tx, ty, pz, 0) - offY;
                        tex2.renderstrip((int) sx - halfW, (int) sy - tileFootprintYOffset,
                                renderW, renderH, 1.0f, 1.0f, 1.0f, 1.0f, null);
                    }
                }

                IndieGL.glColorMask(true, true, true, true);
                IndieGL.glStencilFunc(519, 0, 255);
                IndieGL.glStencilOp(7680, 7680, 7680);
                IndieGL.glStencilMask(127);
                IndieGL.glAlphaFunc(519, 0.0f);
            } catch (Throwable t) {
                PeekAViewMod.trace("Patch_DrawStencilMask exit error", t);
            }
        }
    }

    // == Stair feature ==
    // Restore frameState.camCharacterSquare to realSquare for the
    // duration of IsoCell.update. updateWeatherFx runs inside, and
    // IsoWeatherFX.update reads camCharacterSquare.has(exterior) to
    // gate the indoorsAlphaMod ramp; a fakeSquare leftover from the
    // prior render — common on outdoor stair → upper-floor landing
    // tiles that aren't marked exterior — would otherwise ramp rain
    // alpha to zero mid-climb. RECENT_FRAMES > 0 because frameCount
    // has already been bumped by GameWindow.frameStep when update()
    // runs, so the strict FakeWindow.isReady equality check misses.
    @Patch(className = "zombie.iso.IsoCell", methodName = "update")
    public static class Patch_update {

        private static final int RECENT_FRAMES = 2;

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                int idx = fs.playerIndex;
                if (idx < 0 || idx >= FakeWindow.MAX_PLAYERS) return;
                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs == null || ffs.realSquare == null) return;
                if ((fs.frameCount - ffs.frameCounter) > RECENT_FRAMES) return;
                if (fs.camCharacterSquare == ffs.realSquare) return;
                savedSquare = fs.camCharacterSquare;
                fs.camCharacterSquare = ffs.realSquare;
                opened = true;
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoCell.update enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare) {
            if (!opened) return;
            try {
                IsoCamera.frameState.camCharacterSquare = savedSquare;
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoCell.update exit failed", t);
            }
        }
    }

    // == Stair feature ==
    // Non-FBO render-pass swap: same idea as Patch_FBORenderCell.Patch_renderInternal
    // but on the legacy IsoCell path. Active only when fboRenderChunk is off.
    @Patch(className = "zombie.iso.IsoCell", methodName = "renderInternal")
    public static class Patch_renderInternal {

        @Patch.OnEnter
        public static void enter(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("idx") int idx,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentSwapped") boolean currentSwapped,
                @Patch.Local("posMutated") boolean posMutated,
                @Patch.Local("sqSwapped") boolean sqSwapped,
                @Patch.Local("savedRoom") IsoRoom savedRoom,
                @Patch.Local("savedRoomId") long savedRoomId,
                @Patch.Local("savedExterior") boolean savedExterior) {
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                idx = fs.playerIndex;
                if (!FakeWindow.isReady(idx)) return;

                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs == null) return;

                // Captures only — commit opened=true immediately after so
                // any throw further down still hits exit cleanup. See
                // FBORenderCell.Patch_renderInternal for the full ordering
                // rationale (mirror of this patch).
                savedX = fs.camCharacterX;
                savedY = fs.camCharacterY;
                savedZ = fs.camCharacterZ;
                savedSquare = fs.camCharacterSquare;
                opened = true;
                FakeWindow.renderingFake.set(ffs);

                fs.camCharacterX = ffs.fakePos.x;
                fs.camCharacterY = ffs.fakePos.y;
                fs.camCharacterZ = ffs.fakePos.z;
                fs.camCharacterSquare = ffs.fakeSquare;

                if (ffs.camChar != null && ffs.fakeSquare != null) {
                    savedCurrent = FakeWindow.readCurrentField(ffs.camChar);
                    ffs.camChar.setCurrent(ffs.fakeSquare);
                    currentSwapped = true;
                }

                // Direct-field write of x/y/z skips setX/Y/Z's side
                // effects on nx, scriptnx, lx, ly, lz. fieldMutated.set(1)
                // BEFORE writeFakePos so non-render readers see the
                // shadow during the gap. Rollback on Reflection failure.
                if (ffs.camChar != null) {
                    FakeWindow.fieldMutated.set(idx, 1);
                    if (FakeWindow.writeFakePos(ffs.camChar, ffs.fakePos.x, ffs.fakePos.y, ffs.fakePos.z)) {
                        posMutated = true;
                    } else {
                        FakeWindow.fieldMutated.set(idx, 0);
                    }
                }

                IsoGridSquare fake = ffs.fakeSquare;
                IsoGridSquare floor = ffs.floorSquare;
                if (fake != null && floor != null && fake.room == null && floor.room != null) {
                    savedRoom = fake.room;
                    savedRoomId = fake.roomId;
                    savedExterior = fake.getProperties().has(IsoFlagType.exterior);
                    sqSwapped = true;
                    fake.room = floor.room;
                    fake.roomId = floor.getRoomID();
                    if (savedExterior) {
                        fake.getProperties().unset(IsoFlagType.exterior);
                    }
                }
            } catch (Throwable t) {
                PeekAViewMod.trace("stair: IsoCell.renderInternal enter failed", t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Local("opened") boolean opened,
                @Patch.Local("idx") int idx,
                @Patch.Local("savedX") float savedX,
                @Patch.Local("savedY") float savedY,
                @Patch.Local("savedZ") float savedZ,
                @Patch.Local("savedSquare") IsoGridSquare savedSquare,
                @Patch.Local("savedCurrent") IsoGridSquare savedCurrent,
                @Patch.Local("currentSwapped") boolean currentSwapped,
                @Patch.Local("posMutated") boolean posMutated,
                @Patch.Local("sqSwapped") boolean sqSwapped,
                @Patch.Local("savedRoom") IsoRoom savedRoom,
                @Patch.Local("savedRoomId") long savedRoomId,
                @Patch.Local("savedExterior") boolean savedExterior) {
            if (!opened) return;
            try {
                IsoCamera.FrameState fs = IsoCamera.frameState;
                fs.camCharacterX = savedX;
                fs.camCharacterY = savedY;
                fs.camCharacterZ = savedZ;
                fs.camCharacterSquare = savedSquare;

                FakeFrameState ffs = FakeWindow.get(idx);
                if (ffs != null) {
                    if (posMutated && ffs.camChar != null) {
                        FakeWindow.writeRealPos(ffs.camChar, savedX, savedY, savedZ);
                        FakeWindow.fieldMutated.set(idx, 0);
                    }
                    if (currentSwapped && ffs.camChar != null) {
                        ffs.camChar.setCurrent(savedCurrent);
                    }
                    if (sqSwapped && ffs.fakeSquare != null) {
                        IsoGridSquare fake = ffs.fakeSquare;
                        fake.room = savedRoom;
                        fake.roomId = savedRoomId;
                        if (savedExterior) {
                            fake.getProperties().set(IsoFlagType.exterior);
                        }
                    }
                }
            } finally {
                FakeWindow.renderingFake.remove();
            }
        }
    }

    // doBuildingInternal reads IsoCamera.getCameraCharacterZ(), which
    // calls isoCameraGameCharacter.getZ() — a dynamic getter, not the
    // frameState field. Arm renderingFake for the method's duration so
    // Patch_getZ returns fake, keeping buildZ consistent with the
    // pickedTile projection from Patch_UIManager. Without this, the
    // bRender=false click-trigger from UIManager.update reads real-Z
    // and the WalkTo target lands on a mid-air square.
    @Patch(className = "zombie.iso.IsoCell", methodName = "doBuildingInternal")
    public static class Patch_doBuildingInternal {
        @Patch.OnEnter
        public static void enter(@Patch.Local("opened") boolean opened) {
            FakeFrameState ffs = FakeWindow.get(0);
            if (ffs == null || !ffs.stairLatchArmed) return;
            if (IsoCamera.frameState.frameCount - ffs.lastStrictActivationFrame > 3) return;
            if (FakeWindow.renderingFake.get() != null) return;
            FakeWindow.renderingFake.set(ffs);
            opened = true;
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Local("opened") boolean opened) {
            if (!opened) return;
            FakeWindow.renderingFake.remove();
        }
    }
}
